-- One durable refund per failed structured model attempt that provably produced no completion.
CREATE TABLE IF NOT EXISTS agent_model_call_refunds (
    run_id uuid NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    logical_id uuid NOT NULL,
    ordinal int NOT NULL CHECK (ordinal BETWEEN 1 AND 3),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, logical_id, ordinal)
);
