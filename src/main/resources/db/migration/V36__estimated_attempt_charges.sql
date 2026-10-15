-- Keep reservation estimates separate from request limits and unknown charges.
ALTER TABLE ai_token_attempts ADD COLUMN input_limit bigint;
ALTER TABLE ai_token_attempts ADD COLUMN unknown_charge_tokens bigint;
UPDATE ai_token_attempts SET input_limit=input_bound;
UPDATE ai_token_attempts SET unknown_charge_tokens=reserved_tokens WHERE state='UNKNOWN';
ALTER TABLE ai_token_attempts ALTER COLUMN input_limit SET NOT NULL;
ALTER TABLE ai_token_attempts ADD CONSTRAINT attempt_input_limit_nonnegative CHECK(input_limit>=0);
ALTER TABLE ai_token_attempts ADD CONSTRAINT attempt_unknown_charge_nonnegative CHECK(unknown_charge_tokens>=0);
