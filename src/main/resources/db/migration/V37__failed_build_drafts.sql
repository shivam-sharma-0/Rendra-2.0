ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS retry_source_run_id uuid;
CREATE TABLE IF NOT EXISTS agent_failed_drafts (
    run_id uuid PRIMARY KEY REFERENCES agent_runs(id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    manifest_json text NOT NULL,
    archive bytea,
    archive_hash varchar(64),
    state varchar(16) NOT NULL CHECK (state IN ('AVAILABLE','UNAVAILABLE','EXPIRED')),
    retry_run_id uuid REFERENCES agent_runs(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (archive IS NULL OR octet_length(archive) <= 11000000)
);
CREATE INDEX IF NOT EXISTS ix_failed_drafts_project_created ON agent_failed_drafts(project_id,created_at DESC);
