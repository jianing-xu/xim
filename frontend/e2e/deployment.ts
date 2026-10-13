import { spawn, execFile, execFileSync, type ChildProcess } from 'node:child_process';
import { mkdtempSync, readFileSync, createWriteStream } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import net from 'node:net';
import { promisify } from 'node:util';
import { test as base, expect } from '@playwright/test';
// @ts-expect-error Plain Node deployment module is also the local production-asset server.
import { serve } from '../../deployment/static-server.mjs';

const root = resolve(import.meta.dirname, '../..');
async function freePort() {
  const server = net.createServer(); await new Promise<void>(r => server.listen(0, '127.0.0.1', r));
  const port = (server.address() as net.AddressInfo).port; await new Promise<void>(r => server.close(() => r())); return port;
}
export class Deployment {
  dir = mkdtempSync(join(tmpdir(), 'xim-e2e-'));
  database = join(this.dir, 'chat.db');
  backend?: ChildProcess;
  site: any;
  port = 0;
  url = '';
  options: string[] = [];
  async start() {
    this.port = await freePort();
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=127.0.0.1',
      '-addext', 'subjectAltName=IP:127.0.0.1,DNS:localhost', '-keyout', join(this.dir, 'key.pem'), '-out', join(this.dir, 'cert.pem')], { stdio: 'ignore' });
    this.site = await serve({ root: join(root, 'frontend/dist'), backend: `http://127.0.0.1:${this.port}`,
      cert: join(this.dir, 'cert.pem'), key: join(this.dir, 'key.pem') });
    this.url = this.site.url; await this.startBackend();
    await promisify(execFile)('curl', ['--max-time', '10', '--noproxy', '*', '--fail', '--silent', '--cacert', join(this.dir, 'cert.pem'), this.url + '/api/health']);
  }
  async startBackend() {
    const cp = join(root, 'backend/target/classes') + ':' + readFileSync(join(root, 'backend/target/classpath.txt'), 'utf8').trim();
    this.backend = spawn('java', ['-Dxim.host=127.0.0.1', '-Dxim.port=0', `-Dxim.web.port=${this.port}`,
      `-Dxim.db.sqlite.path=${this.database}`, `-Dxim.log.dir=${this.dir}/logs`, '-Dxim.auth.demo=false',
      '-Dxim.web.cookieSecure=true', `-Dxim.web.allowedOrigins=${this.url}`, ...this.options, '-cp', cp, 'cn.xjn.xim.server.NettyServer'], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
    const log = createWriteStream(join(this.dir, 'backend.log'), { flags: 'a' });
    this.backend.stdout?.pipe(log); this.backend.stderr?.pipe(log, { end: false });
    for (let i = 0; i < 100; i++) {
      if (this.backend.exitCode !== null) throw new Error(`Backend exited; inspect ${this.dir}/backend.log`);
      try { if ((await fetch(`http://127.0.0.1:${this.port}/api/health`)).ok) return; } catch { /* startup */ }
      await new Promise(r => setTimeout(r, 100));
    }
    throw new Error('Backend startup timed out');
  }
  async stopBackend() {
    const process = this.backend; if (!process || process.exitCode !== null) return;
    await new Promise<void>(resolve => {
      const timer = setTimeout(() => process.kill('SIGKILL'), 12_000);
      process.once('exit', () => { clearTimeout(timer); resolve(); }); process.kill('SIGTERM');
    });
  }
  seedContacts(count: number) {
    // Only this fixture's isolated database. Copy a real registration's hash for directory fixtures.
    execFileSync('python3', ['-c', `import sqlite3,sys
with sqlite3.connect(sys.argv[1]) as db:
    password_hash=db.execute("SELECT password_hash FROM xim_users WHERE password_hash IS NOT NULL LIMIT 1").fetchone()[0]
    for i in range(int(sys.argv[2])):
        db.execute("INSERT INTO xim_users(user_id,username,password_hash,display_name) VALUES(?,?,?,?)", ('fixture-%03d'%i,'fixture_%03d'%i,password_hash,'跨页伙伴 %03d'%i))
`, this.database, String(count)]);
  }
  backup() {
    const snapshot = join(this.dir, 'backup.db');
    execFileSync('python3', [join(root, 'scripts/sqlite-backup.py'), this.database, snapshot]); return snapshot;
  }
  async restore(snapshot: string) {
    await this.stopBackend();
    // A new path avoids mixing a restored snapshot with the previous DB's WAL/SHM.
    const restored = join(this.dir, 'restored.db');
    execFileSync('python3', [join(root, 'scripts/sqlite-backup.py'), snapshot, restored]);
    this.database = restored; await this.startBackend();
  }
  async close() { await this.stopBackend(); await this.site?.close(); }
}
export const test = base.extend<{ deployment: Deployment }>({
  deployment: async ({}, use, info) => {
    const deployment = new Deployment();
    try { await deployment.start(); await use(deployment); }
    finally {
      await deployment.close();
      await info.attach('deployment-path', { body: deployment.dir, contentType: 'text/plain' });
    }
  },
});
export { expect };
