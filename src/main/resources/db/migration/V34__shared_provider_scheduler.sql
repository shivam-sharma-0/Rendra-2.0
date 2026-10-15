CREATE TABLE agent_scheduler_config (id boolean PRIMARY KEY DEFAULT true CHECK(id), fingerprint varchar(64) NOT NULL);
CREATE TABLE agent_scheduler_user_turns (user_id bigint PRIMARY KEY, admitted_at timestamptz NOT NULL);
CREATE TABLE agent_provider_slots (
    id uuid PRIMARY KEY, provider varchar(48) NOT NULL, account_pool varchar(80) NOT NULL,
    user_id bigint NOT NULL, run_id uuid,
    state varchar(16) NOT NULL CHECK(state IN ('WAITING','ACTIVE','RELEASED','CANCELLED','UNKNOWN')),
    queued_at timestamptz NOT NULL DEFAULT clock_timestamp(), wait_until timestamptz NOT NULL,
    finished_at timestamptz
);
CREATE INDEX agent_provider_slot_active ON agent_provider_slots(state,account_pool,user_id,run_id);
