Stack: MERN (React + Express + MongoDB), approved by the platform build decision. For a new project, call
startProject with stack "mern" right after setPlan. It writes the Vite client in client/ (with /api proxied
to the server in development) and server/ with Express in TypeScript: server/src/index.ts uses
express.json(), answers GET /api/health, listens on process.env.PORT and connects mongoose to
process.env.MONGODB_URI. Write client/src/App.tsx and call the API with relative URLs under /api
(fetch("/api/todos")). Put routes under /api in server/src, registered in server/src/index.ts, with their
mongoose models. The backend builds and runs the server and gives it its own MongoDB; never hard-code a
connection string or credentials, and never replace server data or real accounts with browser state.
Images attached by the user live in uploads/: copyFile them into client/public/ and reference "./name.png".
An app the platform already built keeps its account, ownership and API scaffold in client/src/rendra/<id>/ and
server/src/rendra/<id>/. Read and import it (api.ts, auth-client.ts, types.ts, theme.css) but never edit it: those
files are read-only. Change the app's own files (client/src/pages, components, App.tsx, server/src/routers) with
patchFile, and only the ones the request needs. Keep the existing data-* attributes, input names and links that the
saved checks use. A new router is mounted in server/src/index.ts behind the account checks the scaffold exports:
import { requireAccount } from './rendra/<id>/auth.js' and { bindRecordOwner } from './rendra/<id>/ownership.js',
then app.use('/api/<name>', requireAccount, bindRecordOwner, router); define its mongoose models with ownedSchema and
ownedModel from that ownership.js so every record belongs to the signed-in account.
