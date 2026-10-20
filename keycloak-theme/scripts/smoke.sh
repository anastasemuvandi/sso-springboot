#!/usr/bin/env bash
# Smoke test for the GoRSSO Keycloakify theme jar.
#
# Starts quay.io/keycloak/keycloak:26.2 in Docker with dist_keycloak/*.jar mounted
# in /opt/keycloak/providers, imports two throwaway realms (one per theme variant,
# internationalization en + rw), then curls each realm's login page and checks:
#   - the page is the Keycloakify page for the right theme name,
#   - GORSSO_ACCOUNT_SETUP_URL reached the page (kcContext.properties),
#   - the rw locale is offered,
#   - both theme names are registered for login AND email (admin serverinfo).
#
# Usage:  scripts/smoke.sh            (build the jar first: npm run build-keycloak-theme)
# Env:    KC_IMAGE (default quay.io/keycloak/keycloak:26.2), KC_PORT (default 18089)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$(ls "$HERE"/dist_keycloak/*.jar 2>/dev/null | head -1 || true)"
KC_IMAGE="${KC_IMAGE:-quay.io/keycloak/keycloak:26.2}"
KC_PORT="${KC_PORT:-18089}"
NAME="gorsso-theme-smoke-$$"
SETUP_URL="http://localhost:8000/account/setup-link"
BASE="http://localhost:${KC_PORT}"

[ -n "$JAR" ] || { echo "FAIL: no jar in $HERE/dist_keycloak — run npm run build-keycloak-theme" >&2; exit 1; }

WORK="$(mktemp -d)"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

realm_json() { # $1 realm name, $2 loginTheme
    cat <<JSON
{ "realm": "$1", "enabled": true, "loginTheme": "$2", "emailTheme": "$2",
  "internationalizationEnabled": true, "supportedLocales": ["en", "rw"], "defaultLocale": "en",
  "resetPasswordAllowed": true, "rememberMe": true,
  "bruteForceProtected": true, "failureFactor": 5, "permanentLockout": false,
  "maxFailureWaitSeconds": 900, "minimumQuickLoginWaitSeconds": 900, "waitIncrementSeconds": 900 }
JSON
}
mkdir -p "$WORK/import"
realm_json smoke-citizens government-citizens > "$WORK/import/smoke-citizens.json"
realm_json smoke-admin gor-admin > "$WORK/import/smoke-admin.json"
chmod -R a+rX "$WORK"

echo "Starting $KC_IMAGE with $(basename "$JAR") ..."
docker run -d --name "$NAME" -p "${KC_PORT}:8080" \
    -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
    -e GORSSO_ACCOUNT_SETUP_URL="$SETUP_URL" \
    -v "$JAR:/opt/keycloak/providers/gorsso-keycloak-theme.jar:ro" \
    -v "$WORK/import:/opt/keycloak/data/import:ro" \
    "$KC_IMAGE" start-dev --import-realm --spi-theme--cache-themes=false >/dev/null

for i in $(seq 1 90); do
    if curl -fsS -o /dev/null "$BASE/realms/smoke-admin/.well-known/openid-configuration" 2>/dev/null; then break; fi
    if [ "$i" = 90 ]; then echo "FAIL: Keycloak did not start"; docker logs --tail 80 "$NAME"; exit 1; fi
    sleep 2
done
echo "Keycloak is up."

fail=0
check() { # $1 description, $2 file, $3 fixed string
    if grep -qF -- "$3" "$2"; then echo "  ok   $1"; else echo "  FAIL $1 (missing: $3)"; fail=1; fi
}

for pair in "smoke-citizens:government-citizens" "smoke-admin:gor-admin"; do
    realm="${pair%%:*}"; theme="${pair##*:}"
    out="$WORK/$realm.html"
    redirect="$BASE/realms/$realm/account/"
    curl -fsS -o "$out" -G "$BASE/realms/$realm/protocol/openid-connect/auth" \
        --data-urlencode "client_id=account-console" \
        --data-urlencode "redirect_uri=$redirect" \
        --data-urlencode "response_type=code" --data-urlencode "scope=openid" \
        --data-urlencode "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" \
        --data-urlencode "code_challenge_method=S256" \
        --data-urlencode "ui_locales=rw"
    echo "[$realm -> $theme] $(wc -c < "$out") bytes"
    check "Keycloakify login page"          "$out" 'kcContext.pageId = "login.ftl"'
    check "themeName is $theme"             "$out" "kcContext.themeName = \"$theme\""
    check "GORSSO_ACCOUNT_SETUP_URL wired"  "$out" "$SETUP_URL"
    check "rw locale offered"               "$out" '"languageTag": "rw"'
    check "theme bundle served from jar"    "$out" "/resources/"
done

# Both theme names registered for login and email.
token="$(curl -fsS -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin \
    "$BASE/realms/master/protocol/openid-connect/token" | sed -E 's/.*"access_token":"([^"]+)".*/\1/')"
curl -fsS -H "Authorization: Bearer $token" "$BASE/admin/serverinfo" > "$WORK/serverinfo.json"
for type in login email; do
    names="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(" ".join(t["name"] for t in d["themes"][sys.argv[2]]))' "$WORK/serverinfo.json" "$type")"
    for theme in government-citizens gor-admin; do
        case " $names " in *" $theme "*) echo "  ok   $type theme $theme registered";;
                           *) echo "  FAIL $type theme $theme not registered"; fail=1;; esac
    done
done

if [ "$fail" = 0 ]; then echo "SMOKE PASSED"; else echo "SMOKE FAILED"; docker logs --tail 40 "$NAME"; fi
exit "$fail"
