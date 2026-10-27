// Loads a built site in headless Chromium and reports what went wrong at runtime, as one JSON line.
// Usage: node probe.mjs <site-dir> [settle-ms]. Runs with no network: the site is served on loopback.
// After the page loads, visible controls are exercised once (clicks, typing, selections) so errors in
// event handlers are found too; each such issue names the control that caused it.
// Uses only Node built-ins (http, child_process, global WebSocket) and the Chrome DevTools Protocol.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import {exchange, forwardSupabase, supabaseResponseHeaders} from './relay.mjs';
import {outcomeDom, runOutcomes} from './outcomes.mjs';

const siteDir = path.resolve(process.argv[2] ?? "/site");
const settleMs = Number(process.argv[3] ?? 1500);
const MAX_ISSUES = 20;
const relayDir = process.argv[5] ? path.resolve(process.argv[5]) : null;
const API_BYTES = 1024 * 1024;
let apiCalls = 0, activeApiCalls = 0;
const acceptanceData = process.argv[4] ? JSON.parse(Buffer.from(process.argv[4], 'base64').toString('utf8')) : {actions:[]};
const acceptanceActions = acceptanceData.actions;
const outcomeDefinitions = acceptanceData.outcomes || [];
const evidenceDir = process.env.CODEPAL_BROWSER_EVIDENCE_DIR || null;
const supabaseOrigin = process.env.CODEPAL_SUPABASE_ORIGIN || null;
const visual = { captures: [], warnings: [] };
if (!Array.isArray(acceptanceActions) || acceptanceActions.length > 24) throw new Error('Invalid acceptance data');
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css",
  ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
  ".gif": "image/gif", ".webp": "image/webp", ".ico": "image/x-icon", ".woff2": "font/woff2", ".woff": "font/woff",
  ".wasm": "application/wasm", ".txt": "text/plain", ".map": "application/json" };

const issues = [];
let siteUrl = "";
/** The control being exercised, attached to the issues it causes (null while loading). */
let action = null;
// The probe has no network on purpose, so calls to other origins (an API, Supabase) fail here and
// would fail for no fault of the site: those failures, and the errors they cause, are not reported.
const OFFLINE = /Failed to fetch|NetworkError|Load failed|net::ERR_|ERR_INTERNET_DISCONNECTED|ERR_NAME_NOT_RESOLVED|ENOTFOUND|AuthRetryableFetchError/i;
const sameOrigin = (url) => !url || url.startsWith(siteUrl) || url.startsWith("data:") || url.startsWith("blob:");
const add = (kind, message, source) => {
  message = String(message);
  if (kind !== "asset" && OFFLINE.test(message)) return;
  message = message.replaceAll(siteUrl, "");
  if (issues.length < MAX_ISSUES && !issues.some((i) => i.kind === kind && i.message === message)) {
    issues.push({ kind, message: message.slice(0, 600), source: source ?? null, action });
  }
};
const finish = (extra) => {
  process.stdout.write(JSON.stringify({ issues:[...issues, ...visual.warnings.map(message => ({kind:'visual', message, source:null, action:null}))], visual, ...extra }) + "\n");
  process.exit(0);
};

