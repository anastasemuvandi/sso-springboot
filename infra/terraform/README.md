# Onboarding-as-code

Terraform for the GoRSSO institution model. Onboarding an institution is a
registry edit and an apply — see
[`docs/runbooks/onboard-institution.md`](../../docs/runbooks/onboard-institution.md)
for the procedure. This file covers how the code is put together and where its
boundaries are.

## Layout

```
infra/terraform/
├── versions.tf          provider pin + pg state backend
├── providers.tf         Keycloak provider (all settings explicit — see below)
├── variables.tf         connection, environment, toggles
├── locals.tf            reads the registry + templates; validates both
├── base.tf              the realm every institution is onboarded into
├── institutions.tf      one module instance per active registry entry
├── outputs.tf
├── envs/                per-environment tfvars + backend config (no secrets)
├── scripts/
│   └── import-existing.sh   adopt objects that already exist
└── modules/institution/     the parameterised onboarding module
```

## What drives it

Two files outside this directory are the source of truth. Terraform reads both
and derives everything else:

| File | Answers |
|---|---|
| `configs/institutions.yaml` | **Which** institutions exist (ADR-0001 §5.4) |
| `configs/delegated-permissions/templates.json` | **What** their local admins may do (ADR-0001 §4) |

The second one matters more than it looks. The `inst:<slug>:*` realm roles
Terraform creates are *rendered from the same templates the wrapper enforces at
request time* — the tier's `grant_role` plus its `provision.roles`, exactly what
`PermissionTemplate.rolesToProvision()` (wrapper `policy.delegated`) computes. If the roles were
hard-coded here instead, they would be a second copy of the policy, free to
drift from the one that is actually enforced. Adding a tier to `templates.json`
therefore creates its roles on the next apply, with no Terraform change at all.

## Usage

Credentials are never in a tfvars file. Pass them as environment variables:

```bash
export TF_VAR_keycloak_url=http://10.10.73.8:8080
export TF_VAR_keycloak_admin_username=admin
export TF_VAR_keycloak_admin_password=...
export PG_CONN_STR='postgres://user:password@10.10.73.8:5432/sso_risa?sslmode=disable'

make tf-init  TF_ENV=dev
make tf-plan  TF_ENV=dev
make tf-apply TF_ENV=dev
```

Or directly:

```bash
terraform init -backend-config=envs/dev.backend.hcl -backend-config="conn_str=$PG_CONN_STR"
terraform plan -var-file=envs/dev.tfvars
```

Offline checks, needing no Keycloak at all:

```bash
make registry-validate    # the registry's own rules
make tf-validate          # fmt + HCL validity
```

## State

`backend "pg"`, in the Postgres that already backs Keycloak. Not a cloud
backend: the deployment target is an on-prem HTTP-only box with no cloud
account, and local state cannot be shared between a laptop and the CI runner.
The pg backend gives locking and a shared store with no new infrastructure.

Dev and staging use separate schemas (`terraform_sso_dev`,
`terraform_sso_staging`). `conn_str` is passed at init time so the password is
never committed.

**The state contains client secrets.** Treat that database as a credential store
— restrict access to it and back it up with the rest of the stack.

## What is *not* managed here, and why

Not everything belongs in IaC. These are deliberate exclusions, not gaps:

| Excluded | Why |
|---|---|
| `sso-client`, `sso-admin-ui` | The wrapper self-heals its service account's realm-management roles on every start (`reconcile_service_account_roles`). Terraform would fight the reconciler, and managing the secret would rotate it out from under a running service. |
| Users, and role **assignments** to people | Appointing a local admin is the moment delegated power changes hands. It stays a separate, individually audited act — see the runbook §7.2. |
| Access-policy **membership** | Day-to-day assignment happens through `/authorization`. Terraform owns the policy's *existence* (`ignore_changes = [groups]`); reverting an operational grant on the next apply would be worse than the drift. |
| `government-citizens`, `government-admin` | Tier realms with no institutions in them; still provisioned from `configs/realms/*.json`. |
| Themes, authentication flows, identity providers | Not part of institution onboarding. |

## Notes on the provider configuration

**Every provider setting is passed explicitly.** This is not style. The repo's
`.env` sets `KEYCLOAK_CLIENT_ID=sso-client` and
`KEYCLOAK_REALM=government-internal` for the Spring Boot wrapper — and the Keycloak
provider reads those *exact* variable names as its own fallbacks. A shell that
has sourced `.env` would otherwise silently authenticate Terraform as the
wrapper's service account against the wrong realm. Binding each argument to a TF
variable makes ambient `KEYCLOAK_*` values inert.

`initial_login = false` keeps the provider from authenticating while it is being
configured, so `terraform validate` works on a machine that cannot reach
Keycloak — which is what lets CI gate a PR from a shared GitLab runner.

## Safety rails

- `prevent_destroy` on the realm and on every institution group.
- Registry preconditions (`terraform_data.registry_guard`) fail the plan on a
  bad slug, a duplicate, an IPPIS-code collision, an unknown tier, a client-id
  collision, or a **slug that extends another slug** — before anything is
  written. That last one is an isolation rule: client scoping globs on
  `<slug>-*` and `-` is legal inside a slug, so `local` would match
  `local-govt-portal`. Role scoping (`inst:<slug>:`) and member scoping
  (`/<slug>/`) cannot collide this way because their separators are illegal in
  a slug. the wrapper isolation suite (`rw.gov.risa.sso.wrapper.isolation.*IsolationTest`) holds this coupling in
  place.
- CI refuses any plan containing a destroy.
- Post-apply, CI runs the guardrail audit and fails on any finding.

## Adopting existing objects

Terraform creating something Keycloak already has fails with a 409. Import it
rather than deleting it:

```bash
./scripts/import-existing.sh --institution minagri            # prints commands
./scripts/import-existing.sh --institution minagri --execute
terraform plan -var-file=envs/dev.tfvars                      # empty plan = clean
```

See the runbook §8, especially the note on UPPERCASE legacy group names.
