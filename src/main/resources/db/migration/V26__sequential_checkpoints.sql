CREATE TABLE agent_execution_progress (
    run_id uuid PRIMARY KEY REFERENCES agent_runs(id),
    iterations integer NOT NULL DEFAULT 0 CHECK (iterations >= 0),
    model_calls integer NOT NULL DEFAULT 0 CHECK (model_calls >= 0),
    tool_calls integer NOT NULL DEFAULT 0 CHECK (tool_calls >= 0),
    iteration_limit integer NOT NULL CHECK (iteration_limit > 0),
    model_call_limit integer NOT NULL CHECK (model_call_limit > 0),
    tool_call_limit integer NOT NULL CHECK (tool_call_limit > 0),
    deadline_at timestamptz NOT NULL,
    resume_count integer NOT NULL DEFAULT 0 CHECK (resume_count BETWEEN 0 AND 1)
);
CREATE TABLE agent_checkpoints (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES agent_runs(id),
    revision integer NOT NULL CHECK (revision > 0),
    checkpoint_json text NOT NULL,
    manifest_sha256 varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(run_id, revision)
);
