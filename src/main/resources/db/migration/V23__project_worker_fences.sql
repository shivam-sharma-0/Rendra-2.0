-- Redis is dispatch coordination; this durable generation owns trusted mutations.
create table agent_project_leases (
    project_id uuid primary key,
    epoch bigint not null check (epoch > 0),
    run_id uuid not null,
    owner_id uuid not null,
    expires_at timestamptz not null
);
create index agent_project_leases_expiry on agent_project_leases(expires_at);
