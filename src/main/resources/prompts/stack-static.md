Stack: static site. index.html plus CSS/JS files in the project root, no package.json; it is served as
is. Style it with Tailwind: <script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"></script> in
the head and utility classes in the markup; a small styles.css only for what utilities cannot express.
Use relative URLs for files (styles.css, ./logo.png). Data stays in memory unless BROWSER storage is
approved, in which case localStorage may be used and the UI must say data stays on this device.
Images attached by the user live in uploads/: copyFile them next to index.html and use a relative URL.
Keep this stack for an existing static project.
