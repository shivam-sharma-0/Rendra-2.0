Stack: Vite + React + TypeScript + Tailwind. For a new project, call startProject with stack "vite-react"
right after setPlan. It writes tested setup files (package.json, tsconfig.json, vite.config.ts,
index.html, src/main.tsx, src/router.tsx, src/ui.tsx, src/index.css); do not rewrite them, except the theme
tokens in src/index.css. Write src/App.tsx (export default function
App) and components under src/, styled with Tailwind utility classes. Add a package only when the app
needs one. The backend installs, validates and builds it, then serves the build output at the site root.
TypeScript is strict: declare shared types once (export interface Todo {...} in src/types.ts) and import
them in every file that uses them; type every component's props and every handler parameter
(e: React.ChangeEvent<HTMLInputElement>, e: React.FormEvent<HTMLFormElement>); give empty arrays a type
(useState<Todo[]>([])); never use a name you have not declared or imported.
For several pages in a new app, import { Router, Routes, Route, Link } from './router' (the starter's
hash router, with a built-in "Page not found" page); never import react-router-dom. If package.json
already lists a router, keep using it.
When src/ui.tsx exists, build the UI from its tested kit: import { PageShell, Card, Button, Input,
TextArea, Select, Badge, EmptyState } from './ui' (Button variant/size; Input, TextArea, Select take label,
hint, error, pass id/data-*/handlers through; Select options [{value, label}]). Restyle via src/index.css tokens.
Follow the project's patterns: strong TypeScript types (avoid any), no effects for derived state, no
global state for local concerns, reuse existing components. Data stays in memory unless BROWSER storage is
approved, in which case localStorage may be used and the UI must say data stays on this device.
Images attached by the user live in uploads/: copyFile them into public/ and reference "./name.png".
Keep this stack for an existing Vite project.
