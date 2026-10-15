CREATE TABLE agent_code_restores (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES agent_runs(id),
    project_id uuid NOT NULL REFERENCES projects(id),
    backup_root text NOT NULL,
    state varchar(24) NOT NULL CHECK(state IN ('PREPARING','APPLYING','RESTORED','ROLLED_BACK','RECOVERY_REQUIRED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_agent_code_restores_pending ON agent_code_restores(project_id)
    WHERE state NOT IN ('RESTORED','ROLLED_BACK');