// Private file relay to one backend-selected candidate. Chromium has no network access.
async function candidateApi(request, response) {
  if (++apiCalls > 128 || activeApiCalls >= 8) {
    add('api-unavailable', 'Candidate API validation capacity exceeded', null);
    response.writeHead(503, {'Content-Type':'application/json'}).end('{"error":"Validation capacity exceeded"}');return;
  }
  activeApiCalls++;
  try {
    if (!relayDir) throw new Error('Candidate API relay unavailable');
    const chunks = []; let size = 0;
    for await (const chunk of request) { size += chunk.length; if (size > API_BYTES) throw new Error('Request too large'); chunks.push(chunk); }
    const headers = Object.fromEntries(['content-type', 'accept', 'authorization'].filter(key => typeof request.headers[key] === 'string').map(key => [key, request.headers[key]]));
    const reply = await exchange(relayDir, {method:request.method, path:request.url, headers, body:Buffer.concat(chunks).toString('base64')});
    const body = Buffer.from(reply.body, 'base64');
    if (!Number.isInteger(reply.status) || reply.status < 100 || reply.status > 599 || body.length > API_BYTES) throw new Error('Invalid response');
    if (reply.unavailable) add('api-unavailable', 'Candidate API transport was unavailable', null);
    response.writeHead(reply.status, {'Content-Type':reply.headers['content-type'], 'Cache-Control':'no-store'});
    response.end(body);
  } catch {
    add('api-unavailable', 'Candidate API validation was unavailable; saved API was not used', null);
    if (!response.headersSent) response.writeHead(503, {'Content-Type':'application/json'});
    response.end(JSON.stringify({error:'Candidate API validation unavailable'}));
  } finally { activeApiCalls--; }
}
// SPA fallback applies to pages, never API requests.
const server = http.createServer((request, response) => {
  const url = new URL(request.url, "http://localhost");
  if (url.pathname === '/api' || url.pathname.startsWith('/api/')) { void candidateApi(request, response); return; }
  let file = path.join(siteDir, decodeURIComponent(url.pathname));
  if (!file.startsWith(siteDir)) { response.writeHead(403).end(); return; }
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, "index.html");
  if (!fs.existsSync(file)) {
    if (path.extname(file)) { response.writeHead(404).end(); return; }
    file = path.join(siteDir, "index.html");
  }
  response.writeHead(200, { "Content-Type": TYPES[path.extname(file).toLowerCase()] ?? "application/octet-stream" });
  fs.createReadStream(file).pipe(response);
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
siteUrl = `http://127.0.0.1:${server.address().port}/`;

const chrome = spawn(process.env.CHROMIUM ?? "chromium", ["--headless=new", "--no-sandbox", "--disable-gpu",
  "--disable-dev-shm-usage", "--no-first-run", "--no-default-browser-check", "--remote-debugging-port=0",
  "--user-data-dir=/tmp/probe-profile", "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
const timer = setTimeout(() => { chrome.kill("SIGKILL"); finish({ error: "probe timed out" }); }, 40_000);

const endpoint = await new Promise((resolve, reject) => {
  let buffer = "";
  chrome.stderr.on("data", (chunk) => {
    buffer += chunk;
    const match = buffer.match(/DevTools listening on (ws:\/\/\S+)/);
    if (match) resolve(match[1]);
  });
  chrome.on("exit", (code) => reject(new Error(`chromium exited (${code}): ${buffer.slice(-500)}`)));
}).catch((error) => finish({ error: error.message }));

const browser = new WebSocket(endpoint);
await new Promise((resolve) => browser.addEventListener("open", resolve));
let nextId = 1;
const pending = new Map();
const listeners = [];
browser.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (message.id && pending.has(message.id)) { pending.get(message.id)(message); pending.delete(message.id); }
  else listeners.forEach((listener) => listener(message));
});
const send = (method, params = {}, sessionId) => new Promise((resolve) => {
  const id = nextId++;
  pending.set(id, resolve);
  browser.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});

const { result: { targetId } } = await send("Target.createTarget", { url: "about:blank" });
const { result: { sessionId } } = await send("Target.attachToTarget", { targetId, flatten: true });
async function fulfillSupabase(event) {
  if (++apiCalls > 128 || activeApiCalls >= 8) {
    add('api-unavailable', 'Isolated Supabase validation capacity exceeded', null);
    await send('Fetch.failRequest', {requestId:event.requestId, errorReason:'BlockedByClient'}, sessionId);
    return;
  }
  activeApiCalls++;
  try {
    const reply = await forwardSupabase(event.request, supabaseOrigin, request => exchange(relayDir, request));
    if (reply.unavailable) throw Error('Private Supabase transport unavailable');
    await send('Fetch.fulfillRequest', {requestId:event.requestId, responseCode:reply.status,
      responseHeaders:supabaseResponseHeaders(reply, siteUrl), body:reply.body}, sessionId);
  } catch {
    add('api-unavailable', 'Isolated Supabase browser transport was unavailable; live connection was not used', null);
    await send('Fetch.failRequest', {requestId:event.requestId, errorReason:'Failed'}, sessionId);
  } finally { activeApiCalls--; }
}
let loaded = false;
const requestUrls = new Map();
const requestTypes = new Map();
// Browsers ask for a favicon on their own; a site without one is not broken.
const ignored = (url) => /\/favicon\.ico(\?|$)/.test(url ?? "");
// The first load runs before the backend-authored account exists, so the page's own API request is rightly refused.
let accountReady = !acceptanceData.authSetup;
const preAccountRefusal = (status, url) => !accountReady && status === 401 && (url ?? "").startsWith(siteUrl + "api/");
listeners.push((message) => {
  if (message.sessionId !== sessionId) return;
  const p = message.params ?? {};
  switch (message.method) {
    case 'Fetch.requestPaused': void fulfillSupabase(p); break;
    case "Runtime.exceptionThrown": {
      const d = p.exceptionDetails ?? {};
      const frame = d.stackTrace?.callFrames?.[0];
      add("exception", d.exception?.description ?? d.text ?? "Uncaught exception",
        frame ? `${frame.url.replace(siteUrl, "")}:${frame.lineNumber + 1}:${frame.columnNumber + 1} (${frame.functionName || "anonymous"})` : null);
      break;
    }
    case "Runtime.consoleAPICalled":
      if (p.type === "error" || p.type === "assert") {
        add("console", (p.args ?? []).map((a) => a.value ?? a.description ?? "").join(" ").trim() || "console.error");
      }
      break;
    case "Log.entryAdded":
      if (p.entry?.level === "error" && p.entry.source !== "network") add("console", p.entry.text, p.entry.url?.replace(siteUrl, ""));
      break;
    case "Network.requestWillBeSent":
      requestUrls.set(p.requestId, p.request?.url ?? "");
      requestTypes.set(p.requestId, p.type ?? '');
      break;
    case "Network.responseReceived":
      if (p.response?.status >= 400 && sameOrigin(p.response.url) && !ignored(p.response.url)
          && !preAccountRefusal(p.response.status, p.response.url)) {
        add("asset", `${p.response.status} ${p.response.url}`, null);
      }
      break;
    case "Network.loadingFailed": {
      const url = requestUrls.get(p.requestId) ?? "";
      if (supabaseOrigin && !p.canceled && !sameOrigin(url) && ['Fetch', 'XHR'].includes(requestTypes.get(p.requestId))) {
        add('api-unavailable', 'A data request did not use the isolated candidate connection', null);
      }
      if (!p.canceled && sameOrigin(url) && !ignored(url) && !/net::ERR_ABORTED/.test(p.errorText)) add("asset", `${p.errorText} ${url}`, null);
      break;
    }
    case 'Network.webSocketCreated':
      if (supabaseOrigin) add('api-unavailable', 'Realtime data validation requires its dedicated adapter', null);
      break;
    case "Page.loadEventFired":
      loaded = true;
      break;
    case "Page.javascriptDialogOpening":
      send("Page.handleJavaScriptDialog", { accept: true }, sessionId);
      break;
  }
});
for (const domain of ["Runtime.enable", "Log.enable", "Network.enable", "Page.enable"]) await send(domain, {}, sessionId);
if (supabaseOrigin) await send('Fetch.enable', {patterns:[{urlPattern:supabaseOrigin + '/*', requestStage:'Request'}]}, sessionId);
// Dialogs and new windows would block or escape the probe; a site using them is not broken for it.
await send("Page.addScriptToEvaluateOnNewDocument", { source:
  "window.alert = () => {}; window.confirm = () => true; window.prompt = () => ''; window.print = () => {}; window.open = () => null;" }, sessionId);
await send("Page.navigate", { url: siteUrl }, sessionId);
for (let waited = 0; !loaded && waited < 15_000; waited += 100) await new Promise((r) => setTimeout(r, 100));
await new Promise((r) => setTimeout(r, settleMs));

// Called only with backend-authored setup and unique disposable credentials. Return no token or response body.
async function setupAccount(config, credentials) {
  try {
    if (config.signup !== '/api/auth/signup' || config.login !== '/api/auth/login'
        || !/^rendra-session:[a-f0-9-]{36}$/.test(config.storageKey)) return false;
    const post = path => fetch(path, {method:'POST', redirect:'error', headers:{'Content-Type':'application/json'},
      body:JSON.stringify(credentials), signal:AbortSignal.timeout(8000)});
    const signup = await post(config.signup);
    if (signup.status !== 201) return false;
    const login = await post(config.login);
    if (login.status !== 200) return false;
    const session = await login.json();
    if (typeof session.token !== 'string' || !session.token || session.token.length > 8192
        || typeof session.userId !== 'string' || !session.userId || session.userId.length > 128) return false;
    localStorage.setItem(config.storageKey, JSON.stringify({token:session.token, userId:session.userId, email:credentials.email}));
    return true;
  } catch { return false; }
}
if (loaded && acceptanceData.authSetup) {
  const credentials = {email:`browser-${randomUUID()}@example.invalid`, password:randomUUID()};
  const seeded = await send('Runtime.evaluate', {returnByValue:true, awaitPromise:true,
    expression:`(${setupAccount.toString()})(${JSON.stringify(acceptanceData.authSetup)},${JSON.stringify(credentials)})`}, sessionId);
  if (seeded.result?.result?.value !== true) {
    add('auth-setup', 'Fresh candidate account setup failed', null);
    loaded = false;
  } else {
    loaded = false;
    accountReady = true;
    await send('Page.navigate', {url:siteUrl}, sessionId);
    for (let waited=0; !loaded && waited<10000; waited+=100) await new Promise(resolve=>setTimeout(resolve,100));
    await new Promise(resolve=>setTimeout(resolve,settleMs));
  }
}

// A page that rendered nothing visible is broken even without an error (for example an empty #root).
const evaluation = await send("Runtime.evaluate", { returnByValue: true, expression: `(() => {
  const text = (document.body?.innerText ?? "").trim();
  const media = [...document.querySelectorAll("img,canvas,svg,video,iframe,input,button")]
    .filter((el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }).length;
  return { textLength: text.length, media, title: document.title };
})()` }, sessionId);
const page = evaluation.result?.result?.value ?? {};
if (!loaded) add("load", "The page did not finish loading", null);
const blank = (page.textLength ?? 0) === 0 && (page.media ?? 0) === 0;
if (blank) add("blank", "The page rendered nothing visible", null);

// Evidence is local to this offline probe. Page text and screenshot pixels never become instructions.
const capture = async (name, width, height, mobile) => {
  await send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor:1, mobile}, sessionId);
  await new Promise(resolve => setTimeout(resolve, 150));
  if (evidenceDir) {
    const result = await send('Page.captureScreenshot', {format:'png', captureBeyondViewport:false,
      clip:{x:0,y:0,width,height,scale:1}}, sessionId);
    if (typeof result.result?.data === 'string') {
      const bytes = Buffer.from(result.result.data, 'base64');
      if (bytes.length > 0 && bytes.length <= 2 * 1024 * 1024) {
        fs.writeFileSync(path.join(evidenceDir, name + '.png'), bytes, {flag:'wx'});
        visual.captures.push({name, width, height, bytes:bytes.length, sha256:createHash('sha256').update(bytes).digest('hex')});
      }
    }
  }
  const audit = await send('Runtime.evaluate', {returnByValue:true, expression:`(() => {
    const visible = el => { const r=el.getBoundingClientRect(); return r.width>0 && r.height>0 && getComputedStyle(el).visibility!=='hidden'; };
    const named = el => Boolean((el.getAttribute('aria-label') || '').trim()
      || (el.getAttribute('aria-labelledby') || '').split(/\\s+/).some(id => document.getElementById(id)?.textContent?.trim())
      || [...(el.labels || [])].some(label => label.textContent?.trim())
      || ((el.tagName==='BUTTON' || el.tagName==='A') && (el.textContent?.trim() || el.querySelector('img[alt]:not([alt=""])')))
      || (el.tagName==='INPUT' && ['submit','button','reset'].includes(el.type) && el.value));
    return [...document.querySelectorAll('button,input:not([type=hidden]),textarea,select,a[href]')]
      .filter(el => visible(el) && !named(el)).slice(0,5)
      .map(el => 'A visible '+el.tagName.toLowerCase()+' needs an accessible name or associated label');
  })()`}, sessionId);
  for (const warning of audit.result?.result?.value || []) {
    const message = String(warning) + ' at ' + width + 'px';
    if (visual.warnings.length < 10 && !visual.warnings.includes(message)) {
      visual.warnings.push(message);
    }
  }
};
if (loaded && !blank) {
  await capture('desktop', 1440, 900, false);
  await capture('mobile', 375, 812, true);
  await send('Emulation.clearDeviceMetricsOverride', {}, sessionId);
}

