import { test, expect } from '@playwright/test'

test('simple test', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('text=Notice')).toBeVisible()
})
