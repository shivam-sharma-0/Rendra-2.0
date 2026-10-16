ALTER TABLE project_supabase_connections
    ADD COLUMN connection_epoch uuid NOT NULL DEFAULT gen_random_uuid();

CREATE TABLE supabase_apply_operations (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    target_key varchar(160) NOT NULL,
    connection_epoch uuid NOT NULL,
    manifest_hash varchar(80) NOT NULL CHECK (manifest_hash ~ '^sha256:[0-9a-f]{64}$'),
    idempotency_key varchar(100) NOT NULL,
    approved_by bigint NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN
        ('PREPARED','APPLYING','RECOVERY_REQUIRED','COMPLETED','FAILED','ABORTED')),
    owner_id uuid NOT NULL,
    fence bigint NOT NULL DEFAULT 1 CHECK (fence > 0),
    lease_expires_at timestamptz NOT NULL,
    next_reconcile_at timestamptz,
    failure_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_supabase_apply_request UNIQUE (project_id, approved_by, idempotency_key)
);
CREATE UNIQUE INDEX uq_supabase_apply_active_target ON supabase_apply_operations(target_key)
    WHERE state IN ('PREPARED','APPLYING','RECOVERY_REQUIRED');
CREATE UNIQUE INDEX uq_supabase_apply_active_project ON supabase_apply_operations(project_id)
    WHERE state IN ('PREPARED','APPLYING','RECOVERY_REQUIRED');
CREATE INDEX ix_supabase_apply_recovery ON supabase_apply_operations(lease_expires_at, next_reconcile_at)
    WHERE state IN ('PREPARED','APPLYING','RECOVERY_REQUIRED');
CREATE INDEX ix_supabase_apply_target_history ON supabase_apply_operations(target_key, id);

CREATE TABLE supabase_migration_attempts (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL REFERENCES supabase_apply_operations(id),
    ordinal integer NOT NULL CHECK (ordinal >= 0 AND ordinal < 100),
    attempt_number integer NOT NULL DEFAULT 1 CHECK (attempt_number > 0),
    name varchar(200) NOT NULL,
    checksum varchar(80) NOT NULL CHECK (checksum ~ '^sha256:[0-9a-f]{64}$'),
    byte_count integer NOT NULL CHECK (byte_count > 0 AND byte_count <= 200000),
    sql_cipher text NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('PREPARED','SENDING','APPLIED','NOT_SENT','UNKNOWN')),
    dispatch_fence bigint CHECK (dispatch_fence > 0),
    sent_at timestamptz,
    settled_at timestamptz,
    provider_request_id varchar(160),
    failure_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_supabase_migration_attempt UNIQUE (operation_id, ordinal, attempt_number),
    CHECK (state NOT IN ('SENDING','APPLIED','UNKNOWN') OR (sent_at IS NOT NULL AND dispatch_fence IS NOT NULL)),
    CHECK (state <> 'APPLIED' OR settled_at IS NOT NULL)
);
CREATE INDEX ix_supabase_migration_operation ON supabase_migration_attempts(operation_id, ordinal, attempt_number);

-- Outcomes can advance, but the bytes and approval/target identities can never be rewritten.
CREATE FUNCTION guard_supabase_migration_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.operation_id,NEW.ordinal,NEW.attempt_number,NEW.name,NEW.checksum,NEW.byte_count,NEW.sql_cipher,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.operation_id,OLD.ordinal,OLD.attempt_number,OLD.name,OLD.checksum,OLD.byte_count,OLD.sql_cipher,OLD.created_at) THEN
        RAISE EXCEPTION 'Migration snapshot is immutable';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
        (OLD.state = 'PREPARED' AND NEW.state IN ('SENDING','NOT_SENT')) OR
        (OLD.state = 'SENDING' AND NEW.state IN ('APPLIED','UNKNOWN'))
    ) THEN
        RAISE EXCEPTION 'Invalid migration outcome transition';
    END IF;
    IF OLD.state <> 'PREPARED' AND ROW(NEW.dispatch_fence,NEW.sent_at) IS DISTINCT FROM ROW(OLD.dispatch_fence,OLD.sent_at) THEN
        RAISE EXCEPTION 'Migration dispatch identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_supabase_migration_attempt BEFORE UPDATE ON supabase_migration_attempts
    FOR EACH ROW EXECUTE FUNCTION guard_supabase_migration_attempt();

CREATE FUNCTION guard_supabase_apply_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.project_id,NEW.target_key,NEW.connection_epoch,NEW.manifest_hash,NEW.idempotency_key,NEW.approved_by,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.project_id,OLD.target_key,OLD.connection_epoch,OLD.manifest_hash,OLD.idempotency_key,OLD.approved_by,OLD.created_at) THEN
        RAISE EXCEPTION 'Migration approval is immutable';
    END IF;
    IF NEW.fence < OLD.fence OR (NEW.owner_id <> OLD.owner_id AND NEW.fence <= OLD.fence) THEN
        RAISE EXCEPTION 'Migration fence must advance';
    END IF;
    IF OLD.state IN ('COMPLETED','FAILED','ABORTED') AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION 'Migration operation is terminal';
    END IF;
    IF OLD.state = 'RECOVERY_REQUIRED' AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION 'Migration outcome requires external proof';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_supabase_apply_identity BEFORE UPDATE ON supabase_apply_operations
    FOR EACH ROW EXECUTE FUNCTION guard_supabase_apply_identity();
