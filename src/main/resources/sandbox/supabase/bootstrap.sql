CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE service_role NOLOGIN BYPASSRLS;
CREATE ROLE authenticator NOINHERIT LOGIN PASSWORD '{{password}}';
GRANT anon, authenticated, service_role TO authenticator;
CREATE ROLE supabase_auth_admin LOGIN CREATEROLE PASSWORD '{{password}}';
CREATE SCHEMA auth AUTHORIZATION supabase_auth_admin;
CREATE SCHEMA extensions;
GRANT USAGE ON SCHEMA extensions TO supabase_auth_admin;
ALTER ROLE supabase_auth_admin SET search_path = auth, public, extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE ROLE validation_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{{password}}';
GRANT CREATE ON DATABASE postgres TO validation_owner;
GRANT USAGE, CREATE ON SCHEMA public TO validation_owner;
GRANT USAGE ON SCHEMA auth, extensions TO validation_owner;
ALTER DEFAULT PRIVILEGES FOR ROLE validation_owner IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE validation_owner IN SCHEMA public GRANT ALL ON SEQUENCES TO anon, authenticated, service_role;
GRANT USAGE ON SCHEMA public, auth TO anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO anon, authenticated, service_role;
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
  SELECT coalesce(nullif(current_setting('request.jwt.claim.sub', true), ''),
    nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')::uuid;
$$;
CREATE FUNCTION auth.role() RETURNS text LANGUAGE sql STABLE AS $$
  SELECT coalesce(nullif(current_setting('request.jwt.claim.role', true), ''),
    nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role');
$$;
CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE AS $$
  SELECT nullif(current_setting('request.jwt.claims', true), '')::jsonb;
$$;
ALTER FUNCTION auth.uid() OWNER TO supabase_auth_admin;
ALTER FUNCTION auth.role() OWNER TO supabase_auth_admin;
ALTER FUNCTION auth.jwt() OWNER TO supabase_auth_admin;
