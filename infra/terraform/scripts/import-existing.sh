#!/usr/bin/env bash
#
# Adopt Keycloak objects that already exist into Terraform state.
#
# WHY THIS EXISTS
#   Terraform creating an object that Keycloak already has fails with a 409, and
#   the obvious "fix" — deleting it in the console so the apply succeeds — would
#   revoke live access. Importing is the correct move: it makes Terraform the
#   owner of what is already there, without touching it.
#
# WHAT IT DOES
#   Reads configs/institutions.yaml, asks Keycloak which of those objects exist,
#   and PRINTS the matching `terraform import` commands. It writes nothing
#   unless you pass --execute. Review the output first: an import against the
#   wrong address silently binds Terraform to the wrong object.
#
# AFTER IMPORTING
#   Always run a plan. Import records the object's CURRENT state, so the plan
#   shows exactly how the live object differs from this code. An empty plan
#   means the adoption is clean. A plan proposing changes is real drift — decide
#   deliberately whether the code or the live object is right before applying.
#
# USAGE
#   export KC_URL=http://10.10.73.8:8080
#   export KC_USER=admin KC_PASS=...
#   ./scripts/import-existing.sh                 # print commands
#   ./scripts/import-existing.sh --execute       # run them
#   ./scripts/import-existing.sh --institution minagri
#
# REQUIRES curl, jq, terraform (it also reads the YAML registry, with `terraform console`).

set -euo pipefail

TF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${TF_DIR}/../.." && pwd)"

KC_URL="${KC_URL:-http://localhost:8080}"
KC_USER="${KC_USER:-admin}"
KC_PASS="${KC_PASS:-admin}"
KC_REALM="${KC_REALM:-government-internal}"
REGISTRY="${REGISTRY:-${REPO_ROOT}/configs/institutions.yaml}"

EXECUTE=0
ONLY_INSTITUTIONS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --execute)      EXECUTE=1; shift ;;
    --institution)  ONLY_INSTITUTIONS+=("$2"); shift 2 ;;
    --realm)        KC_REALM="$2"; shift 2 ;;
    -h|--help)      sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

for bin in curl jq terraform; do
  command -v "$bin" >/dev/null || { echo "ERROR: $bin is required" >&2; exit 1; }
done

# ---------------------------------------------------------------------------
# auth
# ---------------------------------------------------------------------------
TOKEN="$(curl -sS --fail-with-body \
  -d "client_id=admin-cli" -d "username=${KC_USER}" -d "password=${KC_PASS}" \
  -d "grant_type=password" \
  "${KC_URL}/realms/master/protocol/openid-connect/token" | jq -r .access_token)"

[[ -n "${TOKEN}" && "${TOKEN}" != "null" ]] || { echo "ERROR: could not obtain admin token from ${KC_URL}" >&2; exit 1; }

kc() { curl -sS --fail-with-body -H "Authorization: Bearer ${TOKEN}" "${KC_URL}/admin/realms/${KC_REALM}$1"; }

emit() {
  local address="$1" id="$2"
  if [[ "${EXECUTE}" -eq 1 ]]; then
    echo "==> terraform import '${address}' '${id}'"
    (cd "${TF_DIR}" && terraform import "${address}" "${id}")
  else
    printf "terraform import '%s' '%s'\n" "${address}" "${id}"
  fi
}

