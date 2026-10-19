const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('../frontend/node_modules/typescript');
const fixture = JSON.parse(fs.readFileSync('target/codex-contract-scaffold/client-auth.json', 'utf8'));
function compile(source) {
    return ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
}
function load(source, storage = new Map(), unavailable = false) {
    const events = new Map(), requests = [];
    let response = { status: 200, data: { token: 'synthetic-token', userId: 'synthetic-user' } };
    const window = { localStorage: {
        getItem(key) { if (unavailable) throw Error('Storage unavailable'); return storage.get(key) ?? null; },
        setItem(key, value) { if (unavailable) throw Error('Storage unavailable'); storage.set(key, value); },
        removeItem(key) { if (unavailable) throw Error('Storage unavailable'); storage.delete(key); }
    }, addEventListener(name, handler) { events.set(name, handler); } };
    const context = { exports: {}, window, fetch: async (url, options) => {
        requests.push({ url, options });
        return { ok: response.status >= 200 && response.status < 300, status: response.status, json: async () => response.data };
    }, require(name) {
        if (name === 'react') return { useSyncExternalStore(subscribe, snapshot) { subscribe(() => {}); return snapshot(); } };
        throw Error('Unexpected auth dependency');
    } };
    vm.runInNewContext(compile(source), context);
    return { ...context.exports, storage, events, requests, context,
        respond(status, data) { response = { status, data }; } };
}
(async () => {
    const client = load(fixture.auth);
    assert.equal(client.auth.getSession(), null);
    await client.auth.signIn({ email: 'USER@example.invalid ', password: 'synthetic-password' });
    assert.equal(client.requests[0].url, '/api/auth/login');
    assert.equal(client.auth.getSession().email, 'user@example.invalid');
    assert.equal(client.useSession().token, 'synthetic-token');
    assert.equal(Object.isFrozen(client.auth.getSession()), true);
    assert.equal([...client.storage.values()][0].includes('synthetic-password'), false);
    assert.equal(load(fixture.auth, client.storage).auth.getToken(), 'synthetic-token');
    assert.equal(load(fixture.otherProjectAuth, client.storage).auth.getSession(), null);

    const apiContext = { exports: {}, fetch: client.context.fetch, require: name => {
        if (name === './auth-client') return { auth: client.auth };
        throw Error('Unexpected API dependency');
    } };
    vm.runInNewContext(compile(fixture.api), apiContext);
    client.respond(200, { id: 'record' });
    await apiContext.exports.api[fixture.getMethod]({ id: 'record' });
    assert.equal(client.requests.at(-1).options.headers.Authorization, 'Bearer synthetic-token');
    assert.equal(client.requests.at(-1).options.body, undefined);
    await apiContext.exports.api[fixture.getMethod]({ id: 'record' }, undefined, 'explicit-token');
    assert.equal(client.requests.at(-1).options.headers.Authorization, 'Bearer explicit-token');
    client.respond(401, { error: 'sensitive server body' });
    await assert.rejects(apiContext.exports.api[fixture.getMethod]({ id: 'record' }), /API request failed \(401\)/);
    assert.equal(client.auth.getSession(), null);
    assert.equal(client.storage.size, 0);
    await assert.rejects(client.auth.signIn({ email: 'user@example.invalid', password: 'wrong' }), /Authentication failed/);
    assert.equal(client.auth.getSession(), null);
    client.respond(200, { token: '', userId: 'user' });
    await assert.rejects(client.auth.signIn({ email: 'user@example.invalid', password: 'wrong' }), /invalid session/);
    assert.equal(client.auth.getSession(), null);

    const restricted = load(fixture.auth, new Map(), true);
    await restricted.auth.signUp({ email: 'user@example.invalid', password: 'synthetic-password' });
    assert.equal(restricted.requests[0].url, '/api/auth/signup');
    assert.equal(restricted.auth.getToken(), 'synthetic-token');
    restricted.auth.signOut();
    assert.equal(restricted.auth.getSession(), null);

    const tabs = load(fixture.auth);
    await tabs.auth.signIn({ email: 'user@example.invalid', password: 'synthetic-password' });
    const key = [...tabs.storage.keys()][0];
    tabs.events.get('storage')({ key: 'another-project', newValue: null });
    assert.equal(tabs.auth.getToken(), 'synthetic-token');
    tabs.events.get('storage')({ key, newValue: null });
    assert.equal(tabs.auth.getSession(), null);
    console.log('Contract auth passed: signup/login, reload, scope isolation, bearer API, 401, malformed sessions, restricted storage and cross-tab sign-out');
})().catch(error => { console.error(error.message); process.exitCode = 1; });
