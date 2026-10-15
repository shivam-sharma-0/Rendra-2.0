const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createBenchmarkApi } = require('./AgentBenchmarkAuth.cjs');

const credentials = { email: 'benchmark-test@example.invalid', password: 'synthetic-only', name: 'Synthetic benchmark' };
const response = (status, data) => ({ ok: status >= 200 && status < 300, status,
  text: async () => typeof data === 'string' ? data : JSON.stringify(data) });

test('each journey can re-login the same synthetic identity without signing up again', async () => {
  const calls = [];
  const client = createBenchmarkApi('http://127.0.0.1:9000', credentials, async (url, options) => {
    calls.push({ url, options });
    return url.endsWith('/signup') ? response(201, { accessToken: 'initial' }) : response(200, { accessToken: 'renewed' });
  });
  await client.signup();
  await client.renew();
  await client.renew();
  assert.deepEqual(calls.map(call => call.url.split('/').at(-1)), ['signup', 'login', 'login']);
  assert.equal(JSON.parse(calls[1].options.body).email, credentials.email);
  assert.equal(calls[1].options.headers.Authorization, undefined);
});

test('401 during a long journey re-logins and replays the identical request once', async () => {
  const calls = [];
  const client = createBenchmarkApi('http://127.0.0.1:9000', credentials, async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith('/signup')) return response(201, { accessToken: 'expired' });
    if (url.endsWith('/login')) return response(200, { accessToken: 'fresh' });
    return options.headers.Authorization === 'Bearer expired'
      ? response(401, '<html>Unauthorized</html>') : response(200, { id: 'same-message' });
  });
  await client.signup();
  assert.deepEqual(await client.api('/api/projects/test/messages', 'POST', { id: 'same-message', message: 'Synthetic' }),
    { id: 'same-message' });
  assert.equal(calls.length, 4);
  assert.equal(calls[1].options.body, calls[3].options.body);
  assert.equal(calls[3].options.method, 'POST');
  assert.equal(calls[3].options.headers.Authorization, 'Bearer fresh');
});

test('a second 401 terminates without another renewal or recursive request', async () => {
  let renewals = 0;
  let requests = 0;
  const client = createBenchmarkApi('http://127.0.0.1:9000', credentials, async url => {
    if (url.endsWith('/signup')) return response(201, { accessToken: 'initial' });
    if (url.endsWith('/login')) { renewals++; return response(200, { accessToken: 'fresh' }); }
    requests++; return response(401, { message: 'refused' });
  });
  await client.signup();
  await assert.rejects(client.api('/api/projects/test/agent-runs'), /HTTP 401/);
  assert.equal(renewals, 1);
  assert.equal(requests, 2);
});

test('non-auth failures do not renew and provider error bodies never leak credentials', async () => {
  const calls = [];
  const client = createBenchmarkApi('http://127.0.0.1:9000', credentials, async url => {
    calls.push(url);
    if (url.endsWith('/signup')) return response(201, { accessToken: 'initial' });
    return response(403, { message: credentials.password });
  });
  await client.signup();
  await assert.rejects(client.api('/api/projects/test'), error =>
    /HTTP 403/.test(error.message) && !error.message.includes(credentials.password));
  assert.equal(calls.length, 2);
});

test('failed login is terminal and never recursively renews', async () => {
  let attempts = 0;
  const client = createBenchmarkApi('http://127.0.0.1:9000', credentials, async () => {
    attempts++; return response(401, '<html>expired</html>');
  });
  await assert.rejects(client.renew(), /HTTP 401/);
  assert.equal(attempts, 1);
});

test('missing tokens and external destinations fail closed', async () => {
  assert.throws(() => createBenchmarkApi('https://example.com', credentials), /loopback/);
  const client = createBenchmarkApi('http://127.0.0.1:9000', credentials, async () => response(200, {}));
  await assert.rejects(client.signup(), /access token/);
  await assert.rejects(client.api('//example.com/path'), /API path/);
});
