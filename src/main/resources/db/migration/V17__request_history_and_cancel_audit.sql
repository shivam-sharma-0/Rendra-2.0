-- Earlier dev Hibernate updates added these entity fields without a Flyway migration.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS seq bigint NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS feedback varchar(8);
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS conversational boolean NOT NULL DEFAULT false;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS responding_at timestamp(6) with time zone;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS cancel_requested_by bigint;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS cancel_requested_at timestamp(6) with time zone;
CREATE INDEX IF NOT EXISTS ix_messages_project_request_order ON messages (project_id, created_at, seq);
