import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { loadEnv } from 'vite';

const cwd = fileURLToPath(new URL('..', import.meta.url));
const [platform, action, ...extra] = process.argv.slice(2);
if (!['desktop', 'macos', 'windows', 'android', 'ios'].includes(platform) || !['init', 'dev', 'build'].includes(action)) {
  throw new Error('Usage: node scripts/app.mjs <desktop|macos|windows|android|ios> <init|dev|build> [Tauri options]');
}
if (platform === 'macos' && process.platform !== 'darwin' || platform === 'windows' && process.platform !== 'win32' || platform === 'ios' && process.platform !== 'darwin') {
  throw new Error(`${platform} must be built on its native host (see docs/APP_PACKAGING.md)`);
}
if (action === 'init' && !['android', 'ios'].includes(platform)) throw new Error('init is only needed for mobile');
const env = { ...loadEnv('app', cwd, 'XIM_APP_'), ...process.env };
if (action !== 'init') {
  env.XIM_APP_SERVER_URL ||= action === 'dev' ? 'http://127.0.0.1:8080' : '';
  if (!env.XIM_APP_SERVER_URL) throw new Error('Set XIM_APP_SERVER_URL=https://your-service.example (or frontend/.env.app.local) before building');
  const url = new URL(env.XIM_APP_SERVER_URL);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('XIM_APP_SERVER_URL must be an HTTP(S) origin with no credentials, path, query or fragment');
  }
  if (url.protocol !== 'https:' && action === 'build' && env.XIM_APP_ALLOW_HTTP !== '1') {
    throw new Error('Release App requires HTTPS. Local validation only: explicitly set XIM_APP_ALLOW_HTTP=1');
  }
}
const args = ['android', 'ios'].includes(platform) ? [platform, action] : [action];
if (platform === 'macos' && action === 'build' && !extra.includes('--bundles')) args.push('--bundles', 'app,dmg');
if (platform === 'windows' && action === 'build' && !extra.includes('--bundles')) args.push('--bundles', 'nsis,msi');
const cli = resolve(cwd, 'node_modules/@tauri-apps/cli/tauri.js');
const result = spawnSync(process.execPath, [cli, ...args, ...extra], { cwd, env, stdio: 'inherit' });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
