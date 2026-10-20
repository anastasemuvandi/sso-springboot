# Runbook — Create the `sso_identity` database (IPPIS mirror)

| | |
|---|---|
| **Applies to** | `sso-wrapper` identity mirror (`identity/*`, Flyway `classpath:db/identity`) |
| **Typical duration** | 10 minutes |

The mirror keeps what IPPIS says about every employee — including those with **no** account — in
its own database, `sso_identity`, on the existing Postgres. It is **optional**: with
`IDENTITY_DATABASE_URL` unset the wrapper runs, the reconcile syncs straight from IPPIS, and
`GET /employees` answers 503 `identity_db_not_configured`.

> **Scope.** A read-only mirror of *what IPPIS says*. Nothing in it decides who can log in or what
> roles anyone holds — that stays Keycloak's, through the Admin API.

## 1. Why a separate database (never Keycloak's)
1. Keycloak's database is rebuilt/migrated by Keycloak; ours would be collateral.
2. It is in the login path (persistent sessions); a bulk mirror refresh must not contend with logins.
3. Credentials: Keycloak's owner role can write `user_entity` directly, bypassing every wrapper gate.
   A separate role cannot reach those tables.
4. Restore coupling: restoring Keycloak must not silently roll the mirror back.

There is deliberately **no default URL**: unset means "no mirror", never "the database next door".

## 2. Create it

```bash
IDENTITY_PW="$(openssl rand -hex 24)"   # hex: safe inside a URL
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d postgres <<SQL
CREATE ROLE sso_identity_rw LOGIN PASSWORD '${IDENTITY_PW}';
CREATE DATABASE sso_identity OWNER sso_identity_rw;
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;   -- substitute Keycloak's database name
SQL
```

Owning the database matters on Postgres 15+: `public` is owned by `pg_database_owner`, so the owner
role is what lets Flyway create tables.

### Confirm the isolation (do not skip)
```bash
docker compose exec -T postgres env PGPASSWORD="$IDENTITY_PW" psql -U sso_identity_rw -d sso_identity -c '\conninfo'   # works
docker compose exec -T postgres env PGPASSWORD="$IDENTITY_PW" psql -U sso_identity_rw -d keycloak -c 'select 1'       # MUST fail: permission denied
```

## 3. Wire the wrapper

```bash
IDENTITY_DATABASE_URL=jdbc:postgresql://postgres:5432/sso_identity
IDENTITY_DATABASE_USER=sso_identity_rw
IDENTITY_DATABASE_PASSWORD_FILE=/run/secrets/identity_db_password   # or IDENTITY_DATABASE_PASSWORD
```

The reference form `postgresql[+asyncpg]://user:pw@host:5432/sso_identity` is also accepted (user and
password taken from it unless the separate variables are set). A URL with an empty password (`:@`)
is refused at startup with a message naming this runbook.

`IDENTITY_DB_POOL_SIZE` (10) sizes the Hikari pool. The pool is lazy: a mirror that is down does not
stop the wrapper from booting or serving logins.

## 4. Migrations (Flyway)
`src/main/resources/db/identity/V1__ippis_identity.sql` (history table `flyway_identity_history`)
creates `sector`, `institution`, `employee` (id = the IPPIS employee number), `employment`, `job`
(append-only: trigger `trg_job_append_only` refuses DELETE and any UPDATE other than
`end_date`/`reason_for_change`) and `employee_sso_link`. They run at startup when
`IDENTITY_DB_MIGRATE=true` (default; Postgres advisory locks make concurrent replicas safe). Set it
to `false` and run migrations from a single job if your policy requires it. A failed migration is
logged and the wrapper keeps serving (mirror features fail until fixed).

## 5. Fill it
```bash
java -jar sso-wrapper.jar ippis-reconcile --require-mirror    # delta; first run with no watermark pulls everyone
# dev only: seed a roster covering every directory state (Keycloak is only read)
java -jar sso-wrapper.jar seed-identity-mirror [--dry-run]     # or SPRING_PROFILES_ACTIVE=seed-mirror
```
The seeder refuses `APP_ENV=staging|prod`.

## 6. Verify
```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8000/employees   # 401 (asks for a token), not 503
```

## 7. Backup
```bash
docker compose exec -T postgres pg_dump -U sso_identity_rw sso_identity | gzip > "backup/sso_identity_$(date +%F).sql.gz"
```
Restorable independently of Keycloak's dump — say which database a restore targets.

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `permission denied for schema public` during migration | `ALTER DATABASE sso_identity OWNER TO sso_identity_rw;` |
| `/employees` 503 `identity_db_not_configured` | `IDENTITY_DATABASE_URL` unset in the wrapper's environment |
| startup error "empty password (:@)" | set `IDENTITY_DATABASE_PASSWORD`, or unset the URL |
| reconcile exits 2 with `--require-mirror` | no URL, or database unreachable |
| reconcile exits 3 | mirror writes failed; read the first error in the WARNING block |
| mirror "refused" counts | reassigned employee number (same number, different national id) or an identifier another employee holds — fix in IPPIS; the stored row is untouched |

## 9. Teardown (dev only)
```bash
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d postgres -c 'DROP DATABASE IF EXISTS sso_identity' -c 'DROP ROLE IF EXISTS sso_identity_rw'
```
