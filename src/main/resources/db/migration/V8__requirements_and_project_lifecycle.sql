ALTER TABLE projects ADD COLUMN lifecycle_stage varchar(20) NOT NULL DEFAULT 'DESIGN';
ALTER TABLE projects ADD CONSTRAINT projects_lifecycle_stage_check CHECK (lifecycle_stage IN ('REQUIREMENTS', 'DESIGN', 'BUILDING', 'REVIEW', 'DEPLOYING', 'LIVE', 'FAILED'));

CREATE TABLE requirement_sessions (
    id          uuid PRIMARY KEY,
    project_id  uuid NOT NULL UNIQUE,
    status      varchar(24) NOT NULL,
    source_prompt text NOT NULL,
    approved_at timestamp(6) with time zone,
    approved_by bigint,
    created_at  timestamp(6) with time zone NOT NULL,
    updated_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT requirement_sessions_status_check CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'APPROVAL_REQUIRED', 'APPROVED'))
);
CREATE INDEX ix_requirement_sessions_project ON requirement_sessions (project_id);

CREATE TABLE requirement_questions (
    id                 uuid PRIMARY KEY,
    session_id         uuid NOT NULL,
    sequence_no        integer NOT NULL,
    question_key       varchar(80) NOT NULL,
    prompt             text NOT NULL,
    type               varchar(20) NOT NULL,
    required           boolean NOT NULL,
    options_json       text NOT NULL,
    recommended_option varchar(80),
    selected_option    varchar(80),
    other_text         text,
    created_at         timestamp(6) with time zone NOT NULL,
    updated_at         timestamp(6) with time zone NOT NULL,
    CONSTRAINT requirement_questions_type_check CHECK (type IN ('SINGLE_SELECT', 'MULTI_SELECT', 'TEXT')),
    CONSTRAINT uq_requirement_question_sequence UNIQUE (session_id, sequence_no)
);
CREATE INDEX ix_requirement_questions_session_seq ON requirement_questions (session_id, sequence_no);
