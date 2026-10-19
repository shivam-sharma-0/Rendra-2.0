-- Optional reported usage detail and price-versioned estimates. Token budget authority is unchanged.
ALTER TABLE agent_model_calls ADD COLUMN cached_input_tokens integer;
ALTER TABLE agent_model_calls ADD COLUMN reasoning_output_tokens integer;
ALTER TABLE agent_model_calls ADD COLUMN estimated_cost_usd numeric(30,12);
ALTER TABLE agent_model_calls ADD COLUMN cost_estimate_kind varchar(32);
ALTER TABLE agent_model_calls ADD COLUMN price_revision varchar(80);
ALTER TABLE agent_model_calls ADD CONSTRAINT ck_call_usage_details CHECK (
    (cached_input_tokens IS NULL OR (cached_input_tokens >= 0 AND cached_input_tokens <= prompt_tokens))
    AND (reasoning_output_tokens IS NULL OR (reasoning_output_tokens >= 0 AND reasoning_output_tokens <= completion_tokens))
    AND (estimated_cost_usd IS NULL OR estimated_cost_usd >= 0)
);
