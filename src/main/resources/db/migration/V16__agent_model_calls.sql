-- The call ledger: one row per model call (who, why, which model, tokens, duration, outcome); no prompts or replies.
-- purpose and outcome are plain text so new values need no migration.
CREATE TABLE IF NOT EXISTS agent_model_calls (
    id uuid PRIMARY KEY,
    user_id bigint,
    project_id uuid,
    run_id uuid,
    purpose varchar(24) NOT NULL,
    provider varchar(40),
    model varchar(160),
    prompt_tokens integer NOT NULL DEFAULT 0,
    completion_tokens integer NOT NULL DEFAULT 0,
    estimated_input_tokens integer NOT NULL DEFAULT 0,
    max_tokens integer,
    duration_ms integer NOT NULL DEFAULT 0,
    outcome varchar(16) NOT NULL,
    error varchar(300),
    counted_in_run boolean NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_agent_model_calls_user_created ON agent_model_calls (user_id, created_at);
CREATE INDEX IF NOT EXISTS ix_agent_model_calls_run ON agent_model_calls (run_id);
