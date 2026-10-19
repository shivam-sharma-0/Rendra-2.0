const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('../frontend/node_modules/typescript');
const React = require('../frontend/node_modules/react');
const { renderToStaticMarkup } = require('../frontend/node_modules/react-dom/server');
const router = require('../frontend/node_modules/react-router-dom');
const source = 'const routes = globalThis.fixtureRoutes;\n' + fs.readFileSync('src/main/resources/contract/hash-app.tsx', 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } });
const Home = () => React.createElement('h1', null, 'Home');
const Detail = ({params}) => React.createElement('h1', null, 'Detail ' + params.id);
const NewTask = () => React.createElement('h1', null, 'New task');
const Settings = () => React.createElement('h1', null, 'Settings');
const routes = [{ path: '/', component: Home }, { path: '/tasks/:id', component: Detail },
    { path: '/tasks/new', component: NewTask }, { path: '/settings', component: Settings }];
function page(hash) {
    const location = { pathname: '/api/projects/fixture/preview/', hash };
    const context = { exports: {}, fixtureRoutes: routes,
        window: { location, history: { state: null, replaceState(state, title, address) { location.hash = address; } } },
        require(name) {
            if (name === 'react-router-dom') return { ...router,
                HashRouter: ({children}) => React.createElement(router.MemoryRouter, {initialEntries: [hash.slice(1) || '/']}, children) };
            if (name === 'react') return React;
            if (name === 'react/jsx-runtime') return require('../frontend/node_modules/react/jsx-runtime');
            throw Error('Unexpected router dependency');
        } };
    vm.runInNewContext(compiled.outputText, context);
    return { html: renderToStaticMarkup(React.createElement(context.exports.default)), location };
}
const home = page('');
assert.match(home.html, /Home/);
assert.equal(home.location.hash, '#/');
assert.equal(home.location.pathname, '/api/projects/fixture/preview/');
assert.match(page('#/settings?tab=profile').html, /Settings/);
assert.match(page('#/tasks/new').html, /New task/);
assert.match(page('#/tasks/task%2042?mode=edit').html, /Detail task 42/);
for (const hash of ['#/unknown', '#/tasks', '#/tasks/42/extra']) assert.match(page(hash).html, /Page not found/);
console.log('Contract routing passed with react-router-dom: preview prefix, home, query, static priority, decoded parameters and not-found routes');
