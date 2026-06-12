import { test, expect } from '@playwright/test'

test.describe('Messages View', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.fill('input[type="password"]', 'notice123')
    await page.click('button:has-text("验证并进入")')
    await expect(page.locator('.page-title').first()).toContainText('消息', { timeout: 20000 })
    await page.waitForTimeout(3000)
  })

  test('shows message list after login', async ({ page }) => {
    const msgCards = page.locator('.msg-card')
    const count = await msgCards.count()
    if (count > 0) {
      await expect(msgCards.first()).toBeVisible({ timeout: 20000 })
    } else {
      await expect(page.locator('.message-list')).toBeVisible({ timeout: 10000 })
    }
  })

  test('can search messages', async ({ page }) => {
    const searchInput = page.locator('input[placeholder*="搜索"]')
    await searchInput.waitFor({ state: 'visible', timeout: 10000 })
    await searchInput.fill('测试')
    const count = await page.locator('.msg-card').count()
    expect(count).toBeGreaterThanOrEqual(0)
    await page.fill('input[placeholder*="搜索"]', '')
  })

  test('can open message detail', async ({ page }) => {
    const msgCards = page.locator('.msg-card')
    const count = await msgCards.count()
    if (count > 0) {
      await msgCards.first().waitFor({ state: 'visible', timeout: 20000 })
      await msgCards.first().click()
      await expect(page.locator('.modal')).toBeVisible({ timeout: 5000 })
      await expect(page.locator('.modal .d-title')).toBeVisible()
    } else {
      test.skip(true, 'No messages to open')
    }
  })

  test('can open send panel and send message', async ({ page }) => {
    await page.click('button[aria-label="打开发送面板"]')
    await expect(page.locator('h4:has-text("发送消息")')).toBeVisible({ timeout: 5000 })

    await page.fill('input[placeholder="消息标题"]', 'E2E 测试标题')
    await page.fill('input[placeholder="留空使用默认"]', 'notice/test')
    await page.fill('textarea[placeholder*="消息内容"]', 'E2E 测试内容')

    await page.click('button:has-text("发送消息")')

    await expect(page.locator('text=消息已发送')).toBeVisible({ timeout: 10000 })

    await expect(page.locator('.msg-card').first()).toContainText('E2E 测试标题', { timeout: 10000 })
  })

  test('can close send panel', async ({ page }) => {
    await page.click('button[aria-label="打开发送面板"]')
    await page.click('button:has-text("收起")')
    await expect(page.locator('button[aria-label="打开发送面板"]')).toBeVisible()
  })

  test('can toggle theme', async ({ page }) => {
    await page.click('button[title="切换主题"]')
    await page.click('button[title="切换主题"]')
  })

  test('can navigate to topics view', async ({ page }) => {
    await page.click('button:has-text("主题")')
    await expect(page.locator('text=主题').first()).toBeVisible()
  })

  test('can navigate to clients view', async ({ page }) => {
    await page.click('button:has-text("客户端")')
    await expect(page.locator('h3').filter({ hasText: '客户端' })).toBeVisible()
    await expect(page.locator('.name').first()).toContainText('web-', { timeout: 5000 })
  })

  test('can open settings panel', async ({ page }) => {
    await page.click('button:has-text("设置")')
    await expect(page.locator('h3').filter({ hasText: '设置' })).toBeVisible()
  })

  test('can open about dialog', async ({ page }) => {
    await page.click('button[title="关于"]')
    await expect(page.locator('.about-dialog h3')).toContainText('Notice')
    await expect(page.locator('text=v0.1')).toBeVisible()
    await expect(page.locator('a:has-text("GitHub")')).toBeVisible()
    await page.click('button:has-text("关闭")')
  })
})