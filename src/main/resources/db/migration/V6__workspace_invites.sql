-- Shareable workspace invite links (accept flow).
CREATE TABLE workspace_invites (
    id                  UUID PRIMARY KEY,
    token               VARCHAR(64)  NOT NULL,
    workspace_id        UUID         NOT NULL,
    email               VARCHAR(255),
    role                VARCHAR(16)  NOT NULL DEFAULT 'MEMBER',
    invited_by_user_id  BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    accepted_by_user_id BIGINT,
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_invite_token UNIQUE (token)
);

CREATE INDEX ix_invite_workspace ON workspace_invites (workspace_id);
