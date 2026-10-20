# Runbook: access the databases locally

| | |
|---|---|
| Applies to | the local stack, project `gorsso` (`make start`) |
| Server | one Postgres 16 container, four databases |
| Audience | anyone debugging state. Read section 2 before writing anything |

```bash
DC="docker compose -p gorsso -f deploy/docker-compose.base.yml -f deploy/docker-compose.apps.yml"
```

| Database | Owner role | Holds | Rule |
|---|---|---|---|
| `keycloak` | `keycloak` | Keycloak's schema: users, clients, roles, groups, sessions, policies, events | Read-only from SQL. Write through the Admin REST API (wrapper, console, `kcadm.sh`) |
| `sso_identity` | `sso_identity_rw` | IPPIS mirror: `sector`, `institution`, `employment`, `job`, `employee`, `employee_sso_link` | Writes through the wrapper's mirror writer or Flyway; hand SQL only for throwaway experiments |
| `audit` | `audit` | `audit_logs` (hash chained, append-only triggers), `audit_chain_head`, `audit_purged_links`, `retention_policies`, `service_health`, `cert_expiry_tracking`, `shedlock` | Never write. UPDATE/DELETE/TRUNCATE are refused by triggers and break the chain |
| `notifications` | `notification` | `alerts`, `alert_configs`, `notifications`, `detector_state`, `processed_audit_events`, `shedlock` | Through the notification API |

## 0. Credentials (local dev only)

Defaults from `deploy/.env.example`. Staging and production must not reuse any of them.

| What | From the host | From a container | Database | User | Password |
|---|---|---|---|---|---|
| Superuser | `localhost:5435` | `postgres:5432` | `postgres` | `gorsso` | `gorsso-dev` (`POSTGRES_PASSWORD`) |
| Keycloak DB | same | same | `keycloak` | `keycloak` | `keycloak-dev` (`KC_DB_PASSWORD`) |
| Mirror | same | same | `sso_identity` | `sso_identity_rw` | `sso-identity-dev` (`IDENTITY_DB_PASSWORD`) |
| Audit | same | same | `audit` | `audit` | `audit-dev` (`AUDIT_DB_PASSWORD`) |
| Notifications | same | same | `notifications` | `notification` | `notification-dev` (`NOTIFICATION_DB_PASSWORD`) |
| Keycloak console | http://localhost:8080 | `http://keycloak:8080` | realm `master` | `admin` | `admin` |
| Adminer | http://localhost:8081 | | | any pair above, server `postgres` | |

The host port is `HOST_PORT_POSTGRES` (default 5435). Confirm what is actually in force:

```bash
docker exec gorsso-postgres-1 printenv POSTGRES_USER POSTGRES_DB
```

Realm users (people inside `government-internal`) come from the seeder, not from this table. Demo password `GorSsoDev#2026`: `superadmin`, `minagri.admin`, `minisante.admin`, `<slug>.hr|auditor|staff|planner|unassigned`. Brute force is on: 5 wrong passwords lock an account for 15 minutes (Keycloak answers 403). Do not probe credentials in a loop.

## 1. Where things are

| | Value |
|---|---|
| Container | `gorsso-postgres-1` |
| Volume | `gorsso_postgres_data` (removed by `make clean`) |
| Init script | `deploy/postgres/initdb/10-databases.sh`, runs only on an empty volume |

If a database is missing on an older volume, create it by hand (`create-sso-identity-db.md` section 3.2 shows the pattern).

## 2. Do not write to Keycloak's database

The `keycloak` owner can `UPDATE user_entity` or `INSERT INTO user_role_mapping`, which bypasses every wrapper RBAC gate, the delegated institution filter, the per-client access policies and every audit event. It also bypasses Keycloak's caches: the running server keeps realm, client and user state in memory, so a changed row is not seen until a restart. The usual outcome is a database and a server that disagree, which then looks like a wrapper bug.

`SELECT` freely. Make changes through:

