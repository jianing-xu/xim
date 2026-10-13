import { test, expect } from './deployment';
import type { Page, WebSocketRoute } from '@playwright/test';
const password = 'Test密碼2026!';
async function register(page: Page, url: string, username: string, displayName = username) {
  await page.goto(url); await expect(page.locator('#login-button')).toBeEnabled();
  await page.locator('#register-toggle').click(); await page.locator('#username').fill(username);
  await page.locator('#display-name').fill(displayName); await page.locator('#password').fill(password);
  await page.locator('#login-button').click(); await expect(page.locator('#login-title')).toHaveText('登录，开始聊天');
}
async function login(page: Page, username: string, secret = password) {
  await expect(page.locator('#login-button')).toBeEnabled(); await page.locator('#username').fill(username);
  await page.locator('#password').fill(secret); await page.locator('#login-button').click();
  await expect(page.locator('#self-name')).toHaveText(username); await page.waitForFunction(() => !document.body.textContent?.includes('正在恢复消息'));
}
async function contact(page: Page, name: string) {
  await page.locator('#users-tab').click(); await page.locator('#contact-search').fill(name);
  await page.locator('#list .conversation').filter({ hasText: name }).first().click();
  await expect(page.locator('#message')).toBeEnabled();
}
async function send(page: Page, text: string) {
  await page.locator('#message').fill(text); await expect(page.locator('#send')).toBeEnabled(); await page.locator('#send').click();
  await expect(page.locator('.message-row').filter({ hasText: text }).locator('.message-status')).toHaveText('已发送');
}

test('TLS static deployment: account, offline contacts, private/group, unread, history, refresh, restart, backup/restore', async ({ browser, deployment, page }) => {
  const alice = page; const bobContext = await browser.newContext({ ignoreHTTPSErrors: true }); const bob = await bobContext.newPage();
  const response = await alice.goto(deployment.url); expect(response?.status()).toBe(200);
  expect((await response?.securityDetails())?.protocol).toMatch(/TLS/);
  expect((await alice.request.get(deployment.url + '/api/health')).status()).toBe(200);
  await register(bob, deployment.url, 'bob', '离线伙伴'); await register(alice, deployment.url, 'alice'); await login(alice, 'alice');
  const id = await alice.locator('#self-id').textContent();
  await expect.poll(async () => (await alice.context().cookies()).some(c => c.name === '__Host-xim_session')).toBe(true);
  const cookie = (await alice.context().cookies()).find(c => c.name === '__Host-xim_session');
  expect(cookie).toBeDefined();
  if (cookie) { expect(cookie.secure).toBe(true); expect(cookie.httpOnly).toBe(true); expect(cookie.sameSite).toBe('Strict'); }
  await contact(alice, '离线伙伴'); await expect(alice.locator('#chat-subtitle')).toContainText('离线');
  await send(alice, '离线私聊：你好🌏'); await alice.locator('#message').fill('刷新后保留的草稿');
  await alice.reload(); await expect(alice.locator('#self-id')).toHaveText(id!); await expect(alice.locator('#message')).toHaveValue('刷新后保留的草稿');
  await alice.locator('#new-group').click(); await alice.locator('#group-contact-search').fill('离线伙伴');
  await alice.locator('#group-options input[type=checkbox]').check(); await alice.locator('#group-form button[type=submit]').click();
  await expect(alice.locator('#chat-subtitle')).toContainText('群号');
  const groupId = (await alice.locator('#chat-subtitle').textContent())!.replace('群号 ', '');
  await send(alice, '离线群聊：中文与 Emoji 🐱');
  await login(bob, 'bob');
  await expect(bob.locator('#list .unread')).toHaveCount(2);
  await bob.locator('#list .conversation').filter({ hasText: '离线私聊' }).click();
  await expect(bob.locator('.bubble')).toContainText(['离线私聊：你好🌏']); await send(bob, '在线回复');
  await contact(alice, '离线伙伴'); await expect(alice.locator('.bubble')).toContainText(['离线私聊：你好🌏', '在线回复']);
  // Real browser -> real server: fill a history exceeding the latest page.
  for (let i = 0; i < 52; i++) await send(alice, `分页消息 ${String(i).padStart(2, '0')}`);
  await bob.locator('#conversations-tab').click();
  await bob.locator('#list .conversation').filter({ hasText: '群聊' }).click();
  await expect(bob.locator('.bubble')).toContainText(['离线群聊：中文与 Emoji 🐱']);
  await bob.locator('#members').click(); await expect(bob.locator('#member-list')).toContainText('alice');
  await bob.getByRole('button', { name: '关闭', exact: true }).click();
  await bob.locator('#leave-group').click(); await bob.locator('#leave-form button[type=submit]').click();
  await bob.locator('#join-group').click(); await bob.locator('#group-id').fill(groupId); await bob.locator('#join-form button[type=submit]').click();
  await expect(bob.locator('#chat-subtitle')).toContainText(groupId);
  await deployment.stopBackend(); await deployment.startBackend();
  await expect(alice.locator('#self-id')).toHaveText(id!, { timeout: 30_000 });
  await expect(alice.locator('#send')).toBeDisabled();
  await contact(alice, '离线伙伴'); await alice.reload(); await expect(alice.locator('#self-name')).toHaveText('alice');
  await expect(alice.getByRole('button', { name: '加载更早消息', exact: true })).toBeEnabled();
  await alice.getByRole('button', { name: '加载更早消息', exact: true }).click(); await expect(alice.locator('.bubble')).toContainText(['离线私聊：你好🌏']);
  const snapshot = deployment.backup(); await send(alice, '备份之后消息');
  await deployment.restore(snapshot); await alice.evaluate(() => sessionStorage.removeItem('xim.recovery')); await alice.reload(); await expect(alice.locator('#self-id')).toHaveText(id!, { timeout: 30_000 });
  await contact(alice, '离线伙伴'); await expect(alice.locator('.bubble').filter({ hasText: '备份之后消息' })).toHaveCount(0);
  await expect(alice.locator('.bubble').filter({ hasText: '在线回复' })).toHaveCount(1);
  await bobContext.close();
});

