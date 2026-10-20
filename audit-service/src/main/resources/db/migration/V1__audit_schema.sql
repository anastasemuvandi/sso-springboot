-- Audit service schema. Port of the reference Alembic revisions 001 + 002
-- (audit-service/db/migrations/versions). Written with IF NOT EXISTS so Flyway can also
-- adopt a database the Python service already created (baseline-on-migrate, version 0).

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- audit_logs: immutable event ledger with a SHA-256 hash chain.
CREATE TABLE IF NOT EXISTS audit_logs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100) NOT NULL,
    category        VARCHAR(50)  NOT NULL,
    user_id         VARCHAR(255),
    employee_id     VARCHAR(100),
    app_id          VARCHAR(100),
    realm           VARCHAR(100),
    performed_by    VARCHAR(255),
    ip_address      VARCHAR(45),
    details         JSONB        NOT NULL DEFAULT '{}',
    severity        VARCHAR(20)  NOT NULL DEFAULT 'INFO',
    prev_hash       VARCHAR(64)  NOT NULL,
    event_hash      VARCHAR(64)  NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Revision 002: institution + target as first-class (indexed, hashed) columns, and the
-- hash formula version (1 = actor not covered, 2 = actor/institution/target/app/ip/severity).
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS institution  VARCHAR(100);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS target       VARCHAR(255);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS hash_version SMALLINT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_audit_logs_event_type  ON audit_logs (event_type);
CREATE INDEX IF NOT EXISTS idx_audit_logs_category    ON audit_logs (category);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id     ON audit_logs (user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_app_id      ON audit_logs (app_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at  ON audit_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_severity    ON audit_logs (severity);
CREATE INDEX IF NOT EXISTS ix_audit_logs_institution  ON audit_logs (institution);
CREATE INDEX IF NOT EXISTS ix_audit_logs_target       ON audit_logs (target);
-- The per-institution view is always "this institution, newest first".
CREATE INDEX IF NOT EXISTS ix_audit_logs_institution_created_at ON audit_logs (institution, created_at);

-- Backfill from details exactly as Alembic 002 did, for a database adopted at revision 001.
-- Only v1 rows are touched; details itself is never rewritten (it is hash input).
UPDATE audit_logs
   SET institution = NULLIF(details->>'institution', ''),
       target      = NULLIF(COALESCE(details->>'target_user', details->>'target'), '')
 WHERE hash_version = 1 AND institution IS NULL AND target IS NULL AND details IS NOT NULL
   AND (details->>'institution' IS NOT NULL OR details->>'target_user' IS NOT NULL
        OR details->>'target' IS NOT NULL);

-- service_health: periodic health snapshots written by the health-check worker.
CREATE TABLE IF NOT EXISTS service_health (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    service_name     VARCHAR(100) NOT NULL,
    status           VARCHAR(20)  NOT NULL,   -- UP | DOWN | DEGRADED | UNKNOWN
    response_time_ms INTEGER,
    details          JSONB        NOT NULL DEFAULT '{}',
    checked_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_service_health_name       ON service_health (service_name);
CREATE INDEX IF NOT EXISTS idx_service_health_checked_at ON service_health (checked_at);

-- cert_expiry_tracking: upserted daily by the certificate-expiry worker.
CREATE TABLE IF NOT EXISTS cert_expiry_tracking (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        VARCHAR(255) NOT NULL UNIQUE,
    employee_id    VARCHAR(100),
    email          VARCHAR(255),
    cert_expiry    DATE         NOT NULL,
    days_remaining INTEGER,
    notified_30    BOOLEAN      NOT NULL DEFAULT FALSE,
    notified_60    BOOLEAN      NOT NULL DEFAULT FALSE,
    notified_90    BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- retention_policies: per category; category NULL is the default for all categories.
CREATE TABLE IF NOT EXISTS retention_policies (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    category       VARCHAR(50),
    retention_days INTEGER      NOT NULL,
    legal_hold     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
-- The reference's UNIQUE(category) let several NULL (default) rows accumulate because NULLs
-- are distinct. Collapse any such duplicates (keep the newest), then enforce one default row.
DELETE FROM retention_policies r
 WHERE r.category IS NULL
   AND EXISTS (SELECT 1 FROM retention_policies o
                WHERE o.category IS NULL
                  AND (o.updated_at, o.id::text) > (r.updated_at, r.id::text));
CREATE UNIQUE INDEX IF NOT EXISTS uq_retention_policies_category
    ON retention_policies (category) NULLS NOT DISTINCT;

-- Sensible default: keep all events for 365 days unless overridden.
INSERT INTO retention_policies (category, retention_days, legal_hold)
SELECT NULL, 365, FALSE
 WHERE NOT EXISTS (SELECT 1 FROM retention_policies WHERE category IS NULL);
