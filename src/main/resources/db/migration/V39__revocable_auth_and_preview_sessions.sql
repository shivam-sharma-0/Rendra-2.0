CREATE TABLE IF NOT EXISTS auth_sessions (
    id uuid PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS auth_refresh_tokens (
    token_hash varchar(64) PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE INDEX IF NOT EXISTS ix_auth_sessions_expiry ON auth_sessions(expires_at);
CREATE INDEX IF NOT EXISTS ix_auth_refresh_session ON auth_refresh_tokens(session_id);
CREATE TABLE IF NOT EXISTS preview_grants (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id uuid NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
    draft_run_id uuid REFERENCES agent_runs(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS ix_preview_grants_binding ON preview_grants(project_id,session_id,expires_at);
