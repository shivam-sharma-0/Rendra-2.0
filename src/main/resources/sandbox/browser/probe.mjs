// Loads a built site in headless Chromium and reports what went wrong at runtime, as one JSON line.
// Usage: node probe.mjs <site-dir> [settle-ms]. Runs with no network: the site is served on loopback.
// Uses only Node built-ins (http, child_process, global WebSocket) and the Chrome DevTools Protocol.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";

const siteDir = path.resolve(process.argv[2] ?? "/site");
const settleMs = Number(process.argv[3] ?? 1500);
const MAX_ISSUES = 20;
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css",
  ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
  ".gif": "image/gif", ".webp": "image/webp", ".ico": "image/x-icon", ".woff2": "font/woff2", ".woff": "font/woff",
  ".wasm": "application/wasm", ".txt": "text/plain", ".map": "application/json" };

const issues = [];
let siteUrl = "";
const add = (kind, message, source) => {
  message = String(message).replaceAll(siteUrl, "");
  if (issues.length < MAX_ISSUES && !issues.some((i) => i.kind === kind && i.message === message)) {
    issues.push({ kind, message: message.slice(0, 600), source: source ?? null });
  }
};
const finish = (extra) => {
  process.stdout.write(JSON.stringify({ issues, ...extra }) + "\n");
  process.exit(0);
};

// Static server with SPA fallback, like the real preview.
const server = http.createServer((request, response) => {
  const url = new URL(request.url, "http://localhost");
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
const timer = setTimeout(() => { chrome.kill("SIGKILL"); finish({ error: "probe timed out" }); }, 25_000);

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
let loaded = false;
const requestUrls = new Map();
// Browsers ask for a favicon on their own; a site without one is not broken.
const ignored = (url) => /\/favicon\.ico(\?|$)/.test(url ?? "");
listeners.push((message) => {
  if (message.sessionId !== sessionId) return;
  const p = message.params ?? {};
  switch (message.method) {
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
      break;
    case "Network.responseReceived":
      if (p.response?.status >= 400 && !ignored(p.response.url)) add("asset", `${p.response.status} ${p.response.url}`, null);
      break;
    case "Network.loadingFailed": {
      const url = requestUrls.get(p.requestId) ?? "";
      if (!p.canceled && !ignored(url) && !/net::ERR_ABORTED/.test(p.errorText)) add("asset", `${p.errorText} ${url}`, null);
      break;
    }
    case "Page.loadEventFired":
      loaded = true;
      break;
  }
});
for (const domain of ["Runtime.enable", "Log.enable", "Network.enable", "Page.enable"]) await send(domain, {}, sessionId);
await send("Page.navigate", { url: siteUrl }, sessionId);
for (let waited = 0; !loaded && waited < 15_000; waited += 100) await new Promise((r) => setTimeout(r, 100));
await new Promise((r) => setTimeout(r, settleMs));

// A page that rendered nothing visible is broken even without an error (for example an empty #root).
const evaluation = await send("Runtime.evaluate", { returnByValue: true, expression: `(() => {
  const text = (document.body?.innerText ?? "").trim();
  const media = [...document.querySelectorAll("img,canvas,svg,video,iframe,input,button")]
    .filter((el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }).length;
  return { textLength: text.length, media, title: document.title };
})()` }, sessionId);
const page = evaluation.result?.result?.value ?? {};
if (!loaded) add("load", "The page did not finish loading", null);
if ((page.textLength ?? 0) === 0 && (page.media ?? 0) === 0) add("blank", "The page rendered nothing visible", null);

clearTimeout(timer);
chrome.kill("SIGKILL");
server.close();
finish({ loaded, page });
