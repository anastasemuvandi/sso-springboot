# Runbook: onboard a new institution

| | |
|---|---|
| Applies to | realm `government-internal` (all institutions share it, ADR-0001) |
| Automation | `infra/terraform` (production path), `.gitlab-ci.yml` (`config-safety`, `terraform-validate`, `terraform-plan`, `terraform-apply`, `delegated-check`) |
| Dev shortcut | `make seed` (tools/seeder) for `minagri` / `minisante` on a local stack only |
| Duration | about 20 minutes of work, about 1 hour with review and verification |

## 0. What onboarding is

Onboarding is two file edits and a merge. Terraform reads both files and creates everything in Keycloak.

```
configs/institutions.yaml                      WHICH institutions exist
configs/delegated-permissions/templates.json   WHAT their local admins may do
configs/generated/fgap-v2-plan.json            FGAP v2 projection of the two (generated, committed)
        |
        +-- infra/terraform creates, per active entry:
              /<slug>, /<slug>/<department>       groups
              inst:<slug>:{admin,hr,staff,auditor} realm roles, rendered from the templates
              <slug>-<app>                         clients, client roles
                +-- institution-attribute          `institution` claim mapper
                +-- sso-access-groups              access policy (ACCESS_DEFAULT_DENY)
              FGAP v2 role policies + permissions  Keycloak-side delegated boundary
```

Do not use the Keycloak admin console. A console-made group, role or grant is invisible to Terraform and to review, and `make delegated-check` reports it as unmanaged.

The slug is permanent. It is written into tokens, group paths, role names and client ids. There is no rename path.

## 1. Prerequisites

- Write access to the repository and the ability to open a merge request.
- For a manual apply: Terraform 1.5 or later (or `docker run hashicorp/terraform:1.9`), network access to Keycloak, the master-realm admin credentials, and the Postgres connection string for the state backend.
- A named institution, a named prospective local admin, and at least one app or a stated intent to add one.

Check whether the institution already exists (an older UPPERCASE group such as `MINAGRI` is the same institution under a non-canonical name; go to section 8):

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$KC_URL/admin/realms/government-internal/groups?search=minagri" | jq '.[].name'
```

## 2. Choose the slug

Rules, enforced by `make registry-validate` and by a Terraform precondition:

1. Lowercase `[a-z0-9-]` only.
2. Prefer the established acronym (`minagri`, `rdb`, `rra`).
3. One slug per legal institution. A department is a subgroup.
4. Permanent. A rename changes `display_name` only.
5. A slug may not extend an existing slug (`local` taken means `local-govt` is refused). Client scoping is a glob on `<slug>-*`, and `-` is legal inside a slug, so `local-govt-portal` would match `local-*`.

Also collect the display name, every IPPIS code that maps to the slug (a code maps to one slug only), optional departments, and optional apps with their roles.

## 3. Edit the registry

```yaml
  - slug: rura
    display_name: Rwanda Utilities Regulatory Authority
    ippis_codes: [RURA]
    status: active
    departments: [licensing, inspection]
    apps:
      - name: licensing-portal          # client id becomes rura-licensing-portal
        display_name: RURA Licensing Portal
        roles: [viewer, officer, approver]
```

Leave out redirect URIs that differ per environment. Put them in `infra/terraform/envs/<env>.tfvars` under `extra_redirect_uris`. Never put `localhost` in the registry.

Delegation tiers default to `assignments.default` in `templates.json` (`institution-admin`, `institution-hr`, `institution-auditor`). To withhold a tier, list the institution with a shorter list:

```json
"by_institution": { "rura": ["institution-admin", "institution-hr"] }
```

## 4. Regenerate the FGAP plan and validate (offline)

```bash
make fgap-plan              # rewrites configs/generated/fgap-v2-plan.json; commit it
make registry-validate      # slugs, IPPIS collisions, client ids, tiers
make delegated-validate     # the templates are safe (guardrail)
make fgap-plan-check        # the committed plan matches the two files
make tf-validate            # terraform fmt -check + validate, no Keycloak needed
```

These wrap `java -jar sso-wrapper/target/sso-wrapper-*.jar <subcommand>` (build the wrapper first with `make build`). They are the same checks CI runs in `config-safety`, `fgap-plan-check` and `terraform-validate`. A stale plan is the dangerous state: it applies cleanly and leaves the new institution's local admins with no Keycloak-side permissions. Terraform also refuses to plan when the plan and the registry name different institutions.

Optional plan preview, if you can reach dev:

```bash
export TF_VAR_keycloak_url=http://<dev-host>:8080
export TF_VAR_keycloak_admin_username=admin
export TF_VAR_keycloak_admin_password=...
export PG_CONN_STR='postgres://user:password@<dev-host>:5432/<db>?sslmode=disable'
make tf-plan TF_ENV=dev     # runs init with envs/dev.backend.hcl first
```

## 5. Open the merge request and read the plan

The `terraform-plan` job (dev runner) attaches the plan. For a new institution it should be almost all `+ create`:

- one group plus one per department
- the `inst:<slug>:*` roles
- per app: a client, its roles, the `institution-attribute` mapper, the `sso-access-groups` policy
- FGAP v2 role policies and permissions for the slug

Stop and think when you see:

| In the plan | Meaning |
|---|---|
| any destroy | The job already failed. Usually a renamed slug or a deleted registry entry (use `status: decommissioned`). |
| update on another institution | You touched shared config (`templates.json` or a tfvars file). |
| update on `keycloak_realm` | The live realm drifted from `base.tf`. Resolve before applying. The MFA mode is never in the plan (`ignore_changes`). |
| fewer roles than tiers | The institution is in `by_institution` with a shorter list. Intended? |

Get a second reviewer.

## 6. Apply

Merging to `main` runs `terraform-apply` on the dev runner, then `delegated-check`, which fails the pipeline if any institution admin holds a broad admin role, a withheld tier was granted by hand, or an expected role is missing.

Manual apply (for example staging):

```bash
make tf-plan  TF_ENV=staging
make tf-apply TF_ENV=staging
make delegated-check
```

## 7. Verify and hand over

### 7.1 What exists

```bash
cd infra/terraform && terraform output -json institutions | jq '.rura'
```

### 7.2 Appoint the local admin

Terraform creates the role, never the person. Assign `inst:<slug>:admin` to a named user through the platform API (audited):

```bash
curl -X POST "$SSO/roles/users/$USER_ID/realm-roles" \
  -H "Authorization: Bearer $PLATFORM_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"user_id\":\"$USER_ID\",\"roles\":[{\"name\":\"inst:rura:admin\"}]}"
