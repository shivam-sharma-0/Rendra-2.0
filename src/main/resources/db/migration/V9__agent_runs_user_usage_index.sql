-- Per-user usage limits sum a user's tokens since the start of the UTC day and count their active runs.
CREATE INDEX IF NOT EXISTS ix_agent_runs_user_created ON agent_runs (user_id, created_at);
