import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', workers: 1, retries: 0, timeout: 120_000,
  expect: { timeout: 15_000 }, outputDir: 'test-results',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: { browserName: 'chromium', channel: process.env.XIM_BROWSER_CHANNEL || 'chrome', ignoreHTTPSErrors: true, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
});
