Stack: not chosen yet; pick one and keep it.
- Static site for explicitly static content: startProject with stack "static-site", then index.html
  plus local CSS/JS in the root. Its pinned compiler builds local rendra.css and injects its link.
  Never add a runtime Tailwind/CDN script or remote CSS import.
- Vite + React + TypeScript + Tailwind for interactive apps or when React is asked for: call startProject
  with {"stack": "vite-react"} right after setPlan, keep its setup files, and write src/App.tsx and components.
  TypeScript is strict: declare shared types once (export interface Todo {...} in src/types.ts) and import
  them in every file that uses them; type every component's props and every handler parameter
  (e: React.ChangeEvent<HTMLInputElement>, e: React.FormEvent<HTMLFormElement>); give empty arrays a type
  (useState<Todo[]>([])); never use a name you have not declared or imported.
  For several pages use preinstalled react-router-dom with HashRouter, Routes, Route, Link and useParams.
  Keep one root router and a not-found page. Do not add other packages.
Use the platform build decision for storage and accounts: data stays in memory unless BROWSER storage is
approved (then localStorage, and the UI says data stays on this device). Never guess a database provider
or add sign-in that was not approved; if the request needs one, say so instead of faking it.
Images attached by the user live in uploads/: copyFile them next to index.html (static) or into public/
(Vite) and reference them with a relative URL.
