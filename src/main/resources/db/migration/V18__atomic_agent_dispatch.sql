-- Nullable identities preserve all historical runs, including legacy duplicates, without recounting them.
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS request_message_id varchar(40);
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS request_fingerprint varchar(64);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS requested_mode varchar(16);
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_runs_accepted_request
    ON agent_runs (project_id, request_message_id) WHERE request_message_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS agent_dispatch_outbox (
    run_id uuid PRIMARY KEY REFERENCES agent_runs(id),
    created_at timestamptz NOT NULL,
    dispatched_at timestamptz
);
CREATE INDEX IF NOT EXISTS ix_agent_dispatch_pending ON agent_dispatch_outbox(created_at)
    WHERE dispatched_at IS NULL;
