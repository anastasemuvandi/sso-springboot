# Runbook: production sizing (3 VMs)

Port of the reference `PROD_SERVER_REQUIREMENTS.md` to the Spring Boot stack. Derived from `deploy/docker-compose.base.yml` + `docker-compose.apps.yml` + `docker-compose.prod.yml`. Estimates until a load test replaces them.

## 1. What runs

| Service | Image | Listens (internal) | Memory limit | Notes |
|---|---|---|---|---|
| reverse proxy | nginx or https-portal | 80, 443 | 128 M | The only public entry point (`tls.md`) |
| `keycloak` | built from `keycloak/Dockerfile` (26.2) | 8080, 9000 (management) | 3 G | `JAVA_OPTS_KC_HEAP`; token signing is the main CPU user; health and metrics on 9000 |
| `wrapper` | `sso-wrapper` | 8000 | 1 G | The API every app calls. JVM, `-XX:MaxRAMPercentage=75` |
| `console-bff` | `console-bff` (serves the built console) | 3001 | 512 M | Sessions in Redis |
| `audit-service` | `audit-service` | 8001 | 768 M | Includes its scheduled workers (ShedLock JDBC); no separate worker container |
| `notification-service` | `notification-service` | 8002 | 512 M | Detector and channels in-process (`@Scheduled`) |
| `redis` | `redis:7-alpine` | 6379 | 256 M | Rate limits, auth codes, setup links, BFF sessions, ShedLock, audit outbox |
| `postgres` | `postgres:16` | 5432 | 4 G (on the db VM) | `keycloak`, `sso_identity`, `audit`, `notifications` |

Not in production: `ippis-mock`, `mailhog`, `adminer`, the `seed` service, sample apps. Point `IPPIS_BASE_URL` at the real IPPIS before go-live.

Compared with the Python reference there are no Celery workers: scheduled work runs inside each Spring service under ShedLock, so a second replica of a service is safe and the lock lease stops a killed run from blocking later ones. JVM services need more resident memory than the Python processes they replace; size the heap to the container limit (the images use `MaxRAMPercentage=75`).

## 2. Topology: three VMs (decided go-live shape)

| VM | vCPU | RAM | Disk | Runs |
|---|---|---|---|---|
| app | 8 | 24 GB | 120 GB SSD | reverse proxy, Keycloak, wrapper, console-bff, audit-service, notification-service, Redis |
| db | 4 | 16 GB | 380 GB NVMe, at least 5 000 sustained write IOPS, low fsync latency | PostgreSQL 16 |
| ops | 2 | 8 GB | 500 GB SSD | backups (pg_dump + WAL archive), log shipping, metrics (Prometheus scrape of 9000 and `/actuator/prometheus`) |
| total | 14 | 48 GB | 1 TB | |

Moving the database off the app host makes the app tier rebuildable without a restore, and keeps backups and monitoring outside the failure domain they watch. All volumes on LVM or extendable cloud disks.

Fallback, single VM: 8 vCPU / 32 GB / 300 GB NVMe, accepting that database, backups and monitoring share one failure domain. Absolute minimum (accepted risk): 4 vCPU / 16 GB / 200 GB SSD.

If RISA provides a managed PostgreSQL 16, the db VM drops out (app + ops: 10 vCPU / 32 GB / 620 GB). If it also provides central backup and monitoring, only the app VM remains.

### App VM memory budget

Keycloak 3 G + wrapper 1 G + BFF 0.5 G + audit 0.75 G + notification 0.5 G + Redis 0.25 G + proxy 0.1 G is about 6 GB of limits. The rest is page cache, the Docker daemon and headroom for a second wrapper or Keycloak replica during a rolling restart. Do not build images on the host (CI builds and pushes them); if unavoidable, add 4 GB RAM and 20 GB disk.

Keycloak heap: set the container `mem_limit` and keep `JAVA_OPTS_KC_HEAP=-XX:MaxRAMPercentage=70 -XX:MinRAMPercentage=70 -XX:InitialRAMPercentage=50`. Use `JAVA_OPTS_KC_HEAP`, not `JAVA_OPTS` (which drops every other default).

## 3. Database

Keycloak 26 writes user sessions to PostgreSQL by default (persistent sessions). Consequences:

- Commit latency is inside every login, refresh and logout. This is why the db disk must be NVMe with low fsync latency; a shared, over-committed SAN tier shows up as random slow logins with nothing in the application logs.
- Session tables churn. Tune autovacuum for them (lower `autovacuum_vacuum_scale_factor`, raise `autovacuum_vacuum_cost_limit`).
- Losing a Keycloak node no longer loses sessions, so rolling restarts are invisible to users.
- Session volume tracks concurrent sessions and their lifetimes. Set realm timeouts deliberately; long-lived offline tokens are what make it grow.

