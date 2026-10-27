For this fresh MERN application leave every unit imports array empty. The coordinator supplies shared interfaces
and all files owned by declared dependency units automatically; describe those relationships with dependsOn.
Typical split: one server unit per router (e.g. todos_api owning server/src/routers/todos.ts, owner of all its api
entries) plus client units owning pages/components. Pages call the coordinator api client and need no dependsOn on server units.
Use full project-relative file paths starting client/ or server/, with portable letters, digits, _, -, / and . only.
Never use ./, ../, angle-bracket placeholders, filesystem route brackets or absolute paths in file metadata.
Use exact shared module paths from the supplied snapshot when writing code, including their actual UUID.
