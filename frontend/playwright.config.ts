import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './test-results',
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:5173',
    channel: 'chrome',
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure'
  },
  projects: [
    {
      name: '桌面端',
      use: { viewport: { width: 1440, height: 1000 } }
    },
    {
      name: '移动端',
      use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true }
    }
  ]
})
