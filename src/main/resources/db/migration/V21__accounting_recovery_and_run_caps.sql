ALTER TABLE ai_token_attempts ADD COLUMN daily_quota bigint NOT NULL DEFAULT 0 CHECK (daily_quota >= 0);
ALTER TABLE ai_token_attempts ADD COLUMN bound_exceeded boolean NOT NULL DEFAULT false;
CREATE TABLE ai_run_budgets (
    run_id uuid PRIMARY KEY,
    user_id bigint NOT NULL,
    quota_tokens bigint NOT NULL CHECK (quota_tokens > 0),
    configured boolean NOT NULL DEFAULT false,
    charged_tokens bigint NOT NULL DEFAULT 0 CHECK (charged_tokens >= 0),
    reserved_tokens bigint NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0)
);
-- A violated verified ceiling suspends new requests until operator investigation; never auto-expire.
CREATE TABLE ai_accounting_blocks (
    provider varchar(40) NOT NULL,
    model varchar(160) NOT NULL,
    reason varchar(160) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(provider,model)
);
UPDATE ai_token_attempts a SET daily_quota=p.quota_tokens FROM ai_budget_periods p
WHERE p.user_id=a.user_id AND p.period_start=a.period_start;
INSERT INTO ai_run_budgets(run_id,user_id,quota_tokens,charged_tokens,reserved_tokens)
SELECT run_id,user_id,500000,
    SUM(CASE WHEN state='REPORTED' THEN prompt_tokens+completion_tokens WHEN state='UNKNOWN' THEN reserved_tokens ELSE 0 END),
    SUM(CASE WHEN state IN ('RESERVED','SENT') THEN reserved_tokens ELSE 0 END)
FROM ai_token_attempts WHERE run_id IS NOT NULL GROUP BY run_id,user_id;
