-- Single accounting authority. Runtime switches to this projection only after atomic reserve/settle.
CREATE TABLE ai_budget_periods (
    user_id bigint NOT NULL,
    period_start date NOT NULL,
    quota_tokens bigint NOT NULL DEFAULT 0 CHECK (quota_tokens >= 0),
    charged_tokens bigint NOT NULL DEFAULT 0 CHECK (charged_tokens >= 0),
    reserved_tokens bigint NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
    known_tokens bigint NOT NULL DEFAULT 0 CHECK (known_tokens >= 0),
    unknown_tokens bigint NOT NULL DEFAULT 0 CHECK (unknown_tokens >= 0),
    unknown_calls bigint NOT NULL DEFAULT 0 CHECK (unknown_calls >= 0),
    PRIMARY KEY(user_id, period_start)
);

CREATE TABLE ai_legacy_usage (
    source_key varchar(100) PRIMARY KEY,
    user_id bigint NOT NULL,
    project_id uuid,
    run_id uuid,
    period_start date NOT NULL,
    known_tokens bigint NOT NULL CHECK (known_tokens >= 0),
    unknown_tokens bigint NOT NULL CHECK (unknown_tokens >= 0),
    unknown_calls bigint NOT NULL CHECK (unknown_calls >= 0)
);

-- Count each legacy ledger row once, whether or not a run displayed its tokens.
-- Old zero usage cannot distinguish absent usage from actual zero: retain available estimates honestly.
INSERT INTO ai_legacy_usage(source_key,user_id,project_id,run_id,period_start,known_tokens,unknown_tokens,unknown_calls)
SELECT 'call:' || id,user_id,project_id,run_id,(created_at AT TIME ZONE 'UTC')::date,
       GREATEST(0,prompt_tokens::bigint) + GREATEST(0,completion_tokens::bigint),
       CASE WHEN prompt_tokens = 0 AND completion_tokens = 0 AND outcome <> 'REFUSED'
            THEN GREATEST(0,estimated_input_tokens::bigint) + GREATEST(0,COALESCE(max_tokens,0)::bigint) ELSE 0 END,
       CASE WHEN prompt_tokens = 0 AND completion_tokens = 0 AND outcome <> 'REFUSED' THEN 1 ELSE 0 END
FROM agent_model_calls WHERE user_id IS NOT NULL;

-- Run totals supplement only the reported tokens absent from that run's counted ledger rows.
-- Ledger calls keep their actual call day; only historical gaps must use the run's creation day.
WITH reported AS (
    SELECT run_id,SUM(GREATEST(0,prompt_tokens::bigint) + GREATEST(0,completion_tokens::bigint)) AS tokens
    FROM agent_model_calls WHERE counted_in_run = true GROUP BY run_id
)
INSERT INTO ai_legacy_usage(source_key,user_id,project_id,run_id,period_start,known_tokens,unknown_tokens,unknown_calls)
SELECT 'run:' || r.id,r.user_id,r.project_id,r.id,(r.created_at AT TIME ZONE 'UTC')::date,
       GREATEST(0,GREATEST(0,r.prompt_tokens::bigint) + GREATEST(0,r.completion_tokens::bigint) - COALESCE(c.tokens,0)),0,0
FROM agent_runs r LEFT JOIN reported c ON c.run_id = r.id;

INSERT INTO ai_budget_periods(user_id,period_start,charged_tokens,known_tokens,unknown_tokens,unknown_calls)
SELECT user_id,period_start,SUM(known_tokens + unknown_tokens),SUM(known_tokens),SUM(unknown_tokens),SUM(unknown_calls)
FROM ai_legacy_usage GROUP BY user_id,period_start;

CREATE TABLE ai_token_attempts (
    id uuid PRIMARY KEY,
    logical_call_id uuid NOT NULL,
    user_id bigint NOT NULL,
    project_id uuid,
    run_id uuid,
    purpose varchar(24) NOT NULL,
    provider varchar(40) NOT NULL,
    model varchar(160) NOT NULL,
    period_start date NOT NULL,
    input_bound bigint NOT NULL CHECK (input_bound >= 0),
    max_output_tokens integer NOT NULL CHECK (max_output_tokens > 0),
    reserved_tokens bigint NOT NULL CHECK (reserved_tokens > 0),
    state varchar(16) NOT NULL CHECK (state IN ('RESERVED','SENT','REPORTED','UNKNOWN','NOT_SENT')),
    prompt_tokens bigint CHECK (prompt_tokens >= 0),
    completion_tokens bigint CHECK (completion_tokens >= 0),
    created_at timestamptz NOT NULL,
    sent_at timestamptz,
    settled_at timestamptz,
    FOREIGN KEY(user_id,period_start) REFERENCES ai_budget_periods(user_id,period_start)
);
CREATE INDEX ix_ai_attempts_logical ON ai_token_attempts(logical_call_id);
CREATE INDEX ix_ai_attempts_run ON ai_token_attempts(run_id);
CREATE INDEX ix_ai_attempts_unsettled ON ai_token_attempts(created_at) WHERE state IN ('RESERVED','SENT');
ALTER TABLE agent_model_calls ADD COLUMN IF NOT EXISTS attempt_id uuid;
ALTER TABLE agent_model_calls ADD COLUMN IF NOT EXISTS logical_call_id uuid;
CREATE UNIQUE INDEX uq_model_call_attempt ON agent_model_calls(attempt_id) WHERE attempt_id IS NOT NULL;
