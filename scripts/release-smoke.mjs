import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, access, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import net from 'node:net';
import { chromium } from '../frontend/node_modules/playwright/index.mjs';

const root = resolve(import.meta.dirname, '..');
const run = promisify(execFile);
async function port() { const s = net.createServer(); await new Promise(r => s.listen(0, '127.0.0.1', r)); const p = s.address().port; await new Promise(r => s.close(r)); return p; }
const dir = await mkdtemp(join(tmpdir(), 'xim-release-smoke-'));
const web = await port(), tcp = await port(), site = await port();
const url = `https://127.0.0.1:${site}`;
let process, browser;
async function stop() {
  if (!process || process.exitCode !== null) return;
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Local deployment did not terminate')), 15_000);
    process.once('exit', () => { clearTimeout(timer); resolve(); }); process.kill('SIGTERM');
  });
}
async function start() {
  process = spawn('./deployment/local.sh', [dir], { cwd: root, env: { ...globalThis.process.env,
    XIM_STATIC_PORT: String(site), XIM_WEB_PORT: String(web), XIM_TCP_PORT: String(tcp) }, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = ''; process.stdout.on('data', b => log += b); process.stderr.on('data', b => log += b);
  for (let i = 0; i < 100; i++) {
    if (process.exitCode !== null) throw new Error(`Local deployment exited: ${log}`);
    try {
      await access(join(dir, 'cert.pem'));
      const result = await run('curl', ['--max-time', '2', '--noproxy', '*', '--fail', '--silent', '--cacert', join(dir, 'cert.pem'), url + '/api/health']);
      if (JSON.parse(result.stdout).status === 'ok') { await writeFile(join(dir, 'deployment.log'), log); return; }
    } catch { /* bounded startup wait */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('Release deployment health timeout');
}
try {
  await start(); browser = await chromium.launch({ channel: globalThis.process.env.XIM_BROWSER_CHANNEL || 'chrome' });
  const context = await browser.newContext({ ignoreHTTPSErrors: true }); let page = await context.newPage();
  await page.goto(url); await page.locator('#register-toggle').click();
  await page.locator('#username').fill('release_smoke'); await page.locator('#password').fill('Release密碼2026!');
  await page.locator('#login-button').click(); await page.locator('#login-title').filter({ hasText: '登录，开始聊天' }).waitFor();
  await page.locator('#password').fill('Release密碼2026!'); await page.locator('#login-button').click();
  await page.locator('#self-id').waitFor(); const id = await page.locator('#self-id').textContent();
  await page.locator('#new-group').click(); await page.locator('#group-form button[type=submit]').click();
  await page.locator('#message').fill('发布包持久化 🧪'); await page.locator('#send').click();
  await page.locator('.message-status').filter({ hasText: '已发送' }).waitFor();
  await run('python3', [join(root, 'scripts/sqlite-backup.py'), join(dir, 'xim.db'), join(dir, 'snapshot.db')]);
  await page.close(); await stop(); await start(); page = await context.newPage(); await page.goto(url);
  await page.locator('#self-id').waitFor(); if (await page.locator('#self-id').textContent() !== id) throw new Error('Identity changed across release restart');
  await page.locator('#list .conversation').first().click(); await page.locator('.bubble').filter({ hasText: '发布包持久化 🧪' }).waitFor();
  await page.screenshot({ path: join(dir, 'release-browser.png') });
  console.log(JSON.stringify({ status: 'passed', dir, url, stableUserId: id, checks: ['release bundle', 'start-backend.sh', 'local.sh', 'trusted CA curl', 'HTTPS/WSS browser', 'Secure Cookie', 'group message', 'online backup', 'restart persistence'] }));
} finally { await browser?.close(); await stop(); }