// Production baseline: a phone-width (375 px) layout must not scroll sideways. A measurement that cannot be
// read is reported as such, never taken as "no overflow". Run on load and again on the state acceptance reached.
const measureMobile = async (where) => {
  await send("Emulation.setDeviceMetricsOverride", { width: 375, height: 812, deviceScaleFactor: 1, mobile: true }, sessionId);
  await new Promise((r) => setTimeout(r, 300));
  const measured = await send("Runtime.evaluate", { returnByValue: true, expression:
    "Math.max(document.documentElement.scrollWidth, document.body?.scrollWidth ?? 0) - window.innerWidth" }, sessionId);
  const overflow = measured.result?.result?.value;
  if (measured.result?.exceptionDetails || typeof overflow !== "number" || !Number.isFinite(overflow)) {
    add("layout", `The phone-width (375 px) layout could not be measured ${where}`, null);
  } else if (overflow > 1) {
    add("layout", `The page scrolls sideways at 375 px wide ${where} (${overflow} px too wide)`, null);
  }
  await send("Emulation.clearDeviceMetricsOverride", {}, sessionId);
  await new Promise((r) => setTimeout(r, 150));
};
if (loaded && !blank) await measureMobile("on load");

// Exercise each visible control once. Links are not followed (that would leave the page); a control
// is found again by its position each time, since the page may re-render after every action.
const CONTROLS = `[...document.querySelectorAll('button, [role=button], [role=tab], [role=switch], [role=checkbox], summary, select, textarea, '
  + 'input:not([type]), input[type=text], input[type=email], input[type=number], input[type=search], input[type=tel], '
  + 'input[type=url], input[type=password], input[type=checkbox], input[type=radio], input[type=range], input[type=date]')]
  .filter((el) => !el.disabled && !el.closest('[aria-disabled=true]') && el.getClientRects().length > 0)`;
