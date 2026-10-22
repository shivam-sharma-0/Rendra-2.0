const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const http = require('node:http');
const {randomUUID} = require('node:crypto');
const source = fs.readFileSync('src/main/resources/sandbox/browser/probe.mjs', 'utf8');
const functionSource = source.slice(source.indexOf('async function setupAccount('), source.indexOf('\nif (loaded && acceptanceData.authSetup)'));
(async () => {
  const accounts = new Map(); let calls = 0, invalid = false;
  const server = http.createServer(async (request, response) => {
    calls++;
    const chunks = []; for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks));
    if (request.url === '/api/auth/signup') { accounts.set(body.email, body.password); response.writeHead(201).end('{}'); }
    else if (accounts.get(body.email) === body.password) response.writeHead(200).end(JSON.stringify(invalid ? {} : {token:'synthetic-token',userId:body.email}));
    else response.writeHead(401).end('{}');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const sessions = new Map();
    const setup = vm.runInNewContext(functionSource + '\nsetupAccount', {
      fetch:(path, options) => fetch(`http://127.0.0.1:${server.address().port}${path}`, options),
      AbortSignal, localStorage:{setItem:(key, value) => sessions.set(key, JSON.parse(value))}
    });
    const config = {signup:'/api/auth/signup',login:'/api/auth/login',storageKey:'rendra-session:'+randomUUID()};
    for (let index=0; index<2; index++) {
      const credentials = {email:randomUUID()+'@example.invalid',password:randomUUID()};
      assert.equal(await setup(config, credentials), true);
      assert.equal(sessions.get(config.storageKey).email, credentials.email);
    }
    assert.equal(accounts.size, 2);
    const before = calls;
    assert.equal(await setup({...config,login:'https://foreign.invalid/login'}, {}), false);
    assert.equal(calls, before);
    sessions.clear(); invalid = true;
    assert.equal(await setup(config, {email:randomUUID()+'@example.invalid',password:randomUUID()}), false);
    assert.equal(sessions.size, 0);
    console.log('Account browser bootstrap passed: fresh users, usable sessions, unsafe endpoint denial and invalid-session refusal');
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
