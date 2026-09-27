CREATE TABLE agent_file_changes (
    id              uuid PRIMARY KEY,
    run_id          uuid NOT NULL,
    project_id      uuid NOT NULL,
    path            varchar(1024) NOT NULL,
    source_path     varchar(1024),
    operation       varchar(16) NOT NULL,
    before_version  varchar(80) NOT NULL,
    after_version   varchar(80) NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL,
    CONSTRAINT agent_file_changes_operation_check CHECK (operation IN ('CREATE', 'PATCH', 'DELETE', 'RENAME'))
);
CREATE INDEX ix_agent_file_changes_run_created ON agent_file_changes (run_id, created_at);
