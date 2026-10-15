-- Read-only project analytics use accounting authority, including requirements/retries/unknown usage.
CREATE INDEX ix_ai_attempts_project ON ai_token_attempts(project_id);
CREATE INDEX ix_ai_legacy_project ON ai_legacy_usage(project_id);
