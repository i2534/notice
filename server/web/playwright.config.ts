import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [['html', { outputFolder: 'playwright-report' }], ['list']],

  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  testMatch: ['**/*.e2e.{ts,tsx}'],

  projects: [
    {
      name: 'e2e:chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'e2e:firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'e2e:webkit',
      use: { ...devices['Desktop Safari'] },
    },
    {
      name: 'e2e:mobile-chrome',
      use: { ...devices['Pixel 5'] },
    },
    {
      name: 'e2e:mobile-safari',
      use: { ...devices['iPhone 12'] },
    },
  ],

  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60000,
  },

  expect: {
    toHaveScreenshot: { maxDiffPixels: 100, threshold: 0.2 },
  },
})