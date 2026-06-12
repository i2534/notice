import { test, expect } from '@playwright/test'

test.describe('Settings Panel', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.fill('input[type="password"]', 'notice123')
    await page.click('button:has-text("验证并进入")')
    await expect(page.locator('.page-title').first()).toContainText('消息', { timeout: 20000 })
    await page.click('button:has-text("设置")')
    await expect(page.locator('h3').filter({ hasText: '设置' })).toBeVisible({ timeout: 10000 })
  })

  test('shows connection settings', async ({ page }) => {
    await expect(page.locator('text=Broker 地址')).toBeVisible({ timeout: 10000 })
    await expect(page.locator('#sBroker')).toBeVisible({ timeout: 10000 })
    await expect(page.locator('text=订阅主题')).toBeVisible()
    await expect(page.locator('#sTopic')).toBeVisible({ timeout: 10000 })
    await expect(page.locator('text=认证 Token')).toBeVisible()
  })

  test('can change theme', async ({ page }) => {
    const themeSelect = page.locator('select').first()
    await expect(themeSelect).toBeVisible()
    await themeSelect.selectOption('light')
    await expect(themeSelect).toHaveValue('light')
    await themeSelect.selectOption('dark')
  })

  test('can change max messages', async ({ page }) => {
    const maxMessagesInput = page.locator('input[type="number"]')
    await expect(maxMessagesInput).toBeVisible({ timeout: 5000 })
    await maxMessagesInput.fill('500')
    await expect(maxMessagesInput).toHaveValue('500')
  })

  test('can save and reconnect', async ({ page }) => {
    await page.click('button:has-text("保存并重连")')
    await expect(page.locator('h3').filter({ hasText: '设置' })).not.toBeVisible({ timeout: 5000 })
  })

  test('can close settings panel', async ({ page }) => {
    await page.click('button:has-text("×")')
    await expect(page.locator('h3').filter({ hasText: '设置' })).not.toBeVisible()
  })
})