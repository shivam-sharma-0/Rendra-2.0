// In-memory credentials for one disposable synthetic identity; never persist tokens or provider data.
function createBenchmarkApi(base, credentials, transport = fetch) {
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw Error('Owned loopback backend required');
  if (!/^benchmark-[a-z0-9-]+@example\.invalid$/.test(credentials.email) || !credentials.password) {
    throw Error('Synthetic benchmark identity required');
  }
  let token;
  const identity = { email: credentials.email, password: credentials.password };

  function remaining(deadline) {
    const value = deadline - Date.now();
    if (value <= 0) throw Error('Benchmark API time budget exhausted');
    return value;
  }

  function send(route, method, body, deadline, authenticated) {
    return transport(base + route, { method, redirect: 'error', signal: AbortSignal.timeout(remaining(deadline)),
      headers: { 'Content-Type': 'application/json', ...(authenticated && token ? { Authorization: 'Bearer ' + token } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  }

  async function decode(response, route) {
    // Error bodies may contain request data. Status/path are enough to diagnose fixture auth failures.
    const text = await response.text();
    if (!response.ok) throw Error(`HTTP ${response.status} at ${route}: request refused`);
    try { return text ? JSON.parse(text) : null; }
    catch { throw Error(`Non-JSON response at ${route} (HTTP ${response.status})`); }
  }

  async function authenticate(route, body, deadline) {
    let response;
    try { response = await send(route, 'POST', body, deadline, false); }
    catch { throw Error('Synthetic benchmark authentication transport failed'); }
    const auth = await decode(response, route);
    if (typeof auth?.accessToken !== 'string' || !auth.accessToken.trim()) throw Error('Synthetic login returned no access token');
    token = auth.accessToken;
  }

  async function renew(timeoutMs = 20000) {
    await authenticate('/api/auth/login', identity, Date.now() + timeoutMs);
  }

  async function api(route, method = 'GET', body, timeoutMs = 20000) {
    if (!route.startsWith('/api/') || /[\\\r\n#]/.test(route) || route.startsWith('/api/auth/')) {
      throw Error('Owned benchmark API path required');
    }
    const deadline = Date.now() + timeoutMs;
    let response = await send(route, method, body, deadline, true);
    if (response.status === 401) {
      await response.text();
      await authenticate('/api/auth/login', identity, deadline);
      response = await send(route, method, body, deadline, true);
    }
    return decode(response, route);
  }

  return { api, renew, signup: () => authenticate('/api/auth/signup',
    { ...identity, name: credentials.name }, Date.now() + 20000) };
}

module.exports = { createBenchmarkApi };
