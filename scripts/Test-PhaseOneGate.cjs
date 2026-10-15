// Full Boot checks against the runner's disposable copy. Never persist credentials.
const assert = require('node:assert/strict');

module.exports = async function prepareGate(base, project, credentials) {
  const checks = [];
  const origin = 'http://localhost:5173';

  async function request(route, method = 'GET', headers = {}, body) {
    const response = await fetch(base + route, {
      method,
      headers: { 'Content-Type': 'application/json', ...headers },
      ...(body ? { body: JSON.stringify(body) } : {}),
      signal: AbortSignal.timeout(10000),
    });
    const text = await response.text();
    let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = null; }
    return {
      status: response.status,
      data,
      cookie: response.headers.getSetCookie().map(value => value.split(';')[0]).join('; '),
    };
  }

  const login = await request('/api/auth/login', 'POST', {}, credentials);
  assert.equal(login.status, 200, 'real login');
  assert.ok(login.cookie && login.data.accessToken);
  checks.push('real login issues access and refresh credentials');

  for (const [route, headers] of [
    ['/api/auth/refresh', { Cookie: login.cookie }],
    ['/api/auth/refresh', { Cookie: login.cookie, Origin: 'https://foreign.example.invalid' }],
    ['/api/auth/%72efresh', { Cookie: login.cookie, Origin: 'https://foreign.example.invalid' }],
  ]) {
    const rejected = await request(route, 'POST', headers);
    assert.ok([400, 403].includes(rejected.status), 'untrusted cookie mutation is refused');
  }
  checks.push('missing, foreign and encoded-path foreign origins are refused');

  const lostResponse = await request('/api/auth/refresh', 'POST', { Cookie: login.cookie, Origin: origin });
  assert.equal(lostResponse.status, 200);
  const retry = await request('/api/auth/refresh', 'POST', { Cookie: login.cookie, Origin: origin });
  assert.equal(retry.status, 200, 'immediate previous refresh gets one lost-response retry');
  assert.notEqual(retry.cookie, lostResponse.cookie);
  const access = retry.data.accessToken;
  assert.equal((await request('/api/auth/me', 'GET', { Authorization: 'Bearer ' + access })).status, 200);
  checks.push('lost-response refresh retry leaves a usable session');

  function openSocket(onMessage) {
    const socket = new WebSocket(base.replace('http:', 'ws:') + '/realtime?token='
      + encodeURIComponent(access) + '&projectId=' + project);
    const ready = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(Error('Realtime CONNECTED timeout')), 10000);
      socket.addEventListener('message', event => {
        const frame = JSON.parse(event.data);
        onMessage(frame);
        if (frame.type === 'CONNECTED') {
          clearTimeout(timer);
          resolve();
        }
      });
      socket.addEventListener('error', () => {
        clearTimeout(timer);
        reject(Error('Realtime connection failed'));
      }, { once: true });
    });
    return { socket, ready };
  }

  function waitClosed(socket, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(Error('Realtime close timeout')), timeoutMs);
      socket.addEventListener('close', event => {
        clearTimeout(timer);
        resolve(event.code);
      }, { once: true });
    });
  }

  let pongs = 0;
  const flood = openSocket(frame => { if (frame.type === 'PONG') pongs++; });
  try {
    await flood.ready;
    const closed = waitClosed(flood.socket, 10000);
    for (let index = 0; index < 21; index++) {
      flood.socket.send(JSON.stringify({ type: 'PING' }));
    }
    assert.equal(await closed, 1008);
    assert.equal(pongs, 20);
    checks.push('real heartbeat flood closes after 20 accepted frames');
  } finally {
    flood.socket.close();
  }

  const streamedRuns = new Set();
  const eventCounts = {};
  const stream = openSocket(frame => {
    eventCounts[frame.type] = (eventCounts[frame.type] || 0) + 1;
    if (frame.payload?.runId) streamedRuns.add(frame.payload.runId);
  });
  try {
    await stream.ready;
  } catch (error) {
    stream.socket.close();
    throw error;
  }

  return {
    close() { stream.socket.close(); },
    async finish(runId) {
      try {
        assert.ok(streamedRuns.has(runId), 'real build progress reached the authenticated socket');
        checks.push('real free-model build streams progress over WebSocket');
        const closed = waitClosed(stream.socket, 35000);
        assert.equal((await request('/api/auth/logout', 'POST', { Cookie: retry.cookie, Origin: origin })).status, 204);
        assert.equal((await request('/api/auth/me', 'GET', { Authorization: 'Bearer ' + access })).status, 401);
        assert.equal((await request('/api/auth/refresh', 'POST', { Cookie: retry.cookie, Origin: origin })).status, 401);
        checks.push('logout revokes access and refresh credentials');
        assert.equal(await closed, 1008);
        checks.push('existing 30-second sweep closes the idle logged-out socket');
        return { passed: checks.length, checks, eventCounts, scope: 'full Boot / copied real dev DB / WSL / FREE' };
      } finally {
        stream.socket.close();
      }
    },
  };
};
