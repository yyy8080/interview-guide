import { existsSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { defineConfig, devices } from '@playwright/test';

const playwrightCacheDir = join(homedir(), '.cache', 'ms-playwright');
const cacheEntries = existsSync(playwrightCacheDir) ? readdirSync(playwrightCacheDir) : [];
const hasPlaywrightChromium = cacheEntries.some((entry) => entry.startsWith('chromium'));
const hasPlaywrightFfmpeg = cacheEntries.some((entry) => entry.startsWith('ffmpeg-'));
const fallbackChromium = !hasPlaywrightChromium && existsSync('/usr/bin/chromium')
  ? '/usr/bin/chromium'
  : undefined;
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || fallbackChromium;

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['html', { open: 'never' }], ['list']] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: hasPlaywrightFfmpeg ? 'retain-on-failure' : 'off',
    permissions: ['microphone'],
    launchOptions: {
      executablePath,
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--use-fake-ui-for-media-stream',
        '--use-fake-device-for-media-stream',
      ],
    },
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'pnpm run dev --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
