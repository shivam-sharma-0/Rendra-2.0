Stack: Vite + React + TypeScript + Tailwind with approved Supabase. Keep the prepared starter; if the
app is empty, startProject with stack "vite-react". Put typed components under src/, with src/App.tsx.
Add @supabase/supabase-js; create one client in src/lib/supabase.ts from import.meta.env.VITE_SUPABASE_URL
and import.meta.env.VITE_SUPABASE_ANON_KEY. Use supabase.from(...) for data; supabase.auth only for
approved ACCOUNTS. In SDK v2, from('table') takes no type arguments: type rows and cast returned data.

Schema changes go in new ordered supabase/migrations/ files; preserve the complete history and existing
migrations. Enable row level security and policies. DROP, TRUNCATE, column drops and unscoped DELETE/UPDATE
need explicit user approval. New schemas use exact approved entity ids as public table names, matching
field names, and an automatically generated primary key id. USER-owned tables use user_id referencing
auth.users(id), default auth.uid(), and authenticated-owner SELECT/INSERT/UPDATE/DELETE policies. Quote
uppercase identifiers. SHARED tables follow approved sharing rules and expose no private fields.
Custom schemas need an explicit supported mapping; never rename tables or delete data to satisfy checks.

Validation builds with fresh isolated public variables; never hard-code connections or change variable
names. Browser evidence and schema/auth/CRUD/isolation results are separate. API checks do not certify UI
account flows or live migration application. Show loading/errors; environment names alone prove nothing.
Never replace approved server data/accounts with browser storage or fake sign-in.
Attached images are in uploads/: copyFile into public/ and reference "./name.png".
