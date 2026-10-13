import http from 'node:http';
import https from 'node:https';
import { readFile, stat } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { resolve, extname, sep } from 'node:path';

/** Built static assets and same-origin HTTP/WebSocket proxy; no Vite development server. */
export async function serve({ root, backend, host = '127.0.0.1', port = 0, cert, key }) {
  const base = resolve(root);
  const upstream = new URL(backend);
  if (upstream.protocol !== 'http:') throw new Error('Backend must be a private HTTP listener');
  const sockets = new Set();
  const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
  const handle = async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'same-origin');
    if (req.url.startsWith('/api/')) {
      const proxy = http.request({ hostname: upstream.hostname, port: upstream.port, path: req.url, method: req.method, headers: req.headers, timeout: 10_000 }, reply => {
        res.writeHead(reply.statusCode, reply.headers); reply.pipe(res);
      });
      proxy.on('timeout', () => proxy.destroy());
      proxy.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
      req.pipe(proxy); return;
    }
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405).end(); return; }
    try {
      const path = decodeURIComponent(new URL(req.url, 'http://local').pathname);
      let file = resolve(base, '.' + path);
      if (file !== base && !file.startsWith(base + sep)) { res.writeHead(403).end(); return; }
      try { if (!(await stat(file)).isFile()) file = resolve(base, 'index.html'); }
      catch { if (extname(path)) { res.writeHead(404).end(); return; } file = resolve(base, 'index.html'); }
      const body = await readFile(file);
      res.setHeader('Content-Type', mime[extname(file)] || 'application/octet-stream');
      res.setHeader('Cache-Control', extname(file) === '.html' ? 'no-store' : 'public, max-age=3600');
      res.setHeader('Content-Length', body.length);
      res.end(req.method === 'HEAD' ? undefined : body);
    } catch { res.writeHead(400).end(); }
  };
  const server = cert && key ? https.createServer({ cert: readFileSync(cert), key: readFileSync(key), minVersion: 'TLSv1.2' }, handle) : http.createServer(handle);
  server.maxConnections = 512; server.headersTimeout = 10_000; server.requestTimeout = 15_000;
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  server.on('upgrade', (req, client, head) => {
    if (req.url !== '/ws') { client.end('HTTP/1.1 404 Not Found\r\n\r\n'); return; }
    const proxy = http.request({ hostname: upstream.hostname, port: upstream.port, path: '/ws', method: 'GET', headers: req.headers, timeout: 10_000 });
    proxy.on('upgrade', (reply, remote, remoteHead) => {
      proxy.setTimeout(0); sockets.add(remote); remote.on('close', () => sockets.delete(remote));
      client.write(`HTTP/1.1 ${reply.statusCode} ${reply.statusMessage}\r\n`);
      for (const [name, value] of Object.entries(reply.headers)) {
        for (const entry of Array.isArray(value) ? value : [value]) client.write(`${name}: ${entry}\r\n`);
      }
      client.write('\r\n'); if (remoteHead.length) client.write(remoteHead); if (head.length) remote.write(head);
      remote.pipe(client); client.pipe(remote);
      remote.on('error', () => client.destroy()); client.on('error', () => remote.destroy());
      remote.on('close', () => client.destroy()); client.on('close', () => remote.destroy());
    });
    proxy.on('response', reply => { client.end(`HTTP/1.1 ${reply.statusCode} Rejected\r\nConnection: close\r\n\r\n`); reply.resume(); });
    proxy.on('timeout', () => proxy.destroy()); proxy.on('error', () => client.destroy()); proxy.end();
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, host, resolve); });
  return { url: `${cert ? 'https' : 'http'}://${host}:${server.address().port}`, close: async () => {
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => server.close(resolve));
  } };
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname)) {
  const app = await serve({ root: process.env.XIM_STATIC_ROOT || 'frontend/dist', backend: process.env.XIM_BACKEND_URL || 'http://127.0.0.1:8080',
    host: process.env.XIM_STATIC_HOST || '127.0.0.1', port: Number(process.env.XIM_STATIC_PORT || 5173), cert: process.env.XIM_TLS_CERT, key: process.env.XIM_TLS_KEY });
  console.log(`XIM static site: ${app.url}`);
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, async () => { await app.close(); process.exit(0); });
}
