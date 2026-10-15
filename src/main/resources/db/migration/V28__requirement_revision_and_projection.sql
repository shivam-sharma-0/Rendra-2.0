ALTER TABLE requirement_sessions ADD COLUMN IF NOT EXISTS answer_revision bigint NOT NULL DEFAULT 0 CHECK (answer_revision >= 0);
ALTER TABLE requirement_sessions ADD COLUMN IF NOT EXISTS approved_revision bigint;
ALTER TABLE requirement_sessions ADD COLUMN IF NOT EXISTS projection_id uuid;

UPDATE requirement_sessions SET answer_revision=0 WHERE answer_revision IS NULL;
ALTER TABLE requirement_sessions ALTER COLUMN answer_revision SET DEFAULT 0;
ALTER TABLE requirement_sessions ALTER COLUMN answer_revision SET NOT NULL;

CREATE TABLE requirement_llm_work (
    session_id uuid NOT NULL REFERENCES requirement_sessions(id),
    revision bigint NOT NULL CHECK (revision >= 0),
    purpose varchar(16) NOT NULL CHECK (purpose IN ('QUESTIONS','ASSESSMENT')),
    owner_id uuid NOT NULL,
    user_id bigint NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('RUNNING','FALLBACK','DONE')),
    expires_at timestamptz NOT NULL,
    outcome varchar(16),
    PRIMARY KEY (session_id,revision,purpose)
);

CREATE TABLE requirement_projections (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES requirement_sessions(id),
    project_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 0),
    payload_json text NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','READY','SUPERSEDED')),
    error_code varchar(80),
    retry_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(session_id,revision)
);
ALTER TABLE requirement_sessions ADD CONSTRAINT requirement_projection_fk FOREIGN KEY (projection_id) REFERENCES requirement_projections(id);
CREATE INDEX requirement_projection_pending ON requirement_projections(retry_at) WHERE state='PENDING';
