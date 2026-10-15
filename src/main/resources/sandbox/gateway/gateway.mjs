// Trusted relay between the backend and the generated app servers. It runs on the host network, so the
// backend reaches it on loopback (even from Windows, through WSL's mirrored networking), while the app
// servers live on an internal Docker network with no route out.
//  - HTTP: 127.0.0.1:GATEWAY_PORT forwards a request to the app server named by the x-codepal-upstream
//    header ("ip:port"), with a short-lived backend HMAC. App processes have separate private networks.
//  - MongoDB: MONGO_LISTEN (loopback) relays to the apps' own database container (MONGO_TARGET), so the
//    backend can create each project's database user. Apps reach that container directly on the network.
import http from "node:http";
import net from "node:net";
import { createHmac, timingSafeEqual } from "node:crypto";

const PORT = Number(process.env.GATEWAY_PORT ?? 55400);
const SUBNET = process.env.APPS_SUBNET ?? "";
const RELAY_KEY=process.env.API_RELAY_KEY??"";
if(!/^[a-f0-9]{64}$/.test(RELAY_KEY))throw new Error("Trusted API relay key is required");
const [MONGO_HOST, MONGO_PORT] = (process.env.MONGO_TARGET ?? "").split(":");
const [LISTEN_HOST, LISTEN_PORT] = (process.env.MONGO_LISTEN ?? "127.0.0.1:55417").split(":");
const HOP = new Set(["connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer", "host"]);

const toNumber = (ip) => ip.split(".").reduce((value, part) => (value << 8) + Number(part), 0) >>> 0;
const [subnetBase, subnetBits] = SUBNET.split("/");
const mask = subnetBits ? (~0 << (32 - Number(subnetBits))) >>> 0 : 0;
const inSubnet = (address) => {
  const ip = String(address ?? "").replace(/^::ffff:/, "");
  if (!SUBNET || !/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return false;
  return ((toNumber(ip) & mask) >>> 0) === ((toNumber(subnetBase) & mask) >>> 0);
};

http.createServer((request, response) => {
  if (request.url === "/__gateway/health") {
    response.writeHead(200, { "content-type": "text/plain" }).end(`ok ${SUBNET}`);
    return;
  }
  const [host, portText] = String(request.headers["x-codepal-upstream"] ?? "").split(":");
  const port = Number(portText);
  const expires=Number(request.headers['x-codepal-expires']);
  const signature=String(request.headers['x-codepal-signature']??'');
  const expected=createHmac('sha256',Buffer.from(RELAY_KEY,'hex')).update(host+':'+portText+'\n'+expires).digest();
  const actual=/^[a-f0-9]{64}$/.test(signature)?Buffer.from(signature,'hex'):Buffer.alloc(0);
  const ip=/^\d{1,3}(\.\d{1,3}){3}$/.test(host) && host.split('.').every(part=>Number(part)<=255);
  if (!ip || !(port >= 1024 && port <= 65535) || !Number.isSafeInteger(expires) || expires<Date.now()/1000 || expires>Date.now()/1000+601 || actual.length!==expected.length || !timingSafeEqual(expected,actual)) {
    response.writeHead(403, { "content-type": "text/plain", "x-codepal-relay-error":"1" }).end("upstream not allowed");
    return;
  }
  const headers = {};
  for (const [name, value] of Object.entries(request.headers)) {
    if (!HOP.has(name) && !name.startsWith("x-codepal-")) headers[name] = value;
  }
  headers.host = `${host}:${port}`;
  const upstream = http.request({ host, port, method: request.method, path: request.url, headers, timeout: 30_000 }, (reply) => {
    const out = {};
    for (const [name, value] of Object.entries(reply.headers)) if (!HOP.has(name)) out[name] = value;
    response.writeHead(reply.statusCode ?? 502, out);
    reply.pipe(response);
  });
  upstream.on("timeout", () => upstream.destroy(new Error("ETIMEDOUT")));
  upstream.on("error", (error) => {
    if (!response.headersSent) response.writeHead(502, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: "The app server is not reachable", code: error.code ?? error.message }));
  });
  request.pipe(upstream);
}).listen(PORT, "127.0.0.1", () => console.log(`gateway http 127.0.0.1:${PORT} subnet=${SUBNET}`));

if (MONGO_HOST && inSubnet(MONGO_HOST)) {
  net.createServer((client) => {
    const database = net.connect(Number(MONGO_PORT), MONGO_HOST);
    client.pipe(database).pipe(client);
    database.on("error", () => client.destroy());
    client.on("error", () => database.destroy());
  }).listen(Number(LISTEN_PORT), LISTEN_HOST, () => console.log(`gateway mongo ${LISTEN_HOST}:${LISTEN_PORT} -> ${MONGO_HOST}:${MONGO_PORT}`));
}
