const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('../frontend/node_modules/typescript');
const source = 'const routes = globalThis.fixtureRoutes;\n' + fs.readFileSync('src/main/resources/contract/hash-app.tsx', 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } });
function Home() {}
function Detail() {}
function NewTask() {}
function Settings() {}
const routes = [{ path: '/', component: Home }, { path: '/tasks/:id', component: Detail },
    { path: '/tasks/new', component: NewTask }, { path: '/settings', component: Settings }];
function page(hash) {
    const location = { pathname: '/api/projects/fixture/preview/', hash };
    const context = { exports: {}, fixtureRoutes: routes,
        window: { location, addEventListener() {}, removeEventListener() {},
            history: { state: null, replaceState(state, title, address) { location.hash = address; } } },
        require(name) {
            if (name === 'react') return { useSyncExternalStore(subscribe, snapshot) { subscribe(() => {}); return snapshot(); } };
            if (name === 'react/jsx-runtime') return { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }) };
            throw Error('Unexpected router dependency');
        } };
    vm.runInNewContext(compiled.outputText, context);
    return { view: context.exports.default(), location };
}
const home = page('');
assert.equal(home.view.type, Home);
assert.equal(home.location.hash, '#/');
assert.equal(home.location.pathname, '/api/projects/fixture/preview/');
assert.equal(page('#/settings?tab=profile').view.type, Settings);
assert.equal(page('#/tasks/new').view.type, NewTask);
const detail = page('#/tasks/task%2042?mode=edit');
assert.equal(detail.view.type, Detail);
assert.equal(detail.view.props.params.id, 'task 42');
for (const hash of ['#/unknown', '#/tasks', '#/tasks/%zz', '#/tasks/42/extra']) assert.equal(page(hash).view.type, 'main');
console.log('Contract routing passed: preview prefix, home, query, static priority, decoded parameters and four invalid routes');
