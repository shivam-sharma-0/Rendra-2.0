ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS save_recovery_required boolean NOT NULL DEFAULT false;
UPDATE agent_runs SET save_recovery_required=false WHERE save_recovery_required IS NULL;
ALTER TABLE agent_runs ALTER COLUMN save_recovery_required SET DEFAULT false;
ALTER TABLE agent_runs ALTER COLUMN save_recovery_required SET NOT NULL;
-- An unfinished save quarantines the project even if a stale status write said FAILED.
CREATE TABLE agent_promotions (
    run_id uuid PRIMARY KEY REFERENCES agent_runs(id),
    project_id uuid NOT NULL,
    owner_id uuid,
    lease_epoch bigint NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    files_json text NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN
        ('PREPARING','READY','APPLYING','ROLLING_BACK','RECOVERY_REQUIRED','ROLLED_BACK','SAVED')),
    saved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((state = 'SAVED') = (saved_at IS NOT NULL))
);
CREATE INDEX ix_agent_promotions_pending ON agent_promotions(project_id)
    WHERE state NOT IN ('SAVED','ROLLED_BACK');