# ---------------------------------------------------------------------------
# which institutions
# ---------------------------------------------------------------------------
if [[ ${#ONLY_INSTITUTIONS[@]} -gt 0 ]]; then
  SLUGS=("${ONLY_INSTITUTIONS[@]}")
else
  # Same filter as locals.tf (managed_institutions). `terraform console` in an empty directory needs
  # no init, no providers and no variables: it is used here only as a YAML reader.
  EMPTY_DIR="$(mktemp -d)"
  mapfile -t SLUGS < <(cd "${EMPTY_DIR}" && echo "join(\"\\n\", [for i in try(yamldecode(file(\"${REGISTRY}\")).institutions, []) : i.slug if try(i.status, \"active\") == \"active\" && !try(i.separate_realm, false)])" \
    | terraform console | sed -e 's/^<<EOT$//' -e 's/^EOT$//' -e 's/^"//' -e 's/"$//' | grep -v '^$')
  rmdir "${EMPTY_DIR}"
fi

echo "# Realm: ${KC_REALM}   Keycloak: ${KC_URL}"
echo "# Institutions: ${SLUGS[*]}"
[[ "${EXECUTE}" -eq 1 ]] || echo "# (dry run — pass --execute to run these)"
echo

# ---------------------------------------------------------------------------
# realm-level objects
# ---------------------------------------------------------------------------
emit "keycloak_realm.internal[0]" "${KC_REALM}"
emit "keycloak_realm_events.internal[0]" "${KC_REALM}"

REALM_ROLES_JSON="$(kc "/roles?briefRepresentation=true&max=2000")"

for role in super_admin admin employee contractor gov-admin auditor citizen; do
  rid="$(jq -r --arg n "$role" '.[] | select(.name==$n) | .id' <<<"${REALM_ROLES_JSON}")"
  [[ -n "$rid" ]] && emit "keycloak_role.platform[\"${role}\"]" "${KC_REALM}/${rid}"
done

# ---------------------------------------------------------------------------
# per institution
# ---------------------------------------------------------------------------
GROUPS_JSON="$(kc "/groups?max=2000")"
CLIENTS_JSON="$(kc "/clients?max=2000")"

for slug in "${SLUGS[@]}"; do
  echo "# ---- ${slug} ----"

  gid="$(jq -r --arg n "$slug" '.[] | select(.name==$n) | .id' <<<"${GROUPS_JSON}")"
  if [[ -n "$gid" ]]; then
    emit "module.institution[\"${slug}\"].keycloak_group.institution" "${KC_REALM}/${gid}"

    # departments — subgroups of the institution group
    kc "/groups/${gid}/children?max=200" 2>/dev/null \
      | jq -r '.[] | "\(.name)\t\(.id)"' \
      | while IFS=$'\t' read -r dname did; do
          [[ -n "$dname" ]] && emit "module.institution[\"${slug}\"].keycloak_group.department[\"${dname}\"]" "${KC_REALM}/${did}"
        done
  else
    echo "# no group /${slug} — Terraform will create it"
  fi

  # inst:<slug>:* realm roles
  jq -r --arg p "inst:${slug}:" '.[] | select(.name | startswith($p)) | "\(.name)\t\(.id)"' <<<"${REALM_ROLES_JSON}" \
    | while IFS=$'\t' read -r rname rid; do
        [[ -n "$rname" ]] && emit "module.institution[\"${slug}\"].keycloak_role.institution[\"${rname}\"]" "${KC_REALM}/${rid}"
      done

  # <slug>-* clients, their roles, the institution mapper, the access policy
  jq -r --arg p "${slug}-" '.[] | select(.clientId | startswith($p)) | "\(.clientId)\t\(.id)"' <<<"${CLIENTS_JSON}" \
    | while IFS=$'\t' read -r cid cuuid; do
        [[ -z "$cid" ]] && continue
        emit "module.institution[\"${slug}\"].keycloak_openid_client.app[\"${cid}\"]" "${KC_REALM}/${cuuid}"

        kc "/clients/${cuuid}/roles" 2>/dev/null \
          | jq -r '.[] | "\(.name)\t\(.id)"' \
          | while IFS=$'\t' read -r crname crid; do
              [[ -n "$crname" ]] && emit "module.institution[\"${slug}\"].keycloak_role.app[\"${cid}/${crname}\"]" "${KC_REALM}/${crid}"
            done

        mid="$(kc "/clients/${cuuid}/protocol-mappers/models" 2>/dev/null \
               | jq -r '.[] | select(.name=="institution-attribute") | .id')"
        [[ -n "$mid" ]] && emit "module.institution[\"${slug}\"].keycloak_openid_user_attribute_protocol_mapper.institution[\"${cid}\"]" \
                                "${KC_REALM}/client/${cuuid}/${mid}"

        pid="$(kc "/clients/${cuuid}/authz/resource-server/policy?max=200" 2>/dev/null \
               | jq -r '.[]? | select(.name=="sso-access-groups") | .id')"
        [[ -n "$pid" ]] && emit "module.institution[\"${slug}\"].keycloak_openid_client_group_policy.access[\"${cid}\"]" \
                                "${KC_REALM}/${cuuid}/${pid}"
      done
  echo
done

echo "# Done. Now run a plan — an empty plan means the adoption is clean:"
echo "#   terraform plan -var-file=envs/<env>.tfvars"
