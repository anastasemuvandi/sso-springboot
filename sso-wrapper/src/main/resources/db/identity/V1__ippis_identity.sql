-- =============================================================================
-- IPPIS identity mirror (sso_identity) — the normalised GESB shape
-- =============================================================================
-- Ported from the reference's db/schema/ippis_identity.sql (Alembic 003, with the
-- flat ippis_employees table of 001/002/004 never created here). Constraint and
-- index names are identical to the reference on purpose.
--
-- Database: `sso_identity` — NEVER Keycloak's database. Nothing in it decides
-- authorisation: it is a mirror of what IPPIS said, plus our sync bookkeeping.
--
--   sector -> institution <- employment -> job (append-only, trigger-enforced)
--             employment -> employee <- employee_sso_link
--
-- `employee.id` IS the IPPIS employee number (natural key). A reassigned number
-- (same id, different national_id) is refused by the writer, not by the schema;
-- see service/ippis/IdentityMirrorWriter.
-- =============================================================================

-- GoRSSO-owned vocabularies (no column yet; kept for parity with the reference).
CREATE TYPE sso_join_column AS ENUM ('nid', 'phone', 'email');
CREATE TYPE sso_client_scope AS ENUM ('institution', 'cross_cutting', 'platform');

-- ── sector ───────────────────────────────────────────────────────────────────
CREATE TABLE sector (
    id          text        PRIMARY KEY,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_sector_id_not_blank   CHECK (btrim(id) <> ''),
    CONSTRAINT ck_sector_name_not_blank CHECK (btrim(name) <> '')
);
CREATE INDEX ix_sector_name ON sector (name);