Separation: four databases, four roles, each with `CONNECT` only on its own (`deploy/postgres/initdb/10-databases.sh`). The audit database is not writable by Keycloak's or the wrapper's credentials.

Starting point for a 16 GB db VM: `shared_buffers=4GB`, `effective_cache_size=11GB`, `max_connections=300` (Keycloak pool, wrapper Hikari pool for `sso_identity`, audit pool 20, notification pool, Terraform state), `work_mem=16MB`, `maintenance_work_mem=512MB`, WAL archiving on from day one, transparent huge pages off.

Redis: enable AOF. It holds the audit outbox (`audit:outbox`), setup-link hashes, import previews and BFF sessions; losing them on restart drops queued audit events and logs everyone out of the console.

## 4. Storage growth

| Mount (db VM) | Size | Holds |
|---|---|---|
| `/` | 40 GB | OS |
| `/srv/postgres` | 300 GB | data directory |
| `/srv/backups` | 40 GB or NFS | local staging before the off-host copy (ops VM) |

The driver is the audit trail, append-only by design. At 20 000 logins a day, about 3 events per login, about 1 KB per row with indexes: about 60 MB a day, 22 GB a year. Configure audit retention policies (with legal hold where required) and Keycloak event expiration (90 days in the realm). Alert at 75 % disk.

## 5. Network and ports

Inbound to the app VM from clients: 443 (and 80 for redirect / ACME). SSH from the admin VLAN only.

Never published: 8000, 8080, 3001, 8001, 8002, 5432, 6379, and 9000. Port 9000 (Keycloak management: `/health/ready`, `/metrics`) must be reachable from the proxy/load balancer health probe and the ops VM only; it is unauthenticated. The prod overlay publishes nothing but the proxy; check with `docker compose ps` after each deploy.

Outbound: IPPIS (https), SMTP relay (587/465), NTP, package mirror and container registry, ACME endpoints if Let's Encrypt is used.

DNS (final before go-live; Keycloak stamps the hostname into every token `iss` and the wrapper pins it in `KEYCLOAK_LOCAL_ISSUER`):

```
sso.gov.rw            -> wrapper
admin.sso.gov.rw      -> console-bff (government network / admin VLAN only)
keycloak.sso.gov.rw   -> Keycloak
```

## 6. Time

chrony against a GoR NTP source, skew under 30 s, monitored. JWT validation, webhook timestamps (`IPPIS_WEBHOOK_MAX_SKEW`) and TOTP all fail on drift, and it looks like a random authentication outage.

## 7. Backup and recovery

| Item | Method | Frequency | Retention |
|---|---|---|---|
| PostgreSQL (4 databases) | `pg_dump` per database + WAL archiving | nightly + continuous | 30 daily, 12 monthly |
| Keycloak realms | `kc.sh export` | weekly and before each change | 12 versions |
| `.env` and secret files | encrypted, off-host | on change | current + 3 |
| Redis AOF | volume snapshot | daily | 7 days |
| VMs | hypervisor snapshot | before each deploy | last 3 |

RPO 15 minutes, RTO 4 hours. Test a restore quarterly and run `GET /audit/integrity/verify` after it; an audit backup that does not verify is not a backup.

## 8. Monitoring

- Ship container logs (JSON, with `correlationId`) to the central platform; local `json-file` rotation is a buffer.
- Health: `/actuator/health/readiness` on wrapper, console-bff, audit-service, notification-service; Keycloak `:9000/health/ready`.
- Metrics: `/actuator/prometheus` on each Spring service; Keycloak `:9000/metrics` (DB pool saturation and session write latency are in the login path).
- Alerts: disk over 75 %, memory over 85 %, restart loops, Keycloak 5xx, wrapper 429 rate, audit integrity failure, certificate expiry under 21 days, audit outbox length growing.

## 9. Hardening

- Host firewall default deny; SSH keys only, no root login; Docker not on TCP; no `docker.sock` mounts.
- Every secret replaced. The staging/prod overlays use `${VAR:?}` for each one, and the Spring services refuse to start in `APP_ENV=staging|prod` without them. Prefer `<NAME>_FILE` secrets over env vars (env vars show in `docker inspect`).
- Master-realm admin: strong password, MFA, break-glass only. Day-to-day administration goes through the wrapper so it is audited.
- Patch monthly, plus out of band for critical CVEs.

## 10. Growth path (phase 2)

Two app nodes behind a load balancer, PostgreSQL primary + streaming standby. The wrapper already keeps rate limits, auth codes and locks in Redis, and the BFF keeps sessions in Redis, so they scale horizontally. Keycloak clustering needs Infinispan/JGroups discovery between nodes (`KC_CACHE=ispn`) and has not been tested yet.
