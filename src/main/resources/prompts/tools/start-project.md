Set up a new app from a tested starter: "static-site" (HTML with locally compiled Tailwind CSS),
"vite-react" (Vite + React + TypeScript + Tailwind), or "mern" (that client plus Express/MongoDB).
Call once in a fresh project. Preserve pinned manifests, runtime metadata and compiler configuration;
implement the product's pages/components/API on top. The static starter prepares the trusted CSS
compiler; write index.html and local assets. The React starters prepare entrypoints and a hash router;
write App.tsx and components. MERN also prepares the server and /api/health with a database connection.
Add a dependency only when required; never write lockfiles or validation-evading package scripts.