| Change | Use |
|---|---|
| Users, roles, groups, clients, policies | The wrapper API (http://localhost:8000/docs/api), the audited path |
| Same, without the wrapper | Keycloak console or `kcadm.sh` (section 4.3) |
| Institution onboarding | `configs/institutions.yaml` + `make tf-apply` (or `make seed` in dev) |
| Delegated permissions | `templates.json` + `make delegated-apply` |
| Clear a lockout | `DELETE /admin/realms/government-internal/attack-detection/brute-force/users/<id>` |
| Start over | `make clean && make start && make seed` |

## 3. Getting a prompt

### 3.1 psql in the container

```bash
docker exec -it gorsso-postgres-1 psql -U gorsso -d keycloak          # superuser over the socket, no password
docker exec -it -e PGPASSWORD=sso-identity-dev gorsso-postgres-1 \
  psql -h localhost -U sso_identity_rw -d sso_identity                # as the mirror role
docker exec -i gorsso-postgres-1 psql -U gorsso -d keycloak <<'SQL'
SELECT count(*) FROM user_entity;
SQL
```

The superuser reaches every database by design, so it proves nothing about isolation (section 6 uses `sso_identity_rw`). There is no `postgres` role; the superuser is `gorsso`.

### 3.2 Adminer

http://localhost:8081, System PostgreSQL, Server `postgres`, then a user/password/database from section 0.

### 3.3 A GUI or script on the host

Use `localhost:5435`. `postgres:5432` resolves only inside the compose network; mixing the two is the most common failure.

```
jdbc:postgresql://localhost:5435/sso_identity   user sso_identity_rw
```

## 4. Reading Keycloak's database

### 4.1 Useful tables

| Table | Holds |
|---|---|
| `realm` | realms (`name`, `id`) |
| `user_entity`, `user_attribute` | users and attributes (`institution`, `employee_number`, `national_id`, ...) |
| `keycloak_group`, `user_group_membership` | groups and membership |
| `keycloak_role`, `user_role_mapping` | realm/client roles and assignments |
| `client`, `client_attributes` | clients (`sso.client.owner_institution`, `sso.client.join_column`, `sso.client.mfa_required`) |
| `resource_server_policy`, `associated_policy` | authz policies (`sso-access-groups`, `sso-access-users`, `sso-access-denied-users`, FGAP v2) |
| `offline_user_session`, `offline_client_session` | persistent sessions (Keycloak 26 stores user sessions here) |
| `event_entity`, `admin_event_entity` | login and admin events |

### 4.2 Recipes

```sql
-- users of one institution
SELECT u.username, a.value AS institution
FROM user_entity u JOIN user_attribute a ON a.user_id = u.id AND a.name = 'institution'
JOIN realm r ON r.id = u.realm_id AND r.name = 'government-internal'
WHERE a.value = 'minagri';

-- who holds an inst:<slug>:* role
SELECT u.username, k.name
FROM user_role_mapping m JOIN keycloak_role k ON k.id = m.role_id
JOIN user_entity u ON u.id = m.user_id
WHERE k.name LIKE 'inst:%' ORDER BY k.name, u.username;

-- failed logins in the last hour
SELECT to_timestamp(event_time/1000), type, user_id, ip_address
FROM event_entity WHERE type = 'LOGIN_ERROR' AND event_time > (extract(epoch FROM now() - interval '1 hour') * 1000)
ORDER BY event_time DESC;
```

### 4.3 What SQL will not tell you

Effective permissions (composites, FGAP v2 evaluation, the access decision), brute-force state (held in memory), and anything the server has cached. Ask Keycloak instead:

```bash
docker exec -it gorsso-keycloak-1 /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user admin --password admin
docker exec -it gorsso-keycloak-1 /opt/keycloak/bin/kcadm.sh get users -r government-internal -q username=minagri.admin
```

## 5. The mirror (`sso_identity`)

`employee.id` is the IPPIS employee number. `employment` links an employee to an `institution` and has `job` rows; `job` is append-only (a trigger refuses UPDATE and DELETE). `employee_sso_link.keycloak_user_id` and `match_state` link an employee to an account.

```sql
-- joiner queue: employees with no account
SELECT e.id, l.first_seen_at FROM employee e
JOIN employee_sso_link l ON l.employee_id = e.id
WHERE l.keycloak_user_id IS NULL ORDER BY l.first_seen_at;
```

Schema changes go through Flyway migrations in the wrapper (`sso-wrapper/src/main/resources/db/identity/`), never by hand. To fill it in dev, run the reconcile (`ippis-sync.md`).

The boundary that outranks everything here: nothing in the mirror decides authorisation.

## 6. Verify the isolation

```bash
docker exec -e PGPASSWORD=sso-identity-dev gorsso-postgres-1 psql -h localhost -U sso_identity_rw -d sso_identity -c '\conninfo'   # succeeds
docker exec -e PGPASSWORD=sso-identity-dev gorsso-postgres-1 psql -h localhost -U sso_identity_rw -d keycloak -c 'SELECT 1'        # must fail
```

The second must fail with `permission denied for database "keycloak"`. Repeat for the `audit` and `notification` roles against each other's databases.

## 7. Backup, restore, reset

```bash
for db in keycloak sso_identity audit notifications; do
  docker exec gorsso-postgres-1 pg_dump -U gorsso -Fc "$db" > "backup/${db}_$(date +%F).dump"
done
# restore one (stop the service that uses it first)
docker exec -i gorsso-postgres-1 pg_restore -U gorsso -d sso_identity --clean --if-exists < backup/sso_identity_<date>.dump
```

After restoring `audit`, run `GET /audit/integrity/verify` on the audit service. Reset everything: `make clean` (drops the volume), then `make start && make seed`.

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `role "postgres" does not exist` | The superuser is `gorsso` |
| `database "..." does not exist` | `\l` lists what exists; older volumes predate the init script |
| `connection refused` on 5435 | Stack not up, or `HOST_PORT_POSTGRES` overridden |
| Edited a Keycloak row, nothing changed | Server cache. Undo, restart Keycloak, use the Admin API |
| `permission denied for database` | Correct: each role reaches only its own database |
| Audit insert/update refused | Correct: `audit_logs` is append-only |
