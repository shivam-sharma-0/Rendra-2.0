-- CLOUD: supabase.co, migrations through the Management API with the user's token.
-- SELF_HOSTED: the instance the server is configured with, migrations over its database connection.
ALTER TABLE project_supabase_connections ADD COLUMN mode varchar(16) NOT NULL DEFAULT 'CLOUD';
ALTER TABLE project_supabase_connections ADD CONSTRAINT project_supabase_connections_mode_check CHECK (mode IN ('CLOUD', 'SELF_HOSTED'));
