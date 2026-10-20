#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# End-to-end API smoke against a running, seeded compose stack (`make smoke` runs it, then the
# browser check console-login.mjs). Needs curl, jq, docker. Ports follow HOST_PORT_* like compose.
#
#   PROJECT=gorsso HOST_PORT_WRAPPER=18000 ... deploy/smoke/api-smoke.sh
#   SMOKE_SKIP_BRUTE_FORCE=1 skips the notification check (it waits for a detector cycle).
# ---------------------------------------------------------------------------
set -uo pipefail

PROJECT="${PROJECT:-gorsso}"
KC="http://localhost:${HOST_PORT_KEYCLOAK:-8080}"
W="http://localhost:${HOST_PORT_WRAPPER:-8000}"
AUDIT="http://localhost:${HOST_PORT_AUDIT:-8001}"
NOTIF="http://localhost:${HOST_PORT_NOTIFICATION:-8002}"
KC_ADMIN="${KC_ADMIN_USERNAME:-admin}"; KC_PASS="${KC_ADMIN_PASSWORD:-admin}"
AUDIT_KEY="${AUDIT_API_KEY:-gorsso-dev-audit-key}"
NOTIF_KEY="${NOTIFICATION_API_KEY:-gorsso-dev-notification-key}"
PW="${SMOKE_PASSWORD:-GorSsoDev#2026}"
REALM=government-internal
WRAPPER_CONTAINER="${WRAPPER_CONTAINER:-${PROJECT}-wrapper-1}"

fails=0
pass() { echo "  PASS $*"; }
fail() { echo "  FAIL $*"; fails=$((fails + 1)); }
step() { echo; echo "== $*"; }

# HTTP helper: sets $code and $body.
req() { local out; out="$(curl -s -w $'\n%{http_code}' "$@")"; code="${out##*$'\n'}"; body="${out%$'\n'*}"; }

step "wrapper health and discovery"
for i in $(seq 1 60); do req "$W/health/ready"; [ "$code" = 200 ] && break; sleep 3; done
[ "$code" = 200 ] && pass "/health/ready 200" || fail "/health/ready $code $body"
req "$W/oauth2/.well-known/openid-configuration"
if [ "$code" = 200 ] && jq -e '.issuer and .authorization_endpoint and .token_endpoint' <<<"$body" >/dev/null; then
  pass "discovery 200 (issuer $(jq -r .issuer <<<"$body"))"
else fail "discovery $code $body"; fi

step "client secrets (Keycloak Admin API)"
ADM="$(curl -s -d grant_type=password -d client_id=admin-cli -d "username=$KC_ADMIN" --data-urlencode "password=$KC_PASS" \
  "$KC/realms/master/protocol/openid-connect/token" | jq -r .access_token)"
secret() {
  local id; id="$(curl -s -H "Authorization: Bearer $ADM" "$KC/admin/realms/$REALM/clients?clientId=$1" | jq -r '.[0].id')"
  curl -s -H "Authorization: Bearer $ADM" "$KC/admin/realms/$REALM/clients/$id/client-secret" | jq -r .value
}
KUBAKA_SECRET="$(secret minagri-kubaka-web)"; HMIS_SECRET="$(secret minisante-hmis)"
[ -n "$KUBAKA_SECRET" ] && [ "$KUBAKA_SECRET" != null ] && pass "secrets read" || fail "could not read client secrets"

login() { # login USER CLIENT SECRET [PASSWORD]
  req -X POST "$W/auth/login" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg u "$1" --arg c "$2" --arg s "$3" --arg p "${4:-$PW}" '{username:$u,password:$p,client_id:$c,client_secret:$s}')"
}

step "password login through the gates (POST /auth/login)"
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
login minagri.staff minagri-kubaka-web "$KUBAKA_SECRET"
[ "$code" = 200 ] && jq -e .access_token <<<"$body" >/dev/null && pass "minagri.staff -> minagri-kubaka-web 200" \
  || fail "minagri.staff -> minagri-kubaka-web $code $body"
