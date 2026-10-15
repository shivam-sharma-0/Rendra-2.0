-- A possibly running trusted full-project command quarantines mutations until owned cleanup is proven.
create table agent_project_io (
    id uuid primary key,
    project_id uuid not null,
    container_name varchar(100) not null unique,
    daemon_id varchar(100) not null,
    created_at timestamptz not null default now(),
    finished_at timestamptz
);
create index agent_project_io_pending on agent_project_io(project_id) where finished_at is null;
