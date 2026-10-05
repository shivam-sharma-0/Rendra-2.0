-- Project files (uploads/...) a user attached to a message, one path per line; the agent reads them.
ALTER TABLE messages ADD COLUMN attachments text;