login minagri.staff minisante-hmis "$HMIS_SECRET"
if [ "$code" = 403 ] && [ "$(jq -r .error <<<"$body")" = access_denied ]; then pass "minagri.staff -> minisante-hmis 403 access_denied"
else fail "minagri.staff -> minisante-hmis expected 403 access_denied, got $code $body"; fi

step "audit trail received the login events"
sleep 3
req -H "X-Audit-API-Key: $AUDIT_KEY" "$AUDIT/audit/events?user_id=minagri.staff&from_date=$T0&limit=50"
types="$(jq -r '[.items[].event_type] | unique | join(",")' <<<"$body" 2>/dev/null)"
echo "    event types for minagri.staff since $T0: ${types:-none}"
grep -q "auth.login.success" <<<"$types" && pass "success event audited" || fail "no auth.login.success event ($code)"
grep -qE "authz.access.denied|login_denied|denied" <<<"$types" && pass "denial event audited" || fail "no access-denied event"

step "institution admin (GET /institution-admin/me, GET /employees)"
login minagri.admin minagri-kubaka-web "$KUBAKA_SECRET"
ADMIN_TOKEN="$(jq -r .access_token <<<"$body")"
[ "$code" = 200 ] && pass "minagri.admin login 200" || fail "minagri.admin login $code $body"
req -H "Authorization: Bearer $ADMIN_TOKEN" "$W/institution-admin/me"
[ "$code" = 200 ] && [ "$(jq -r '.institution // .institution_slug // empty' <<<"$body")" = minagri ] \
  && pass "/institution-admin/me -> minagri" || fail "/institution-admin/me $code $body"
req -H "Authorization: Bearer $ADMIN_TOKEN" "$W/employees?limit=200"
if [ "$code" = 200 ]; then
  others="$(jq -r '[(.items // .employees // .rows // [])[] | (.institution // .institution_slug // .institutionSlug // empty)] | map(select(. != "minagri")) | unique | join(",")' <<<"$body")"
  n="$(jq -r '(.items // .employees // .rows // []) | length' <<<"$body")"
  [ -z "$others" ] && pass "/employees scoped: $n row(s), all minagri" || fail "/employees leaked institutions: $others"
else fail "/employees $code $(head -c 300 <<<"$body")"; fi

step "ops commands inside the wrapper container"
for cmd in "delegated-check" "fgap-plan-check" "verify --institution minagri"; do
  out="$(docker exec "$WRAPPER_CONTAINER" java -jar /app/app.jar $cmd 2>&1)"; rc=$?
  echo "$out" | grep -vE '^\{"@timestamp' | tail -4 | sed 's/^/    /'
  [ $rc -eq 0 ] && pass "$cmd (exit 0)" || fail "$cmd (exit $rc)"
done

if [ -z "${SMOKE_SKIP_BRUTE_FORCE:-}" ]; then
  step "brute force -> notification-service alert"
  T1="$(date -u +%Y-%m-%dT%H:%M:%S)"
  for i in 1 2 3 4 5 6; do login "${SMOKE_BRUTE_USER:-minagri.planner}" minagri-kubaka-web "$KUBAKA_SECRET" "Wrong-Passw0rd-$i"; echo -n " $code"; done; echo
  found=""
  for i in $(seq 1 30); do
    req -H "X-Notification-API-Key: $NOTIF_KEY" "$NOTIF/alerts?limit=50"
    if jq -e --arg t "$T1" '[(if type == "array" then . else (.items // .alerts // []) end)[] | select((.rule_name // .rule // .alert_type // "") | test("brute")) | select((.created_at // "") >= $t)] | length > 0' <<<"$body" >/dev/null 2>&1; then
      found=1; break; fi
    sleep 5
  done
  [ -n "$found" ] && pass "brute_force alert raised" || fail "no brute_force alert after ~150 s ($code $(head -c 300 <<<"$body"))"
fi

echo
[ $fails -eq 0 ] && echo "api smoke: all checks passed" || echo "api smoke: $fails check(s) FAILED"
exit $((fails > 0))
