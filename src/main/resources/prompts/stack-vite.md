Stack: Vite + React + TypeScript + Tailwind. For a new project, call startProject with stack "vite-react"
right after setPlan. It writes tested setup files (package.json, tsconfig.json, vite.config.ts,
index.html, src/main.tsx, src/index.css); do not rewrite them. Write src/App.tsx (export default function
App) and components under src/, styled with Tailwind utility classes. Use only preinstalled packages. The backend installs, validates and builds it, then serves the build output at the site root.
TypeScript is strict: declare shared types once (export interface Todo {...} in src/types.ts) and import
them in every file that uses them; type every component's props and every handler parameter
(e: React.ChangeEvent<HTMLInputElement>, e: React.FormEvent<HTMLFormElement>); give empty arrays a type
(useState<Todo[]>([])); never use a name you have not declared or imported.
Keep runtime imports at module scope. Import useState/useEffect by name before using them; React.useState
needs a declared React namespace import. Reuse existing state and handlers rather than appending copies.
For several pages use preinstalled react-router-dom: HashRouter, Routes, Route, Link, useParams.
Keep one router at the app root and a not-found route; links use to="/path". Hash routing preserves preview prefixes.
Follow the project's patterns: strong TypeScript types (avoid any), no effects for derived state, no
global state for local concerns, reuse existing components. Data stays in memory unless BROWSER storage is
approved, in which case localStorage may be used and the UI must say data stays on this device.
Images attached by the user live in uploads/: copyFile them into public/ and reference "./name.png".
Keep this stack for an existing Vite project.