test('password, invalid credentials, revocation, logout and multi-window policy', async ({ page, deployment, context }) => {
  await register(page, deployment.url, 'account'); await login(page, 'account');
  await expect.poll(async () => (await context.cookies()).some(c => c.name === '__Host-xim_session')).toBe(true);
  const second = await context.newPage(); await second.goto(deployment.url);
  await expect(second.locator('#login-error')).toContainText('另一窗口');
  await login(second, 'account'); await expect(page.locator('#login-error')).toContainText('另一');
  await page.reload(); await expect(page.locator('#login-view')).toBeVisible();
  await second.locator('#change-password').click(); await second.locator('#current-password').fill(password);
  await second.locator('#new-password').fill('Changed密碼2026!'); await second.locator('#confirm-password').fill('Changed密碼2026!');
  await second.locator('#password-dialog button[type=submit]').click(); await expect(second.locator('#login-view')).toBeVisible();
  await second.locator('#password').fill(password); await second.locator('#login-button').click(); await expect(second.locator('#login-error')).toContainText('账号或密码错误');
  await login(second, 'account', 'Changed密碼2026!'); await second.locator('#revoke-sessions').click(); await expect(second.locator('#login-view')).toBeVisible();
  await second.reload(); await expect(second.locator('#login-view')).toBeVisible();
  await login(second, 'account', 'Changed密碼2026!'); await second.locator('#logout').click(); await second.reload();
  await expect(second.locator('#login-view')).toBeVisible(); await expect(second.locator('#self-name')).toHaveCount(0);
});

