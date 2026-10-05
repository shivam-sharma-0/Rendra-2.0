-- The agent can copy a file (an uploaded image into public/, for example) within its change set.
ALTER TABLE agent_file_changes DROP CONSTRAINT agent_file_changes_operation_check;
ALTER TABLE agent_file_changes ADD CONSTRAINT agent_file_changes_operation_check
    CHECK (operation IN ('CREATE', 'PATCH', 'DELETE', 'RENAME', 'COPY'));
