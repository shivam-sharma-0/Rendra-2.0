-- The spec drafted by the requirements call, assuming each question's recommended answer.
ALTER TABLE requirement_sessions ADD COLUMN IF NOT EXISTS spec_draft_json text;