test('real server: dropped and late receipt, repeated retry, disconnect catch-up without duplicate delivery', async ({ browser, page, deployment }) => {
  let offline = false; let route: WebSocketRoute; let held: string | Buffer | undefined; const requests: any[] = [];
  await page.routeWebSocket('**/ws', ws => {
    if (offline) { void ws.close(); return; }
    route = ws; const server = ws.connectToServer();
    ws.onMessage(message => { const data = JSON.parse(message.toString()); if (data.type === 'message') requests.push(data); server.send(message); });
    server.onMessage(message => { const data = JSON.parse(message.toString()); if (data.type === 'sent' && !held) { held = message; return; } if (held && requests.length < 2 && ['history','conversations'].includes(data.type)) return; ws.send(message); });
  });
  const other = await browser.newContext({ ignoreHTTPSErrors: true }); const bob = await other.newPage();
  await register(bob, deployment.url, 'recipient'); await register(page, deployment.url, 'sender'); await login(page, 'sender'); await login(bob, 'recipient');
  await contact(page, 'recipient'); await page.locator('#message').fill('可靠消息 📨'); await page.locator('#send').click();
  await expect(page.getByRole('button', { name: '重试发送这条消息' })).toBeVisible({ timeout: 12_000 });
  await page.getByRole('button', { name: '重试发送这条消息' }).click();
  await expect(page.locator('.message-status')).toHaveText('已发送'); expect(requests).toHaveLength(2); expect(requests[0].requestId).toBe(requests[1].requestId);
  route!.send(held!); await expect(page.locator('.bubble').filter({ hasText: '可靠消息' })).toHaveCount(1);
  await contact(bob, 'sender'); await expect(bob.locator('.bubble').filter({ hasText: '可靠消息' })).toHaveCount(1);
  offline = true; await page.context().setOffline(true); await route!.close(); await expect(page.locator('#message')).toBeDisabled({ timeout: 20_000 });
  for (let i = 0; i < 65; i++) await send(bob, `断网期间消息 ${String(i).padStart(2, '0')}`); offline = false; await page.context().setOffline(false);
  await expect(page.locator('#message')).toBeEnabled({ timeout: 30_000 }); await expect(page.locator('.bubble').filter({ hasText: '断网期间消息' })).toHaveCount(65);
  await other.close();
});

test('contacts cross-page selection and search, offline group invitation and mobile layout', async ({ page, deployment }) => {
  await register(page, deployment.url, 'directory'); deployment.seedContacts(22); await login(page, 'directory');
  await page.locator('#users-tab').click(); await expect(page.locator('#list .conversation')).toHaveCount(20);
  await page.getByRole('button', { name: '加载更多联系人' }).click(); await expect(page.locator('#list .conversation')).toHaveCount(22);
  await page.locator('#new-group').click(); await page.locator('#group-options input').first().check();
  await page.locator('#group-dialog').getByRole('button', { name: '加载更多联系人' }).click();
  await page.locator('#group-options input').last().check(); await page.locator('#group-contact-search').fill('不存在');
  await expect(page.locator('#group-options')).toContainText('没有找到匹配'); await expect(page.locator('.selected-members')).toContainText('已选择 2/50');
  await page.locator('#group-form button[type=submit]').click(); await page.locator('#members').click(); await expect(page.locator('#member-list .member')).toHaveCount(3);
  await page.getByRole('button', { name: '关闭', exact: true }).click(); await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('#back').click(); await page.locator('#users-tab').click(); await page.locator('#contact-search').fill('跨页伙伴 021');
  await expect(page.locator('#list .conversation')).toHaveCount(1); await page.locator('#list .conversation').click();
  await send(page, '移动端离线首聊'); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('absolute session expiry and login failure limit are enforced by a real process', async ({ page, deployment }) => {
  await deployment.stopBackend(); deployment.options = ['-Dxim.session.ttlSeconds=3']; await deployment.startBackend();
  await register(page, deployment.url, 'expiring'); await login(page, 'expiring');
  await expect(page.locator('#login-error')).toContainText('过期', { timeout: 10_000 }); await page.reload(); await expect(page.locator('#login-view')).toBeVisible();
  for (let i = 0; i < 5; i++) {
    await page.locator('#username').fill('expiring'); await page.locator('#password').fill('wrong-password'); await page.locator('#login-button').click();
    await expect(page.locator('#login-button')).toBeEnabled(); await expect(page.locator('#login-error')).toContainText('账号或密码错误');
  }
  await page.locator('#password').fill(password); await page.locator('#login-button').click(); await expect(page.locator('#login-error')).toContainText('频繁');
});