-- ── institution ──────────────────────────────────────────────────────────────
-- short_code / is_onboarded are GoRSSO's, written on INSERT only (never by the
-- sync's update set). short_code is NOT unique: many IPPIS codes may map to one slug.
CREATE TABLE institution (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    ippis_institution_id text        NOT NULL,
    name                 text        NOT NULL,
    sector_id            text        CONSTRAINT fk_institution_sector_id
                                         REFERENCES sector (id)
                                         ON UPDATE CASCADE ON DELETE RESTRICT,
    source_created_at    timestamptz,
    source_updated_at    timestamptz,
    is_onboarded         boolean     NOT NULL DEFAULT false,
    short_code           text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_institution_ippis_institution_id UNIQUE (ippis_institution_id),
    CONSTRAINT ck_institution_ippis_id_not_blank CHECK (btrim(ippis_institution_id) <> ''),
    CONSTRAINT ck_institution_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_institution_short_code_slug
        CHECK (short_code IS NULL OR short_code ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);
CREATE INDEX ix_institution_sector_id         ON institution (sector_id);
CREATE INDEX ix_institution_short_code        ON institution (short_code) WHERE short_code IS NOT NULL;
CREATE INDEX ix_institution_name              ON institution (name);
CREATE INDEX ix_institution_source_updated_at ON institution (source_updated_at);
CREATE INDEX ix_institution_onboarded         ON institution (short_code) WHERE is_onboarded;

-- ── employee ─────────────────────────────────────────────────────────────────
CREATE TABLE employee (
    id                text        PRIMARY KEY,
    national_id       text,
    rssb_number       text,
    phone             text,
    email             text,
    first_name        text,
    last_name         text,
    gender            text,
    marital_status    text,
    date_of_birth     date,
    source_created_at timestamptz,
    source_updated_at timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_employee_id_not_blank    CHECK (btrim(id) <> ''),
    CONSTRAINT uq_employee_national_id     UNIQUE (national_id),
    CONSTRAINT uq_employee_rssb_number     UNIQUE (rssb_number),
    CONSTRAINT uq_employee_phone           UNIQUE (phone),
    CONSTRAINT ck_employee_national_id_not_blank CHECK (national_id IS NULL OR btrim(national_id) <> ''),
    CONSTRAINT ck_employee_rssb_number_not_blank CHECK (rssb_number IS NULL OR btrim(rssb_number) <> ''),
    CONSTRAINT ck_employee_phone_not_blank       CHECK (phone IS NULL OR btrim(phone) <> ''),
    CONSTRAINT ck_employee_email_not_blank       CHECK (email IS NULL OR btrim(email) <> ''),
    CONSTRAINT ck_employee_gender         CHECK (gender IS NULL OR gender IN ('M', 'F')),
    CONSTRAINT ck_employee_marital_status CHECK (marital_status IS NULL OR marital_status IN ('S', 'M', 'D', 'W')),
    CONSTRAINT ck_employee_date_of_birth  CHECK (date_of_birth IS NULL OR date_of_birth <= CURRENT_DATE)
);
-- Case-insensitive email uniqueness (this index IS the UNIQUE (email) constraint).
CREATE UNIQUE INDEX uq_employee_email_lower ON employee (lower(email));
CREATE INDEX ix_employee_last_first        ON employee (last_name, first_name);
CREATE INDEX ix_employee_source_updated_at ON employee (source_updated_at);

-- ── employment ───────────────────────────────────────────────────────────────
CREATE TABLE employment (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id       text        NOT NULL
                                      CONSTRAINT fk_employment_employee_id
                                      REFERENCES employee (id)
                                      ON UPDATE CASCADE ON DELETE CASCADE,
    institution_id    uuid        NOT NULL
                                      CONSTRAINT fk_employment_institution_id
                                      REFERENCES institution (id)
                                      ON UPDATE CASCADE ON DELETE RESTRICT,
    source_updated_at timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_employment_employee_institution UNIQUE (employee_id, institution_id)
);
CREATE INDEX ix_employment_employee_id       ON employment (employee_id);
CREATE INDEX ix_employment_institution_id    ON employment (institution_id);
CREATE INDEX ix_employment_source_updated_at ON employment (source_updated_at);

-- ── job (append-only history) ────────────────────────────────────────────────
CREATE TABLE job (
    id                      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    ippis_job_id            text        NOT NULL,
    employment_id           uuid        NOT NULL
                                            CONSTRAINT fk_job_employment_id
                                            REFERENCES employment (id)
                                            ON UPDATE CASCADE ON DELETE CASCADE,
    position_id             text,
    position_name           text,
    unit_id                 text,
    unit_name               text,
    level                   text,
    has_subordinates        boolean     NOT NULL DEFAULT false,
    employee_group          text,
    is_secondary_employment boolean     NOT NULL DEFAULT false,
    is_acting               boolean     NOT NULL DEFAULT false,
    start_date              date        NOT NULL,
    end_date                date,
    reason_for_change       text,
    source_created_at       timestamptz,
    source_updated_at       timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_ippis_job_id UNIQUE (ippis_job_id),
    CONSTRAINT ck_job_ippis_job_id_not_blank CHECK (btrim(ippis_job_id) <> ''),
    CONSTRAINT ck_job_dates CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_job_employee_group CHECK (
        employee_group IS NULL OR employee_group IN (
            'under_statute', 'political_appointee', 'under_contract', 'intern',
            'diplomat', 'casual_worker', 'board_member')),
    CONSTRAINT ck_job_reason_for_change CHECK (
        reason_for_change IS NULL OR reason_for_change IN ('new', 'removed', 'changed'))
);
CREATE INDEX ix_job_employment_id     ON job (employment_id);
CREATE INDEX ix_job_current           ON job (employment_id) WHERE end_date IS NULL;
CREATE INDEX ix_job_position_id       ON job (position_id);
CREATE INDEX ix_job_unit_id           ON job (unit_id);
CREATE INDEX ix_job_source_updated_at ON job (source_updated_at);
CREATE INDEX ix_job_start_date        ON job (start_date);

-- ── Row-timestamp maintenance ────────────────────────────────────────────────
CREATE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sector_updated_at      BEFORE UPDATE ON sector
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_institution_updated_at BEFORE UPDATE ON institution
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_employee_updated_at    BEFORE UPDATE ON employee
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_employment_updated_at  BEFORE UPDATE ON employment
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── Append-only enforcement on `job` ─────────────────────────────────────────
-- An UPDATE may only close a job (end_date) or restate why it changed; every
-- other column is immutable, and DELETE is refused outright.
CREATE FUNCTION job_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'job % is append-only: close it with end_date, do not DELETE', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF  NEW.ippis_job_id            IS DISTINCT FROM OLD.ippis_job_id
     OR NEW.employment_id           IS DISTINCT FROM OLD.employment_id
     OR NEW.position_id             IS DISTINCT FROM OLD.position_id
     OR NEW.position_name           IS DISTINCT FROM OLD.position_name
     OR NEW.unit_id                 IS DISTINCT FROM OLD.unit_id
     OR NEW.unit_name               IS DISTINCT FROM OLD.unit_name
     OR NEW.level                   IS DISTINCT FROM OLD.level
     OR NEW.has_subordinates        IS DISTINCT FROM OLD.has_subordinates
     OR NEW.employee_group          IS DISTINCT FROM OLD.employee_group
     OR NEW.is_secondary_employment IS DISTINCT FROM OLD.is_secondary_employment
     OR NEW.is_acting               IS DISTINCT FROM OLD.is_acting
     OR NEW.start_date              IS DISTINCT FROM OLD.start_date
     OR NEW.created_at              IS DISTINCT FROM OLD.created_at
    THEN
        RAISE EXCEPTION
            'job % is append-only: only end_date and reason_for_change may change; a new posting is a new row', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;

    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_job_append_only
    BEFORE UPDATE OR DELETE ON job
    FOR EACH ROW EXECUTE FUNCTION job_append_only();

-- ── employee_sso_link (GoRSSO bookkeeping) ───────────────────────────────────
CREATE TABLE employee_sso_link (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id      text        NOT NULL
                                     CONSTRAINT fk_employee_sso_link_employee_id
                                     REFERENCES employee (id)
                                     ON UPDATE CASCADE ON DELETE CASCADE,
    keycloak_user_id text,
    match_state      text        NOT NULL DEFAULT 'unmatched',
    first_seen_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at     timestamptz NOT NULL DEFAULT now(),
    last_synced_at   timestamptz,
    last_sync_status text,
    last_sync_error  text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_employee_sso_link_employee UNIQUE (employee_id),
    CONSTRAINT ck_employee_sso_link_match_state CHECK (match_state IN ('matched', 'unmatched', 'ambiguous'))
);
CREATE INDEX ix_employee_sso_link_employee_id      ON employee_sso_link (employee_id);
CREATE INDEX ix_employee_sso_link_keycloak_user_id ON employee_sso_link (keycloak_user_id);
CREATE INDEX ix_employee_sso_link_last_seen_at     ON employee_sso_link (last_seen_at);
CREATE INDEX ix_employee_sso_link_unmatched        ON employee_sso_link (employee_id) WHERE keycloak_user_id IS NULL;

CREATE TRIGGER trg_employee_sso_link_updated_at BEFORE UPDATE ON employee_sso_link
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
