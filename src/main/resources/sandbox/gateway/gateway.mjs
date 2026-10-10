// Trusted relay between the backend and the generated app servers. It runs on the host network, so the
// backend reaches it on loopback (even from Windows, through WSL's mirrored networking), while the app
// servers live on an internal Docker network with no route out.
//  - HTTP: 127.0.0.1:GATEWAY_PORT forwards a request to the app server named by the x-codepal-upstream
//    header ("ip:port"), which must be inside APPS_SUBNET: the gateway reaches nothing else.
//  - MongoDB: MONGO_LISTEN (loopback) relays to the apps' own database container (MONGO_TARGET), so the
//    backend can create each project's database user. Apps reach that container directly on the network.
import http from "node:http";
import net from "node:net";

const PORT = Number(process.env.GATEWAY_PORT ?? 55400);
const SUBNET = process.env.APPS_SUBNET ?? "";
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
  if (!inSubnet(host) || !(port >= 1024 && port <= 65535)) {
    response.writeHead(403, { "content-type": "text/plain" }).end("upstream not allowed");
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
