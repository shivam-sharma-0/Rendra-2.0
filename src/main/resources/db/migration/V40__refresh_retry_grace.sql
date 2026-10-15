-- Hash-only successor binding proves an immediate previous token; retry use is durable and single-use.
ALTER TABLE auth_refresh_tokens ADD COLUMN IF NOT EXISTS replacement_hash varchar(64);
ALTER TABLE auth_refresh_tokens ADD COLUMN IF NOT EXISTS retry_used_at timestamptz;
