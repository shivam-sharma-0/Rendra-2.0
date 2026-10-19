import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';

const MAX_BYTES = 1024 * 1024;
const HEADERS = new Set(['content-type', 'accept', 'authorization', 'apikey', 'prefer', 'range', 'range-unit',
  'x-client-info', 'x-supabase-api-version', 'accept-profile', 'content-profile']);
const SUPABASE_PATH = /^\/(?:rest\/v1(?:\/[A-Za-z0-9._~!$&'()*+,;=:@%-]*)*|auth\/v1\/(?:signup|token|logout|user|settings|health))\/?$/;

export async function exchange(directory, data) {
  if (!directory || typeof data.path !== 'string' || data.path.length > 2048) throw Error('Invalid private relay request');
  const id = randomUUID().replaceAll('-', '');
  const request = path.join(directory, id + '.request.json');
  const response = path.join(directory, id + '.response.json');
  const temporary = path.join(directory, id + '.tmp');
  try {
    const json = JSON.stringify(data);
    if (Buffer.byteLength(json) > 2 * MAX_BYTES) throw Error('Relay request exceeds capacity');
    fs.writeFileSync(temporary, json, {flag:'wx'});
    fs.renameSync(temporary, request);
    const deadline = Date.now() + 12000;
    while (!fs.existsSync(response)) {
      if (Date.now() >= deadline) throw Error('Private relay timeout');
      await new Promise(resolve => setTimeout(resolve, 15));
    }
    const stat = fs.lstatSync(response);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 2 * MAX_BYTES) throw Error('Invalid private relay response');
    const reply = JSON.parse(fs.readFileSync(response, 'utf8'));
    if (!Number.isInteger(reply.status) || reply.status < 100 || reply.status > 599
      || typeof reply.body !== 'string' || Buffer.from(reply.body, 'base64').length > MAX_BYTES
      || Buffer.from(reply.body, 'base64').toString('base64') !== reply.body
      || typeof reply.headers?.['content-type'] !== 'string') throw Error('Invalid private relay response');
    return reply;
  } finally {
    for (const file of [response, temporary]) if (fs.existsSync(file)) fs.unlinkSync(file);
    // The backend owns consumed request-file removal; do not race its reader on timeout.
  }
}

/** CDP interception of the one virtual Supabase origin; it grants no general browser network access. */
export async function forwardSupabase(request, origin, relay) {
  const target = new URL(request.url);
  const selected = new URL(origin);
  const decoded = decodeURIComponent(target.pathname);
  if (target.origin !== selected.origin || target.username || target.password || target.hash
    || !SUPABASE_PATH.test(target.pathname) || /%(?:2f|5c|40|25)/i.test(target.pathname)
    || decoded.includes('\\') || decoded.includes('//') || decoded.split('/').some(segment => ['.', '..'].includes(segment))
    || !['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'].includes(request.method)) {
    throw Error('Invalid isolated Supabase route');
  }
  if (request.method === 'OPTIONS') return {status:204, headers:{'content-type':'application/json'}, body:''};
  const body = Buffer.from(request.postData || '', 'utf8');
  if (body.length > MAX_BYTES) throw Error('Supabase request exceeds capacity');
  const headers = Object.fromEntries(Object.entries(request.headers || {})
    .map(([name, value]) => [name.toLowerCase(), value])
    .filter(([name, value]) => HEADERS.has(name) && typeof value === 'string' && value.length <= 8192));
  return relay({method:request.method, path:target.pathname + target.search, headers, body:body.toString('base64')});
}

/** Safe response metadata needed for REST counts/ranges; never exposes upstream cookies or redirects. */
export function supabaseResponseHeaders(reply, siteOrigin) {
  const headers = Object.entries(reply.headers || {})
    .filter(([name, value]) => ['content-type', 'content-range', 'range-unit', 'preference-applied'].includes(name)
      && typeof value === 'string' && value.length <= 8192)
    .map(([name, value]) => ({name, value}));
  return [...headers,
    {name:'Cache-Control', value:'no-store'},
    {name:'Access-Control-Allow-Origin', value:new URL(siteOrigin).origin},
    {name:'Access-Control-Allow-Headers', value:[...HEADERS].join(',')},
    {name:'Access-Control-Allow-Methods', value:'GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS'},
    {name:'Access-Control-Expose-Headers', value:'content-range,range-unit,preference-applied'}];
}
