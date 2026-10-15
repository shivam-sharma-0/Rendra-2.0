import fs from 'node:fs/promises'
import { realpathSync } from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'

// Executed as backend-owned --eval code in a disposable build copy, never from project scripts.
const root = process.cwd()
const require = createRequire(pathToFileURL(path.join(root, 'package.json')))
const { compile } = require('@tailwindcss/node')
const { Scanner } = require('@tailwindcss/oxide')
const marker = JSON.parse(await fs.readFile(path.join(root, 'starter-static.json'), 'utf8'))
if (marker.schemaVersion !== 1 || !/^[a-f0-9-]{36}$/.test(marker.contractId)) throw Error('Invalid static asset identity')
const dependencyRoot = await fs.realpath(path.join(root, 'node_modules'))
const allowed = new Set(['.html', '.css', '.js', '.mjs', '.json', '.svg', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.woff2'])
const excluded = new Set(['node_modules', 'dist', 'build', 'package.json', 'package-lock.json', 'starter-static.json', 'starter-runtime.json'])
const sources = []
let total = 0
async function collect(directory, relative = '') {
  for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
    if (entry.name.startsWith('.') || excluded.has(entry.name)) continue
    const name = relative ? `${relative}/${entry.name}` : entry.name
    if (name.startsWith('rendra/') && name !== `rendra/${marker.contractId}/theme.css`
        && !`rendra/${marker.contractId}/theme.css`.startsWith(`${name}/`)) continue
    if (entry.isSymbolicLink()) throw Error('Static artifact contains a symlink')
    const absolute = path.join(directory, entry.name)
    if (entry.isDirectory()) { await collect(absolute, name); continue }
    if (!entry.isFile() || !allowed.has(path.extname(name).toLowerCase())) continue
    const stat = await fs.stat(absolute)
    total += stat.size
    if (stat.size > 1_000_000 || total > 20_000_000 || sources.length >= 1000) throw Error('Static artifact exceeds limits')
    const content = await fs.readFile(absolute)
    const extension = path.extname(name).slice(1).toLowerCase()
    if (['html', 'css', 'js', 'mjs'].includes(extension)) {
      const text = content.toString('utf8')
      if (text.includes('\0')) throw Error('Invalid static source encoding')
      if (/cdn\.tailwindcss\.com|@tailwindcss\/browser|tailwindcss\/browser/.test(text)) throw Error('Runtime Tailwind CDN is forbidden')
      if (extension === 'css' && /@(import|plugin|config|source|tailwind)\b|url\(\s*['"]?(?:https?:)?\/\//i.test(text)) {
        throw Error('Static CSS cannot load remote resources or compiler configuration')
      }
      if (extension === 'html' && /<(?:script|link)\b[^>]*(?:src|href)\s*=\s*['"]?(?:https?:)?\/\//i.test(text)) {
        throw Error('Static scripts and styles must be local')
      }
    }
    sources.push({ name, content, extension })
  }
}
await collect(root)
if (!sources.some(file => file.name === 'index.html')) throw Error('Static entrypoint is missing')
const scanner = new Scanner({})
const candidates = scanner.scanFiles(sources.filter(file => ['html', 'js', 'mjs'].includes(file.extension))
  .map(file => ({ content: file.content.toString('utf8'), extension: file.extension })))
const compiler = await compile('@import "tailwindcss" source(none);', {
  base: root,
  onDependency(file) {
    const relative = path.relative(dependencyRoot, realpathSync(file))
    if (relative.startsWith('..') || path.isAbsolute(relative)) throw Error('Compiler dependency escaped the pinned set')
  },
  customJsResolver() { throw Error('Project compiler plugins are forbidden') },
})
const theme = sources.find(file => file.name === `rendra/${marker.contractId}/theme.css`)
const css = compiler.build(candidates) + '\n' + (theme?.content.toString('utf8') ?? '')
const output = path.join(root, 'dist')
const previous = await fs.lstat(output).catch(error => { if (error.code !== 'ENOENT') throw error; return null })
if (previous?.isSymbolicLink()) throw Error('Artifact output cannot be a symlink')
await fs.rm(output, { recursive: true, force: true })
await fs.mkdir(output, { recursive: true })
for (const file of sources) {
  const destination = path.join(output, file.name)
  await fs.mkdir(path.dirname(destination), { recursive: true })
  let content = file.content
  if (file.extension === 'html') {
    let html = content.toString('utf8').replace(/<link\b[^>]*href\s*=\s*['"](?:\.\/|\/)?rendra\.css['"][^>]*>/gi, '')
    const link = `<link rel="stylesheet" href="${path.posix.relative(path.posix.dirname(file.name), 'rendra.css')}">`
    html = /<\/head\s*>/i.test(html) ? html.replace(/<\/head\s*>/i, `${link}</head>`) : link + html
    content = Buffer.from(html)
  }
  await fs.writeFile(destination, content)
}
await fs.writeFile(path.join(output, 'rendra.css'), css)
