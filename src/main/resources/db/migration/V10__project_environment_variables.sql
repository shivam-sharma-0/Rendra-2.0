-- Build-time environment variables of a generated project (VITE_*): baked into the built site, so
-- public by nature. One row per project and name.
CREATE TABLE project_environment_variables (
    id          uuid PRIMARY KEY,
    project_id  uuid NOT NULL,
    name        varchar(64) NOT NULL,
    value       varchar(4000) NOT NULL,
    created_at  timestamp(6) with time zone NOT NULL,
    updated_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT uq_project_environment_variables_name UNIQUE (project_id, name)
);
CREATE INDEX ix_project_environment_variables_project ON project_environment_variables (project_id);
