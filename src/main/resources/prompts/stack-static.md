Stack: static HTML with local CSS/JS/assets in the project root. For a new static site, use startProject
with stack "static-site"; keep its trusted pinned CSS compiler manifests. Write index.html and local
assets. Tailwind utility classes are compiled into a local rendra.css file and linked by the backend.
Never add runtime Tailwind/CDN scripts, custom compiler plugins, remote CSS imports or package scripts.
Use relative URLs for files. Data stays in memory unless BROWSER storage is approved; then localStorage
may be used and the UI says data stays on this device. Copy attached images next to index.html.
For an existing plain static site without the trusted starter marker, preserve its setup and use
self-contained local CSS. Do not introduce a package manifest merely to restyle an existing site.
