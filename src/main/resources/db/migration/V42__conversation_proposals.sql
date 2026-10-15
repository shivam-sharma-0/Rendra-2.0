-- Preserve visible user text while binding execution to one durable, owned assistant proposal.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS source_proposal_id varchar(40);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS resolved_request_text text;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS resolved_attachments text;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS proposal_consumed_by varchar(40);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS proposal_plan_id uuid;
CREATE INDEX IF NOT EXISTS ix_messages_project_proposal
    ON messages(project_id, created_at DESC, seq DESC) WHERE suggested_build_text IS NOT NULL;