// perform=false only names the control, so issues raised while it acts are attributed to it.
const ACT = (index, perform) => `(() => {
  const el = ${CONTROLS}[${index}];
  if (!el) return null;
  const tag = el.tagName.toLowerCase();
  const kind = tag === 'input' ? (el.type || 'text') + ' input' : tag;
  const label = (el.getAttribute('aria-label') || el.innerText || el.placeholder || el.name || el.value || '').trim().replace(/\\s+/g, ' ').slice(0, 40);
  const setValue = (value) => {
    const proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : tag === 'select' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };
  const name = label ? kind + ' "' + label.replaceAll('"', "'") + '"' : kind;
  if (!${perform}) return name;
  el.scrollIntoView({ block: 'center' });
  if (tag === 'select') { if (el.options.length > 1) setValue(el.options[el.options.length - 1].value); }
  else if (tag === 'textarea' || (tag === 'input' && !['checkbox', 'radio', 'range', 'date'].includes(el.type))) {
    el.focus();
    setValue(el.type === 'number' ? '3' : el.type === 'email' ? 'test@example.com' : el.type === 'url' ? 'https://example.com' : 'Test');
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  }
  else if (el.type === 'range') setValue(el.max || '1');
  else if (el.type === 'date') setValue('2026-01-15');
  else el.click();
  return name;
})()`;
const visibleNow = async () => {
  const check = await send("Runtime.evaluate", { returnByValue: true, expression:
    "(document.body?.innerText ?? '').trim().length + document.querySelectorAll('img,canvas,svg,video,iframe,input,button').length" }, sessionId);
  return (check.result?.result?.value ?? 1) > 0;
};
const interactions = [];
async function reloadPage() {
  loaded = false;
  await send('Page.reload', {ignoreCache:true}, sessionId);
  const deadline = Date.now() + 10000;
  while (!loaded && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50));
  await new Promise(resolve => setTimeout(resolve, settleMs));
  return loaded;
}
const outcomes = !blank && loaded ? await runOutcomes(outcomeDefinitions, {
  async evaluate(definition, phase, fixture) {
    const expression = `(${outcomeDom.toString()})(${JSON.stringify(definition)},${JSON.stringify(phase)},${JSON.stringify(fixture)})`;
    const result = await send('Runtime.evaluate', {returnByValue:true, expression}, sessionId);
    if (result.error || result.result?.exceptionDetails) throw Error('Independent outcome evaluation unavailable');
    return result.result?.result?.value;
  },
  reload:reloadPage,
}, {budgetMs:acceptanceData.outcomeBudgetMs || 12000, stepTimeoutMs:acceptanceData.outcomeStepTimeoutMs || 2000})
  : outcomeDefinitions.map(definition => ({id:definition.id, outcome:'SKIPPED'}));
