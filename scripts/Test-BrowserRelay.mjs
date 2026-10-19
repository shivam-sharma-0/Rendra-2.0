import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {tmpdir} from 'node:os';
import {exchange, forwardSupabase, supabaseResponseHeaders} from '../src/main/resources/sandbox/browser/relay.mjs';

const origin = 'http://codepal-supabase.invalid';
const reply = {status:200, headers:{'content-type':'application/json'}, body:Buffer.from('[]').toString('base64')};

test('Supabase interception forwards only the selected origin and required SDK headers', async () => {
  let forwarded;
  const response = await forwardSupabase({url:origin + '/rest/v1/tasks?id=eq.a&select=*', method:'POST',
    headers:{Authorization:'Bearer fixture', apikey:'public-fixture', Prefer:'return=representation', 'X-Supabase-Api-Version':'2024-01-01',
      'Accept-Profile':'public', 'Content-Profile':'public', Cookie:'private'},
    postData:JSON.stringify({title:'Fixture'})}, origin, async request => { forwarded = request; return reply; });
  assert.equal(response, reply);
  assert.equal(forwarded.path, '/rest/v1/tasks?id=eq.a&select=*');
  assert.equal(forwarded.headers.authorization, 'Bearer fixture');
  assert.equal(forwarded.headers.apikey, 'public-fixture');
  assert.equal(forwarded.headers.prefer, 'return=representation');
  assert.equal(forwarded.headers['x-supabase-api-version'], '2024-01-01');
  assert.equal(forwarded.headers['accept-profile'], 'public');
  assert.equal(forwarded.headers['content-profile'], 'public');
  assert.equal(forwarded.headers.cookie, undefined);
  assert.deepEqual(JSON.parse(Buffer.from(forwarded.body, 'base64')), {title:'Fixture'});
});

test('external, administrative, unsupported and encoded paths never reach the relay', async () => {
  const refused = async () => { throw Error('Relay must not be called'); };
  for (const url of ['http://example.invalid/rest/v1/tasks', 'http://user:pass@codepal-supabase.invalid/rest/v1/tasks',
    origin + '/auth/v1/admin/users', origin + '/storage/v1/objects', origin + '/api/projects',
    origin + '/rest/v1/tasks%2Fadmin', origin + '/rest/v1/tasks%2540host', origin + '/rest/v1/%5Cescape',
    origin + '/rest/v1/tasks#fragment']) {
    await assert.rejects(forwardSupabase({url, method:'GET'}, origin, refused));
  }
  await assert.rejects(forwardSupabase({url:origin + '/rest/v1/tasks', method:'CONNECT'}, origin, refused));
});

test('CORS preflight is fulfilled locally without a service call', async () => {
  const response = await forwardSupabase({url:origin + '/auth/v1/token?grant_type=password', method:'OPTIONS'},
    origin, () => { throw Error('No upstream preflight needed'); });
  assert.equal(response.status, 204);
});

test('REST count metadata reaches the page with bounded CORS exposure and no upstream credentials', () => {
  const headers = Object.fromEntries(supabaseResponseHeaders({...reply, headers:{'content-type':'application/json',
    'content-range':'0-9/42', 'range-unit':'items', 'set-cookie':'private=secret', location:'http://example.invalid',
    'x-secret':'never'}}, 'http://127.0.0.1:3000/page').map(({name, value}) => [name.toLowerCase(), value]));
  assert.equal(headers['content-range'], '0-9/42');
  assert.equal(headers['range-unit'], 'items');
  assert.equal(headers['access-control-allow-origin'], 'http://127.0.0.1:3000');
  assert.match(headers['access-control-expose-headers'], /content-range/);
  assert.match(headers['access-control-allow-headers'], /x-supabase-api-version/);
  assert.match(headers['access-control-allow-headers'], /accept-profile/);
  assert.match(headers['access-control-allow-headers'], /content-profile/);
  assert.equal(headers['set-cookie'], undefined);
  assert.equal(headers.location, undefined);
  assert.equal(headers['x-secret'], undefined);
});

test('oversized request data is refused before transport', async () => {
  await assert.rejects(forwardSupabase({url:origin + '/rest/v1/tasks', method:'POST', postData:'x'.repeat(1024 * 1024 + 1)},
    origin, () => { throw Error('No oversized request'); }));
});

async function withResponder(response, run) {
  const directory = fs.mkdtempSync(path.join(tmpdir(), 'codepal-relay-test-'));
  let received;
  const responder = setInterval(() => {
    const request = fs.readdirSync(directory).find(name => name.endsWith('.request.json'));
    if (!request || received) return;
    received = JSON.parse(fs.readFileSync(path.join(directory, request), 'utf8'));
    fs.writeFileSync(path.join(directory, request.replace('.request.json', '.response.json')), response);
  }, 5);
  try { await run(directory, () => received); }
  finally {
    clearInterval(responder);
    assert(path.resolve(directory).startsWith(path.resolve(tmpdir()) + path.sep + 'codepal-relay-test-'));
    fs.rmSync(directory, {recursive:true, force:true});
  }
}

test('private file exchange preserves the bounded request/response and removes consumed response data', async () => {
  await withResponder(JSON.stringify(reply), async (directory, received) => {
    assert.deepEqual(await exchange(directory, {method:'GET', path:'/rest/v1/tasks', headers:{}, body:''}), reply);
    assert.equal(received().path, '/rest/v1/tasks');
    assert.equal(fs.readdirSync(directory).filter(name => name.endsWith('.response.json')).length, 0);
  });
});

test('malformed and oversized private responses are refused', async () => {
  for (const response of ['not JSON', JSON.stringify({...reply, status:999}), JSON.stringify({...reply, headers:{}}),
    JSON.stringify({...reply, body:'!invalid-base64!'}),
    JSON.stringify({...reply, body:Buffer.alloc(1024 * 1024 + 1).toString('base64')})]) {
    await withResponder(response, async directory => {
      await assert.rejects(exchange(directory, {method:'GET', path:'/rest/v1/tasks', headers:{}, body:''}));
    });
  }
});
