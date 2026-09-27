-- Per-project sharing: grant a specific user access to a single project with a role.
CREATE TABLE project_members (
    id          UUID PRIMARY KEY,
    project_id  UUID   NOT NULL,
    user_id     BIGINT NOT NULL,
    role        VARCHAR(16) NOT NULL DEFAULT 'VIEWER',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_member UNIQUE (project_id, user_id)
);

CREATE INDEX ix_projectmember_user ON project_members (user_id);
