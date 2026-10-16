// Opt-in real browser fixture: generated App/routes, installed packages, owned offline Docker only.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

const [modules, fixtureFile, probeFile] = process.argv.slice(2);
const fixture = JSON.parse(fs.readFileSync(fixtureFile, 'utf8'));
const scratch = path.dirname(fixtureFile);
const source = path.join(scratch, 'source');
const built = path.join(scratch, 'built');
const site = path.join(scratch, 'site');
const requirePackage = createRequire(path.join(modules, 'package.json'));
const owner = 'codepal-contract-hash-router-' + randomUUID();
const label = 'com.codepal.contract-hash-router';
for (const [name, body] of Object.entries(fixture.files)) {
  const target = path.resolve(source, name);
  assert.ok(target.startsWith(source + path.sep));
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, body);
}
fs.writeFileSync(path.join(source, 'src/Home.tsx'), `
export default function Home() { return <main><h1>Known home</h1>
  <a id="settings" href="#/settings">Settings</a><a id="missing" href="#/missing">Missing</a></main> }
`);
fs.writeFileSync(path.join(source, 'src/Settings.tsx'), `
export default function Settings() { return <main><h1>Known settings</h1>
  <button id="back" onClick={() => window.history.back()}>Back</button></main> }
`);
fs.writeFileSync(path.join(source, 'test-main.tsx'), `
import {createRoot} from 'react-dom/client'; import App from './src/App';
createRoot(document.getElementById('root')!).render(<><App/><output id="prefix">{window.location.pathname}</output></>);
`);
fs.writeFileSync(path.join(source, 'index.html'), '<html><head><title>Router fixture</title></head><body><div id="root"></div><script type="module" src="./test-main.tsx"></script></body></html>');
const ts = requirePackage('typescript');
fs.writeFileSync(path.join(source, 'ambient.d.ts'), "declare module '*.css';");
const program = ts.createProgram([path.join(source, 'src/App.tsx'), path.join(source, 'ambient.d.ts')], {
  strict: true, noEmit: true, skipLibCheck: true, types: [], target: ts.ScriptTarget.ES2022,
  module: ts.ModuleKind.ESNext, moduleResolution: ts.ModuleResolutionKind.Bundler, jsx: ts.JsxEmit.ReactJSX,
  paths: { react: [path.join(modules, '@types/react/index.d.ts')], 'react/*': [path.join(modules, '@types/react/*')] },
});
const diagnostics = ts.getPreEmitDiagnostics(program);
assert.equal(diagnostics.length, 0, diagnostics.slice(0, 20)
  .map(item => (item.file?.fileName ?? '') + ': ' + ts.flattenDiagnosticMessageText(item.messageText, '\n')).join('\n'));
const { build } = await import(pathToFileURL(requirePackage.resolve('vite')));
const { default: react } = await import(pathToFileURL(requirePackage.resolve('@vitejs/plugin-react')));
await build({ configFile: false, root: source, base: './', plugins: [react()], logLevel: 'warn',
  resolve: { alias: { react: path.join(modules, 'react'), 'react-dom': path.join(modules, 'react-dom') } },
  build: { outDir: built, emptyOutDir: false } });
const prefixes = ['/p/fixture/', '/preview/run/'];
for (const prefix of prefixes) fs.cpSync(built, path.join(site, prefix.slice(1)), { recursive: true });
const command = args => execFileSync('wsl.exe', ['-e', ...args], { encoding: 'utf8', timeout: 60000, maxBuffer: 1048576 }).trim();
const action = (id, kind, selector, value) => ({ id, kind, selector, value });
const reports = [];
try {
  const sitePath = command(['wslpath', '-u', site]);
  const probePath = command(['wslpath', '-u', probeFile]);
  const image = command(['docker', 'image', 'inspect', 'codepal-browser:efeb16a78e4c', '--format', '{{.Id}}']);
  assert.match(image, /^sha256:[a-f0-9]{64}$/);
  for (const [index, prefix] of prefixes.entries()) {
    fs.writeFileSync(path.join(site, 'index.html'), '<script>location.replace(' + JSON.stringify(prefix) + ')</script>');
    const actions = [
      action('home', 'ASSERT_TEXT', 'h1', 'Known home'),
      action('prefix', 'ASSERT_TEXT', '#prefix', prefix),
      action('settings', 'CLICK', '#settings'), action('settings-route', 'ASSERT_ROUTE', null, '/settings'),
      action('settings-content', 'ASSERT_TEXT', 'h1', 'Known settings'),
      action('back-before-reload', 'CLICK', '#back'), action('home-before-reload', 'ASSERT_TEXT', 'h1', 'Known home'),
      action('settings-again', 'CLICK', '#settings'), action('settings-again-route', 'ASSERT_ROUTE', null, '/settings'),
      action('reload', 'RELOAD'),
      action('settings-after-reload', 'ASSERT_TEXT', 'h1', 'Known settings'),
      action('back', 'CLICK', '#back'), action('back-route', 'ASSERT_ROUTE', null, '/'),
      action('back-content', 'ASSERT_TEXT', 'h1', 'Known home'),
      action('missing', 'CLICK', '#missing'), action('missing-route', 'ASSERT_ROUTE', null, '/missing'),
      action('missing-content', 'ASSERT_TEXT', 'h1', 'Page not found'),
      action('home-link', 'CLICK', 'a[href="#/"]'), action('home-route', 'ASSERT_ROUTE', null, '/'),
      action('home-content', 'ASSERT_TEXT', 'h1', 'Known home'), action('prefix-retained', 'ASSERT_TEXT', '#prefix', prefix),
    ];
    const encoded = Buffer.from(JSON.stringify({ schema: 1, actions })).toString('base64');
    const raw = command(['docker', 'run', '--rm', '--name', owner + '-' + index, '--label', label + '=' + owner,
      '--pull', 'never', '--network', 'none', '--user', '1000:1000', '--cpus', '1', '--memory', '512m', '--cap-drop', 'ALL',
      '--security-opt', 'no-new-privileges', '--mount', 'type=bind,source=' + sitePath + ',target=/site,readonly',
      '--mount', 'type=bind,source=' + probePath + ',target=/opt/codepal/probe.mjs,readonly', '--entrypoint', 'node', image,
      '/opt/codepal/probe.mjs', '/site', '500', encoded]);
    const report = JSON.parse(raw.split('\n').filter(line => line.startsWith('{')).at(-1));
    assert.equal(report.loaded, true);
    assert.equal(report.acceptance?.length, actions.length);
    assert.ok(report.acceptance.every(check => check.passed), JSON.stringify(report));
    assert.deepEqual(report.issues, []);
    reports.push(report);
  }
} finally {
  for (const container of command(['docker', 'ps', '-aq', '--filter', 'label=' + label + '=' + owner]).split('\n').filter(Boolean)) {
    const labels = JSON.parse(command(['docker', 'inspect', '--format', '{{json .Config.Labels}}', container]));
    assert.equal(labels[label], owner);
    command(['docker', 'rm', '-f', container]);
  }
  assert.equal(command(['docker', 'ps', '-aq', '--filter', 'label=' + label + '=' + owner]), '');
  fs.writeFileSync(path.join(scratch, 'browser-result.json'), JSON.stringify({ owner, reports }, null, 2));
}
console.log(JSON.stringify({ prefixes: prefixes.length, checks: reports.flatMap(report => report.acceptance).length, cleanupVerified: true }));
