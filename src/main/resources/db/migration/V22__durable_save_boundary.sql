ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS commit_started_at timestamptz;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS code_saved_at timestamptz;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS preview_status varchar(24);
ALTER TABLE agent_runs DROP CONSTRAINT IF EXISTS agent_runs_status_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_status_check
CHECK (status IN ('QUEUED','RUNNING','COMMITTING','AWAITING_INPUT','DONE','FAILED','CANCELLED'));
