CREATE TABLE agent_unit_outputs (
    id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES agent_runs(id),
    contract_id uuid NOT NULL REFERENCES agent_build_contracts(id), unit_id varchar(48) NOT NULL,
    request_fingerprint varchar(64) NOT NULL, context_hash varchar(64) NOT NULL,
    output_json text NOT NULL, output_hash varchar(64) NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('GENERATED','VERIFIED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_unit_output_latest ON agent_unit_outputs(run_id,unit_id,created_at DESC,id DESC);
