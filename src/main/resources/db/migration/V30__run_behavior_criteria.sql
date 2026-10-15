CREATE TABLE agent_behavior_criteria (
    run_id uuid PRIMARY KEY REFERENCES agent_runs(id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    requirements_projection_id uuid NOT NULL REFERENCES requirement_projections(id),
    requirements_revision bigint NOT NULL CHECK(requirements_revision > 0),
    criteria_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
