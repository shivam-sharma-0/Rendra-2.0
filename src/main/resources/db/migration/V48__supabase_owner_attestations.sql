ALTER TABLE supabase_apply_operations ADD COLUMN owner_attestation_ids uuid[] NOT NULL DEFAULT '{}';
ALTER TABLE supabase_apply_operations DROP CONSTRAINT supabase_apply_operations_state_check;
ALTER TABLE supabase_apply_operations ADD CONSTRAINT supabase_apply_operations_state_check CHECK (state IN
    ('PREPARED','APPLYING','RECOVERY_REQUIRED','COMPLETED','FAILED','ABORTED','COMPLETED_ATTESTED','RESOLVED_ATTESTED'));
ALTER TABLE supabase_apply_operations ADD CONSTRAINT ck_supabase_completion_evidence CHECK (
    (state <> 'COMPLETED_ATTESTED' OR cardinality(owner_attestation_ids) > 0)
    AND (state <> 'COMPLETED' OR cardinality(owner_attestation_ids) = 0));

CREATE TABLE supabase_owner_attestations (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    target_key varchar(160) NOT NULL,
    connection_epoch uuid,
    operation_id uuid REFERENCES supabase_apply_operations(id),
    operation_fence bigint,
    kind varchar(16) NOT NULL CHECK (kind IN ('LEGACY','RESOLUTION')),
    review_hash varchar(80) NOT NULL CHECK (review_hash ~ '^sha256:[0-9a-f]{64}$'),
    request_hash varchar(80) NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    idempotency_key varchar(100) NOT NULL,
    attested_by bigint NOT NULL,
    attested_at timestamptz NOT NULL,
    statement_version integer NOT NULL DEFAULT 1 CHECK (statement_version = 1),
    sender_quiescent boolean NOT NULL CHECK (sender_quiescent),
    file_count integer NOT NULL CHECK (file_count BETWEEN 1 AND 1000),
    UNIQUE (project_id, attested_by, idempotency_key),
    UNIQUE (operation_id),
    CHECK ((kind = 'LEGACY' AND operation_id IS NULL AND operation_fence IS NULL AND connection_epoch IS NOT NULL)
        OR (kind = 'RESOLUTION' AND operation_id IS NOT NULL AND operation_fence IS NOT NULL AND operation_fence > 0))
);
CREATE INDEX ix_supabase_owner_attestation_target ON supabase_owner_attestations(target_key, attested_at, id);

CREATE TABLE supabase_owner_attested_files (
    attestation_id uuid NOT NULL REFERENCES supabase_owner_attestations(id),
    ordinal integer NOT NULL CHECK (ordinal >= 0 AND ordinal < 1000),
    name varchar(200) NOT NULL,
    checksum varchar(80) NOT NULL CHECK (checksum ~ '^sha256:[0-9a-f]{64}$'),
    decision varchar(16) NOT NULL CHECK (decision IN ('APPLIED','NOT_APPLIED')),
    attempt_id uuid UNIQUE REFERENCES supabase_migration_attempts(id),
    PRIMARY KEY (attestation_id, name),
    UNIQUE (attestation_id, ordinal)
);
CREATE INDEX ix_supabase_owner_attested_file ON supabase_owner_attested_files(name, checksum);

CREATE FUNCTION bind_supabase_owner_file() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    receipt supabase_owner_attestations%ROWTYPE;
BEGIN
    SELECT * INTO STRICT receipt FROM supabase_owner_attestations WHERE id=NEW.attestation_id;
    IF NEW.ordinal >= receipt.file_count THEN
        RAISE EXCEPTION 'Owner declaration file list is sealed';
    END IF;
    IF receipt.kind='LEGACY' AND (NEW.decision<>'APPLIED' OR NEW.attempt_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Invalid legacy declaration';
    END IF;
    IF receipt.kind='RESOLUTION' AND NOT EXISTS (
        SELECT 1 FROM supabase_migration_attempts a WHERE a.id=NEW.attempt_id AND a.operation_id=receipt.operation_id
            AND a.name=NEW.name AND a.checksum=NEW.checksum AND a.state='UNKNOWN'
    ) THEN
        RAISE EXCEPTION 'Owner declaration does not bind an uncertain step';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER bound_supabase_owner_file BEFORE INSERT ON supabase_owner_attested_files
    FOR EACH ROW EXECUTE FUNCTION bind_supabase_owner_file();

CREATE FUNCTION complete_supabase_owner_declaration() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT count(*) FROM supabase_owner_attested_files WHERE attestation_id=NEW.id) <> NEW.file_count THEN
        RAISE EXCEPTION 'Owner declaration file list is incomplete';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER complete_supabase_owner_attestation AFTER INSERT ON supabase_owner_attestations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION complete_supabase_owner_declaration();

