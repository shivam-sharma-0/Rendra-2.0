import http from 'node:http';
import net from 'node:net';

// Only backend-selected internal services; no host mounts, customer credentials, arbitrary targets or redirect following.
const routes = [
  {prefix:'/auth/v1', host:process.env.AUTH_HOST, port:9999},
  {prefix:'/rest/v1', host:process.env.REST_HOST, port:3000},
];
const server = http.createServer((request, response) => {
  const target = routes.find(route => request.url === route.prefix || request.url.startsWith(route.prefix + '/') || request.url.startsWith(route.prefix + '?'));
  if (!target || /\/admin(?:\/|\?|$)/.test(request.url)) { response.writeHead(404).end(); return; }
  const upstream = http.request({host:target.host, port:target.port, method:request.method,
    path:request.url.slice(target.prefix.length) || '/', headers:{...request.headers, host:target.host}, timeout:5000}, result => {
    response.writeHead(result.statusCode, result.headers);
    result.pipe(response);
  });
  upstream.on('timeout', () => upstream.destroy());
  upstream.on('error', () => { if (!response.headersSent) response.writeHead(503); response.end(); });
  request.on('aborted', () => upstream.destroy());
  request.pipe(upstream);
});
server.listen(8000, '0.0.0.0');

// Only this backend-owned proxy joins the host-facing bridge. Database/auth/REST stay on the internal network.
net.createServer(client => {
  const upstream = net.connect({host:process.env.DB_HOST, port:5432});
  client.setTimeout(30000, () => client.destroy());
  upstream.setTimeout(30000, () => upstream.destroy());
  client.on('error', () => upstream.destroy());
  upstream.on('error', () => client.destroy());
  client.on('close', () => upstream.destroy());
  upstream.on('close', () => client.destroy());
  client.pipe(upstream).pipe(client);
}).listen(5433, '0.0.0.0');