if (!blank && loaded && acceptanceActions.length === 0) {
  const deadline = Date.now() + 6_000;
  for (let index = 0; index < 20 && Date.now() < deadline; index++) {
    const named = await send("Runtime.evaluate", { returnByValue: true, expression: ACT(index, false) }, sessionId);
    const label = named.result?.result?.value;
    if (!label) break;   // no more controls
    action = label;
    interactions.push(label);
    const acted = await send("Runtime.evaluate", { returnByValue: true, expression: ACT(index, true) }, sessionId);
    if (acted.result?.exceptionDetails) add("exception", acted.result.exceptionDetails.exception?.description ?? "Interaction failed", null);
    await new Promise((r) => setTimeout(r, 250));
    const stillVisible = await visibleNow();
    if (!stillVisible) { add("exception", "The app crashed: the page went blank", null); action = null; break; }
    action = null;
  }
}

const acceptance = [];
for (const expected of acceptanceActions) {
  if (!loaded || blank) { acceptance.push({ id: expected.id, passed: false }); continue; }
  if (expected.kind === 'RELOAD') {
    loaded = false;
    await send('Page.reload', {ignoreCache:true}, sessionId);
    const deadline = Date.now() + 10000;
    while (!loaded && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50));
    acceptance.push({id:expected.id, passed:loaded});
    await new Promise(resolve => setTimeout(resolve, 150));
    continue;
  }
  const expression = `(() => {
    const a = ${JSON.stringify(expected)};
    // A hash router ("#/classes", which works under any hosting prefix) and a path router both count.
    if (a.kind === 'ASSERT_ROUTE') return (location.hash.startsWith('#/') ? location.hash.slice(1).split('?')[0] : location.pathname) === a.value;
    if (a.kind === 'ASSERT_COUNT') return document.querySelectorAll(a.selector).length === Number(a.value);
    if (a.kind === 'ASSERT_EXISTS') return document.querySelectorAll(a.selector).length >= 1;
    const el = document.querySelector(a.selector); if (!el) return false;
    if (a.kind === 'ASSERT_TEXT') return (el.textContent ?? '').includes(a.value);
    if (a.kind === 'CLICK') { if (typeof el.click !== 'function') return false; el.click(); return true; }
    if (a.kind === 'FILL' || a.kind === 'SELECT') {
      const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set;
      if (!setter) return false; setter.call(el, a.value);
      el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); return true;
    }
    return false;
  })()`;
  let passed = false;
  const until = Date.now() + 5000;
  do {
    const result = await send('Runtime.evaluate', { returnByValue: true, expression }, sessionId);
    passed = result.result?.result?.value === true;
    if (passed) break;
    await new Promise(r => setTimeout(r, 100));
  } while (Date.now() < until);
  acceptance.push({ id: expected.id, passed });
  if (!passed) {
    // Preserve required coverage and the first actionable failure instead of spending the whole
    // probe deadline on controls that cannot exist after an earlier prerequisite failed.
    for (const remaining of acceptanceActions.slice(acceptance.length)) acceptance.push({id:remaining.id, passed:false, prerequisiteFailed:true});
    break;
  }
  await new Promise(r => setTimeout(r, 150));
}
// A failed model assertion is advisory, but a page that disappeared during any recipe is a runtime failure.
if (loaded && !await visibleNow()) add("blank", "The page went blank after browser interactions", null);
if (loaded && !blank && acceptance.length > 0 && acceptance.every((a) => a.passed)) await measureMobile("after the required steps");
clearTimeout(timer);
chrome.kill("SIGKILL");
server.close();
finish({ loaded, page, interactions, acceptance, outcomes });
