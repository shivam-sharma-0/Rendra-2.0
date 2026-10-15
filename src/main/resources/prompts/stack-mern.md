Stack: MERN (React + Express + MongoDB), approved by the platform build decision. For a new project, call
startProject with stack "mern" right after setPlan. It writes the Vite client in client/ (with /api proxied
to the server in development) and server/ with Express in TypeScript: server/src/index.ts uses
express.json(), answers GET /api/health, listens on process.env.PORT and connects mongoose to
process.env.MONGODB_URI. Write client/src/App.tsx and call the API with relative URLs under /api
(fetch("/api/todos")). Put routes under /api in server/src, registered in server/src/index.ts, with their
mongoose models. The backend builds and runs the server and gives it its own MongoDB; never hard-code a
connection string or credentials, and never replace server data or real accounts with browser state.
Images attached by the user live in uploads/: copyFile them into client/public/ and reference "./name.png".
