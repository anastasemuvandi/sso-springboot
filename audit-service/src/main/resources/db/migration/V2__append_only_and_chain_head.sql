-- Append-only protection and chain serialisation (not in the reference; see
-- docs/implemented/audit-service.md).

-- Single-row chain head. Every append takes SELECT ... FOR UPDATE on this row, so concurrent
-- writers queue on it and each sees the previous writer's hash: the chain cannot fork. (Locking
-- the newest audit_logs row, as the reference did, forks on an empty table and under READ
-- COMMITTED re-checks.) It also lets the verifier detect deletion of the newest rows.
CREATE TABLE IF NOT EXISTS audit_chain_head (
    id              SMALLINT     PRIMARY KEY CHECK (id = 1),
    last_hash       VARCHAR(64)  NOT NULL,
    last_created_at TIMESTAMPTZ,
    event_count     BIGINT       NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
INSERT INTO audit_chain_head (id, last_hash, last_created_at, event_count)
SELECT 1,
       COALESCE((SELECT event_hash FROM audit_logs ORDER BY created_at DESC LIMIT 1), repeat('0', 64)),
       (SELECT max(created_at) FROM audit_logs),
       (SELECT count(*) FROM audit_logs)
ON CONFLICT (id) DO NOTHING;

-- Hash-only record of rows removed by the retention purge, written by the trigger below in the
-- same statement as the delete. The verifier bridges a prev_hash gap only through these links.
CREATE TABLE IF NOT EXISTS audit_purged_links (
    event_hash  VARCHAR(64)  PRIMARY KEY,
    prev_hash   VARCHAR(64)  NOT NULL,
    category    VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    purged_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS ix_audit_purged_links_prev ON audit_purged_links (prev_hash);

-- audit_logs refuses UPDATE and DELETE. The one exception is the retention purge, which runs
-- in a transaction that has set the transaction-local flag
--   SELECT set_config('gorsso.audit_retention_purge', 'on', true)
-- and even then each deleted row leaves its hash link behind.
CREATE OR REPLACE FUNCTION audit_logs_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('gorsso.audit_retention_purge', true) = 'on' THEN
        INSERT INTO audit_purged_links (event_hash, prev_hash, category, created_at)
        VALUES (OLD.event_hash, OLD.prev_hash, OLD.category, OLD.created_at)
        ON CONFLICT (event_hash) DO NOTHING;
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'audit_logs is append-only: % refused', TG_OP
        USING ERRCODE = 'restrict_violation',
              HINT = 'Only the retention purge may delete audit rows; nothing may update them.';
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_logs_append_only ON audit_logs;
CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_append_only();

CREATE OR REPLACE FUNCTION audit_refuse_truncate() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: TRUNCATE refused', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_logs_no_truncate ON audit_logs;
CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION audit_refuse_truncate();

-- The purge links and the chain head are part of the evidence: links are insert-only, the head
-- row can be moved forward but never removed.
CREATE OR REPLACE FUNCTION audit_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%: % refused', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_purged_links_insert_only ON audit_purged_links;
CREATE TRIGGER trg_audit_purged_links_insert_only
    BEFORE UPDATE OR DELETE ON audit_purged_links
    FOR EACH ROW EXECUTE FUNCTION audit_refuse_change();
DROP TRIGGER IF EXISTS trg_audit_purged_links_no_truncate ON audit_purged_links;
CREATE TRIGGER trg_audit_purged_links_no_truncate
    BEFORE TRUNCATE ON audit_purged_links
    FOR EACH STATEMENT EXECUTE FUNCTION audit_refuse_truncate();

DROP TRIGGER IF EXISTS trg_audit_chain_head_no_delete ON audit_chain_head;
CREATE TRIGGER trg_audit_chain_head_no_delete
    BEFORE DELETE ON audit_chain_head
    FOR EACH ROW EXECUTE FUNCTION audit_refuse_change();
DROP TRIGGER IF EXISTS trg_audit_chain_head_no_truncate ON audit_chain_head;
CREATE TRIGGER trg_audit_chain_head_no_truncate
    BEFORE TRUNCATE ON audit_chain_head
    FOR EACH STATEMENT EXECUTE FUNCTION audit_refuse_truncate();