CREATE FUNCTION keep_supabase_owner_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Owner attestation audit is append-only';
END;
$$;
CREATE TRIGGER immutable_supabase_owner_attestation BEFORE UPDATE OR DELETE ON supabase_owner_attestations
    FOR EACH ROW EXECUTE FUNCTION keep_supabase_owner_audit();
CREATE TRIGGER immutable_supabase_owner_attested_file BEFORE UPDATE OR DELETE ON supabase_owner_attested_files
    FOR EACH ROW EXECUTE FUNCTION keep_supabase_owner_audit();
CREATE TRIGGER immutable_supabase_owner_attestation_truncate BEFORE TRUNCATE ON supabase_owner_attestations
    FOR EACH STATEMENT EXECUTE FUNCTION keep_supabase_owner_audit();
CREATE TRIGGER immutable_supabase_owner_attested_file_truncate BEFORE TRUNCATE ON supabase_owner_attested_files
    FOR EACH STATEMENT EXECUTE FUNCTION keep_supabase_owner_audit();

-- Raw attempt outcomes remain unchanged, including UNKNOWN. Only a bound, complete owner declaration
-- permits this separate terminal state. This is not a verified remote completion or rollback receipt.
CREATE OR REPLACE FUNCTION guard_supabase_apply_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.project_id,NEW.target_key,NEW.connection_epoch,NEW.manifest_hash,NEW.idempotency_key,NEW.approved_by,NEW.created_at,NEW.owner_attestation_ids)
        IS DISTINCT FROM ROW(OLD.id,OLD.project_id,OLD.target_key,OLD.connection_epoch,OLD.manifest_hash,OLD.idempotency_key,OLD.approved_by,OLD.created_at,OLD.owner_attestation_ids) THEN
        RAISE EXCEPTION 'Migration approval is immutable';
    END IF;
    IF NEW.fence < OLD.fence OR (NEW.owner_id <> OLD.owner_id AND NEW.fence <= OLD.fence) THEN
        RAISE EXCEPTION 'Migration fence must advance';
    END IF;
    IF OLD.state IN ('COMPLETED','FAILED','ABORTED','COMPLETED_ATTESTED','RESOLVED_ATTESTED') AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION 'Migration operation is terminal';
    END IF;
    IF OLD.state = 'RECOVERY_REQUIRED' AND NEW.state <> OLD.state THEN
        IF NEW.state <> 'RESOLVED_ATTESTED' OR NEW.fence <= OLD.fence OR NEW.updated_at < OLD.lease_expires_at
            OR NOT EXISTS (
                SELECT 1 FROM supabase_owner_attestations r WHERE r.operation_id=OLD.id AND r.kind='RESOLUTION'
                    AND r.project_id=OLD.project_id AND r.target_key=OLD.target_key AND r.operation_fence=OLD.fence AND r.sender_quiescent
                    AND EXISTS (SELECT 1 FROM supabase_migration_attempts a WHERE a.operation_id=OLD.id AND a.state='UNKNOWN')
                    AND NOT EXISTS (SELECT 1 FROM supabase_migration_attempts a WHERE a.operation_id=OLD.id AND a.state='SENDING')
                    AND NOT EXISTS (SELECT 1 FROM supabase_migration_attempts a WHERE a.operation_id=OLD.id AND a.state='UNKNOWN'
                        AND NOT EXISTS (SELECT 1 FROM supabase_owner_attested_files f WHERE f.attestation_id=r.id
                            AND f.attempt_id=a.id AND f.name=a.name AND f.checksum=a.checksum))
                    AND NOT EXISTS (SELECT 1 FROM supabase_owner_attested_files f LEFT JOIN supabase_migration_attempts a ON a.id=f.attempt_id
                        WHERE f.attestation_id=r.id AND (a.id IS NULL OR a.operation_id<>OLD.id OR a.state<>'UNKNOWN'))
            ) THEN
            RAISE EXCEPTION 'Migration outcome requires a bound owner declaration';
        END IF;
    END IF;
    IF NEW.state='RESOLVED_ATTESTED' AND OLD.state NOT IN ('RECOVERY_REQUIRED','RESOLVED_ATTESTED') THEN
        RAISE EXCEPTION 'Only quarantined work can be owner-resolved';
    END IF;
    RETURN NEW;
END;
$$;
