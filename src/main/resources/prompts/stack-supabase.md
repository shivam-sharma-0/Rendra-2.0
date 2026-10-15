Stack: Vite + React + TypeScript + Tailwind with Supabase (approved by the platform build decision). For a
new project, call startProject with stack "vite-react" right after setPlan; do not rewrite its setup files.
Write src/App.tsx and components under src/ with strong TypeScript types.
Supabase: add @supabase/supabase-js and create the client once in src/lib/supabase.ts from
import.meta.env.VITE_SUPABASE_URL and import.meta.env.VITE_SUPABASE_ANON_KEY. Use supabase.from(...) for
data and supabase.auth only if ACCOUNTS is approved. supabase.from('table') takes no type arguments in
supabase-js v2: type rows with an interface and cast the result (data as Entry[] | null).
Put each schema change in a new file under supabase/migrations/ (never edit an existing one): create
tables, enable row level security and add policies (auth.uid() for per-user rows). Prefer additive changes:
DROP, TRUNCATE, column drops and DELETE/UPDATE without WHERE need the user's explicit approval.
Show loading and error states instead of assuming tables exist. Environment variable names alone do not prove a connection; never
replace Supabase data or real accounts with browser storage or fake sign-in.
Images attached by the user live in uploads/: copyFile them into public/ and reference "./name.png".
