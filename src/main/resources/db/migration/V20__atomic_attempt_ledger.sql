ALTER TABLE ai_token_attempts ADD COLUMN IF NOT EXISTS counted_in_run boolean NOT NULL DEFAULT false;
ALTER TABLE agent_model_calls ADD COLUMN IF NOT EXISTS usage_known boolean;