make delegated-check   # must exit 0
```

That account must hold `inst:<slug>:admin` and no platform tier role (`super_admin`, `admin`, `system_admin`) and no `realm-management` role. Its `institution` attribute must equal the slug (IPPIS normally writes it).

### 7.3 Smoke test the boundary

As the new local admin:

- `GET /institution-admin/users` returns only that institution's users.
- Asking for another institution returns 403, not an empty list.
- Assigning `inst:<slug>:staff` works; `inst:<slug>:admin` is refused (`roles.deny`).
- A member of `/<slug>` can log in to `<slug>-<app>`; a non-member cannot.

`make verify` runs the Keycloak-direct acceptance (9 cases, no wrapper in the path) as a seeded institution admin.

### 7.4 Client secrets

```bash
terraform output -json client_secrets | jq '."rura"'
```

Send client id, secret, realm and issuer over the normal secret channel, never in a ticket.

### 7.5 IPPIS

Nothing more to edit: the `ippis_codes` you wrote are the lookup table. Run a full reconcile and read the `unmapped_institution` count (see `ippis-sync.md`):

```bash
java -jar sso-wrapper/target/sso-wrapper-*.jar ippis-reconcile --full
```

## 8. Adopting a pre-existing institution

Objects created by hand or by the dev seeder already exist. Creating them again fails with 409; deleting them revokes live access. Import them:

```bash
cd infra/terraform
export KC_URL=http://<host>:8080 KC_USER=admin KC_PASS=...
./scripts/import-existing.sh --institution minagri            # prints commands
./scripts/import-existing.sh --institution minagri --execute
terraform plan -var-file=envs/dev.tfvars                      # expect an empty plan
```

UPPERCASE legacy groups (`MINAGRI`) are not `minagri`. Do not import one into the lowercase resource; Terraform would rename it and change every policy that references its path. Migrate members to the lowercase group in a separate planned change, and normalise the `institution` attribute as you go.

## 9. Changing an institution later

| Change | How |
|---|---|
| Rename | `display_name` only |
| Add a department / app / client role | Edit the registry, `make fgap-plan`, merge |
| Environment-specific redirect URI | `envs/<env>.tfvars` |
| What local admins may do | `templates.json` (affects every institution on that tier), `make fgap-plan` |
| Withhold a tier | `assignments.by_institution`; revoke existing holders explicitly |
| Suspend | `status: suspended`, apply |

## 10. Offboarding

Never delete the registry entry: Terraform would destroy the group, roles and clients. Instead:

1. Set `status: decommissioned` and keep the entry forever (the slug can never be reissued).
2. Apply. Terraform stops managing the objects; it does not delete them.
3. Revoke access in separate reviewed steps: remove grant roles, disable clients, disable or reassign users.
4. Delete the Keycloak objects by hand only after the retention period, with sign-off.

## 11. Troubleshooting

| Symptom | Fix |
|---|---|
| 409 on apply | Import (section 8) |
| `Slug prefix collision` | Choose a distinct slug |
| `IPPIS code(s) ... map to more than one slug` | One code, one slug |
| `... missing from configs/generated/fgap-v2-plan.json` | `make fgap-plan`, commit |
| New client rejects every login | `ACCESS_DEFAULT_DENY` with no policy. Check `enforce_access` and that the app is not public; `GET /authorization/clients/<clientId>/access` |
| Local admin gets 403 everywhere | Missing `institution` attribute, or a claim mismatch; check the user and the mapper |
| `/institution-admin/*` returns 503 | Templates missing or unsafe; `make delegated-validate` |
| `delegated-check` reports unmanaged grants | Someone used the console. Revoke, then make the change in config |
| Backend lock timeout | Another apply is running. `terraform force-unlock` only after confirming it is not |

## Appendix: CI runner setup

`terraform-plan`, `terraform-apply` and `delegated-check` need a self-hosted runner that can reach the dev Keycloak. Register one with the Docker executor and `--docker-network-mode host`, tag it (for example `sso-dev`), and set the project variable `TF_DEV_RUNNER=sso-dev`. Without it those jobs are not created and the offline gates protect `main`.

Environment-scoped (`dev`), masked and protected variables: `KEYCLOAK_URL`, `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_CLIENT_SECRET` (sso-client, for `delegated-check`), `PG_CONN_STR`. Terraform state lives in that Postgres (schema `terraform_sso_dev`) and contains client secrets: back it up as a credential store.

## Adding a department later

Keycloak 26.2 FGAP v2 group permissions do not reach a group's children, so the applied plan lists
each department explicitly. A department created after the last apply is invisible to that
institution's admins at the Keycloak layer (the wrapper skips it with a WARN naming the department,
so their user list keeps working but omits its members). After creating a department, re-apply:
`make tf-apply TF_ENV=<env>` in deployed environments, or
`java -jar sso-wrapper.jar fgap-apply` for offline/dev realms.
