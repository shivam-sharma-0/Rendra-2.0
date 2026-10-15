CREATE TABLE agent_build_contracts (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL UNIQUE REFERENCES agent_runs(id),
    project_id uuid NOT NULL REFERENCES projects(id),
    contract_json text NOT NULL,
    contract_hash varchar(64) NOT NULL,
    binding_json text NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('DRAFT','APPROVED','INVALIDATED')),
    approval_kind varchar(16) CHECK(approval_kind IN ('EXPLICIT_PLAN','BUILD_REQUEST')),
    approved_by bigint,
    approved_at timestamptz,
    invalidation_reason varchar(160),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK((state='APPROVED') = (approved_by IS NOT NULL AND approved_at IS NOT NULL) OR state='INVALIDATED')
);
CREATE INDEX ix_agent_build_contracts_project ON agent_build_contracts(project_id,created_at DESC);
CREATE TABLE agent_run_contracts (
    run_id uuid PRIMARY KEY REFERENCES agent_runs(id),
    project_id uuid NOT NULL REFERENCES projects(id),
    contract_id uuid NOT NULL REFERENCES agent_build_contracts(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
