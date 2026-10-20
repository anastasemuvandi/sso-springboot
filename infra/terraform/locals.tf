# =============================================================================
# Reading the two source-of-truth files, and validating them before anything is
# written to Keycloak.
# =============================================================================

locals {
  # ---- registry (ADR-0001 §5.4) --------------------------------------------
  registry = try(yamldecode(file(var.institutions_file)).institutions, [])

  # ---- delegated-permission templates (ADR-0001 §4) ------------------------
  templates_doc  = jsondecode(file(var.delegated_templates_file))
  templates      = local.templates_doc.templates
  default_tiers  = local.templates_doc.assignments.default
  tiers_override = try(local.templates_doc.assignments.by_institution, {})

  all_institutions = { for i in local.registry : i.slug => i }

  # Tiers per institution — the same resolution the wrapper does:
  # by_institution wins, otherwise the default tier list.
  tiers_for = {
    for slug, _ in local.all_institutions :
    slug => lookup(local.tiers_override, slug, local.default_tiers)
  }

  # What THIS root module manages: active, in this realm, not a §6 exception.
  # A decommissioned or exception institution is left in the registry (so its
  # slug can never be reissued) but is not provisioned.
  managed_institutions = {
    for slug, i in local.all_institutions : slug => i
    if try(i.status, "active") == "active"
    && try(i.realm, var.realm) == var.realm
    && try(i.separate_realm, false) == false
  }

  # ---------------------------------------------------------------------------
  # Registry validation. Cheap, and it fails the plan rather than half-applying
  # a bad slug into token claims and role names that can never be renamed.
  # (`make registry-validate`, the wrapper's RegistryValidator, runs the same checks with no
  # Keycloak connection, so CI can gate a PR before it ever reaches a plan.)
  # ---------------------------------------------------------------------------
  invalid_slugs = [
    for i in local.registry : i.slug
    if length(regexall("^[a-z0-9][a-z0-9-]*$", i.slug)) == 0
  ]

  duplicate_slugs = [
    for slug, entries in { for i in local.registry : i.slug => i... } :
    slug if length(entries) > 1
  ]

  # One IPPIS code may map to only one slug, or the inbound normalizer
  # (ADR-0001 §5.2) has no deterministic answer for that employee.
  ippis_pairs = flatten([
    for i in local.registry : [
      for code in try(i.ippis_codes, []) : { code = upper(code), slug = i.slug }
    ]
  ])

  ippis_collisions = [
    for code, slugs in { for p in local.ippis_pairs : p.code => p.slug... } :
    code if length(distinct(slugs)) > 1
  ]

  unknown_tiers = distinct(flatten([
    for slug, tiers in local.tiers_for : [
      for t in tiers : "${slug} -> ${t}"
      if !contains(keys(local.templates), t)
    ]
  ]))

  # Client-ids are realm-global; two institutions must not race for one.
  all_client_ids = flatten([
    for slug, i in local.managed_institutions : [
      for a in try(i.apps, []) : "${slug}-${a.name}"
    ]
  ])

  duplicate_client_ids = [
    for cid, entries in { for c in local.all_client_ids : c => c... } :
    cid if length(entries) > 1
  ]

  # No slug may be a hyphen-prefix of another.
  #
  # Client-management scoping is a glob on the client-id (`<slug>-*`). Role and
  # member scoping use `inst:<slug>:` and `/<slug>/`, whose separators cannot
  # appear inside a slug — but `-` can (ADR-0001 §5.1 rule 2 permits
  # `local-govt`). With both `local` and `local-govt` onboarded,
  # `local-govt-portal` matches `local-*`, and one institution's admins reach
  # another's apps. The client-id alone cannot disambiguate, so the collision is
  # forbidden at onboarding, while the slug is still free to change.
  slug_prefix_collisions = flatten([
    for outer in keys(local.all_institutions) : [
      for inner in keys(local.all_institutions) :
      "${inner} extends ${outer}"
      if inner != outer && startswith(inner, "${outer}-")
    ]
  ])
}

# A gate every institution module depends on, so a registry error surfaces as
# one clear message at plan time instead of N confusing resource errors.
resource "terraform_data" "registry_guard" {
  input = {
    institutions = length(local.managed_institutions)
    clients      = length(local.all_client_ids)
  }

  lifecycle {
    precondition {
      condition     = length(local.registry) > 0
      error_message = "Institution registry ${var.institutions_file} is empty or has no top-level `institutions:` key."
    }

    precondition {
      condition     = length(local.invalid_slugs) == 0
      error_message = "Invalid institution slug(s) ${jsonencode(local.invalid_slugs)}. ADR-0001 §5.1: lowercase [a-z0-9-] only, no dots/underscores/spaces."
    }

    precondition {
      condition     = length(local.duplicate_slugs) == 0
      error_message = "Duplicate institution slug(s) ${jsonencode(local.duplicate_slugs)} in the registry. A slug identifies exactly one institution."
    }

    precondition {
      condition     = length(local.ippis_collisions) == 0
      error_message = "IPPIS code(s) ${jsonencode(local.ippis_collisions)} map to more than one slug. The inbound normalizer (ADR-0001 §5.2) needs one target per code."
    }

    precondition {
      condition     = length(local.unknown_tiers) == 0
      error_message = "Delegation tier(s) ${jsonencode(local.unknown_tiers)} are not defined in ${var.delegated_templates_file}."
    }

    precondition {
      condition     = length(local.duplicate_client_ids) == 0
      error_message = "Duplicate client-id(s) ${jsonencode(local.duplicate_client_ids)}. Client-ids are realm-global."
    }

    precondition {
      condition     = length(local.slug_prefix_collisions) == 0
      error_message = "Slug prefix collision(s) ${jsonencode(local.slug_prefix_collisions)}. Client scoping is a glob ('<slug>-*'), so the shorter slug's admins would match the longer slug's clients. Choose a slug that is not an extension of an existing one."
    }
  }
}
