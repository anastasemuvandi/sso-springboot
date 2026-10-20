variable "realm_id" {
  description = "The shared institution realm (ADR-0001). One realm for all institutions."
  type        = string
}

variable "slug" {
  description = <<-EOT
    The institution's canonical slug (ADR-0001 §5.1). Permanent: it is the group
    name, the `institution` claim value, the `inst:<slug>:*` role namespace and
    the client-id prefix all at once. Changing it destroys and recreates every
    object below, invalidating live tokens.
  EOT
  type        = string

  validation {
    condition     = length(regexall("^[a-z0-9][a-z0-9-]*$", var.slug)) > 0
    error_message = "slug must be lowercase [a-z0-9-] and start alphanumeric (ADR-0001 §5.1)."
  }
}

variable "display_name" {
  description = "Human-readable institution name. The only field a rename may change."
  type        = string
}

variable "templates" {
  description = <<-EOT
    The `templates` object from configs/delegated-permissions/templates.json,
    passed in whole. The module renders each assigned tier's `grant_role` and
    `provision.roles` for this slug, which is how the realm roles it creates
    stay identical to what the wrapper's DelegatedPermissionsService expects —
    rather than a second hard-coded list that silently drifts.
  EOT
  type        = any
}

variable "tiers" {
  description = "Which template names apply to this institution (assignments.by_institution, else assignments.default)."
  type        = list(string)
}

variable "departments" {
  description = "Optional subgroups under /<slug> (ADR-0001 §3). Departments are subgroups, never new slugs."
  type        = list(string)
  default     = []
}

variable "apps" {
  description = <<-EOT
    Client apps owned by this institution. Each becomes a client `<slug>-<name>`
    (ADR-0001 §5.3), its client roles, an `institution` claim mapper, and — when
    enforced — the `sso-access-groups` policy admitting this institution's group.
  EOT
  type = list(object({
    name                 = string
    display_name         = optional(string)
    roles                = optional(list(string), [])
    redirect_uris        = optional(list(string), [])
    web_origins          = optional(list(string), [])
    root_url             = optional(string)
    public               = optional(bool, false)
    standard_flow        = optional(bool, true)
    direct_access_grants = optional(bool, false)
    service_accounts     = optional(bool, false)
    enforce_access       = optional(bool, true)
  }))
  default = []
}

variable "extra_redirect_uris" {
  description = "Environment-specific redirect URIs appended to every client of this institution."
  type        = list(string)
  default     = []
}

variable "extra_web_origins" {
  description = "Environment-specific web origins appended to every client of this institution."
  type        = list(string)
  default     = []
}

variable "enable_client_access_policies" {
  description = "Create the `sso-access-groups` policy on each enforced client (required under ACCESS_DEFAULT_DENY)."
  type        = bool
  default     = true
}

variable "access_extend_children" {
  description = "Whether the access policy extends to department subgroups. False matches what the wrapper writes."
  type        = bool
  default     = false
}

variable "group_default_roles" {
  description = <<-EOT
    Short role names (rendered to `inst:<slug>:<name>`) granted to every member
    of the institution group by virtue of membership. Empty by default: an
    automatic grant is invisible on the user, and `admin` must never be handed
    out this way. Typically at most ["staff"].
  EOT
  type        = list(string)
  default     = []
}
