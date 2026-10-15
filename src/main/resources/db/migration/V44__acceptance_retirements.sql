-- Acceptance checks a follow-up run retired because the user changed or removed that feature.
CREATE TABLE IF NOT EXISTS agent_acceptance_retirements (
    run_id uuid NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    action_id varchar(64) NOT NULL,
    reason varchar(300) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, action_id)
);
CREATE INDEX IF NOT EXISTS ix_acceptance_retirements_project ON agent_acceptance_retirements(project_id, created_at);
