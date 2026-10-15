-- Idempotent contract deadline reduction across bounded worker recovery.
ALTER TABLE agent_execution_progress ADD COLUMN started_at timestamptz NOT NULL DEFAULT clock_timestamp();
