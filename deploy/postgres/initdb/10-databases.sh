#!/bin/sh
# ---------------------------------------------------------------------------
# One database and one login role per service, on the shared Postgres 16.
#
# WHEN THIS RUNS: only when Postgres initialises an EMPTY data directory (first `up`, or after
# `make clean`). On an existing volume it is skipped; create a missing database by hand, see
# docs/runbooks/create-sso-identity-db.md.
#
#   keycloak       role keycloak         KC_DB_PASSWORD             Keycloak's own schema
#   sso_identity   role sso_identity_rw  IDENTITY_DB_PASSWORD       wrapper IPPIS mirror (Flyway). NEVER Keycloak's DB.
#   audit          role audit            AUDIT_DB_PASSWORD          audit-service (Flyway)
#   notifications  role notification    NOTIFICATION_DB_PASSWORD   notification-service (Flyway)
#
# Each role OWNS its database (on Postgres 15+ the public schema belongs to pg_database_owner, so a
# mere grantee cannot create tables) and CONNECT is revoked from PUBLIC on every one of them, so no
# service role can open another service's database. The superuser (POSTGRES_USER) is unaffected.
# Passwords may also come from <NAME>_FILE (Docker secrets).
# ---------------------------------------------------------------------------
set -eu

secret() { # secret NAME DEFAULT
  eval "file=\${$1_FILE:-}"
  if [ -n "$file" ]; then cat "$file"; return; fi
  eval "val=\${$1:-}"
  if [ -n "$val" ]; then printf '%s' "$val"; else printf '%s' "$2"; fi
}

create() { # create DB ROLE PASSWORD
  db="$1"; role="$2"; pw="$3"
  echo "initdb: database $db owned by $role"
  psql -v ON_ERROR_STOP=1 -v db="$db" -v role="$role" -v pw="$pw" \
       --username "$POSTGRES_USER" --dbname postgres <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'pw')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'role')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'db') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'db', :'role') \gexec
SQL
}

create keycloak      keycloak         "$(secret KC_DB_PASSWORD keycloak-dev)"
create sso_identity  sso_identity_rw  "$(secret IDENTITY_DB_PASSWORD sso-identity-dev)"
create audit         audit            "$(secret AUDIT_DB_PASSWORD audit-dev)"
create notifications notification     "$(secret NOTIFICATION_DB_PASSWORD notification-dev)"

# The superuser's own database (POSTGRES_DB) stays for the Terraform pg state backend.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -c "REVOKE CONNECT ON DATABASE \"${POSTGRES_DB:-$POSTGRES_USER}\" FROM PUBLIC"
