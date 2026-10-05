-- A generated project's link to the user's own Supabase project. The anon key is public (it ships in the
-- site); the personal access token, used only by the backend to apply approved migrations, is stored
-- encrypted.
CREATE TABLE project_supabase_connections (
    project_id            uuid PRIMARY KEY,
    project_ref           varchar(40) NOT NULL,
    url                   varchar(200) NOT NULL,
    anon_key              varchar(2000) NOT NULL,
    access_token_cipher   varchar(4000),
    connected_by          bigint NOT NULL,
    created_at            timestamp(6) with time zone NOT NULL,
    updated_at            timestamp(6) with time zone NOT NULL
);

-- Migrations (supabase/migrations/*.sql in the project) the user approved and the backend applied.
CREATE TABLE project_supabase_migrations (
    id          uuid PRIMARY KEY,
    project_id  uuid NOT NULL,
    name        varchar(200) NOT NULL,
    checksum    varchar(80) NOT NULL,
    applied_by  bigint NOT NULL,
    applied_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT uq_project_supabase_migrations_name UNIQUE (project_id, name)
);
CREATE INDEX ix_project_supabase_migrations_project ON project_supabase_migrations (project_id);
