ALTER TABLE projects ADD COLUMN IF NOT EXISTS cleanup_completed_at timestamptz;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS cleanup_attempted_at timestamptz;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS failure_reason varchar(48);
CREATE INDEX IF NOT EXISTS ix_projects_pending_cleanup ON projects((coalesce(cleanup_attempted_at,deleted_at)),id)
    WHERE deleted_at IS NOT NULL AND cleanup_completed_at IS NULL;
