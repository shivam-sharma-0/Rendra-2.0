const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');

const requirePackage = createRequire(path.join(process.argv[2], 'package.json'));
const ts = requirePackage('typescript');
const React = requirePackage('react');
const { renderToStaticMarkup } = requirePackage('react-dom/server');
const source = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));

function load(text, imports) {
  const compiled = ts.transpileModule(text, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX,
  } }).outputText;
  const module = { exports: {} };
  vm.runInNewContext(compiled, { module, exports: module.exports, window: global.window,
    require(name) { return imports[name] ?? (name.endsWith('.css') ? {} : requirePackage(name)); } });
  return module.exports;
}

const home = load(source.home, {}).default;
const settings = load(source.settings, {}).default;
const routeImports = {};
for (const match of source.routes.matchAll(/import Route(\d+) from "([^"]+)"/g)) {
  routeImports[match[2]] = { __esModule: true, default: match[1] === '0' ? home : settings };
}
const routes = load(source.routes, routeImports);

const cases = [
  ['/', '', 'Known home'],
  ['/p/fixture/', '', 'Known home'],
  ['/p/fixture/', '#/settings', 'Known settings'],
  ['/preview/run/', '#/settings', 'Known settings'],
  ['/p/fixture/', '#/missing', 'Page not found'],
  ['/', '#/settings', 'Known settings'],
  ['/preview/run/', '#/', 'Known home'],
];
for (const [pathname, hash, expected] of cases) {
  global.window = { location: { pathname, hash } };
  const imports = {};
  const match = source.app.match(/import \{ routes \} from "([^"]+)"/);
  assert.ok(match, 'Generated app must use the declared routes module');
  imports[match[1]] = routes;
  const App = load(source.app, imports).default;
  const html = renderToStaticMarkup(React.createElement(App));
  assert.ok(html.includes(expected), `${pathname}${hash}: ${html}`);
  if (hash === '#/missing') {
    assert.ok(!html.includes('Known home') && !html.includes('Known settings'));
    assert.ok(html.includes('href="#/"'), 'Home link must retain the hosting prefix');
  }
}
console.log(`router behavior: ${cases.length}/${cases.length} prefixed/hash URLs passed`);
