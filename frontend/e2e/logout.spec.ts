import { test, expect } from '@playwright/test';
import { uniqueEmail, registerUserViaApi, setAuthInLocalStorage } from './helpers';

test.describe('US-203: Logout', () => {
  let email: string;
  let tokens: Awaited<ReturnType<typeof registerUserViaApi>>;

  test.beforeEach(async ({ page }) => {
    email = uniqueEmail();
    tokens = await registerUserViaApi(page, email, 'Secret1234', 'Logout Tester');
    await setAuthInLocalStorage(page, tokens);
    await page.goto('/dashboard');
    await expect(page.getByText('Logout Tester')).toBeVisible();
  });

  test('should have logout button accessible from the main navigation (US-203 AC1)', async ({ page }) => {
    await expect(page.getByLabel('Logout')).toBeVisible();
  });

  test('should redirect to login page after logout (US-203 AC2)', async ({ page }) => {
    await page.getByLabel('Logout').click();
    await expect(page).toHaveURL(/\/login/);
  });

  test('should clear auth state from localStorage on logout', async ({ page }) => {
    await page.getByLabel('Logout').click();
    await expect(page).toHaveURL(/\/login/);

    const accessToken = await page.evaluate(() => localStorage.getItem('taskforge-access-token'));
    const refreshToken = await page.evaluate(() => localStorage.getItem('taskforge-refresh-token'));
    const user = await page.evaluate(() => localStorage.getItem('taskforge-user'));

    expect(accessToken).toBeNull();
    expect(refreshToken).toBeNull();
    expect(user).toBeNull();
  });

  test('should not show authenticated content after logout and back button (US-203 AC3)', async ({ page }) => {
    // Navigate to dashboard (authenticated)
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();

    // Logout
    await page.getByLabel('Logout').click();
    await expect(page).toHaveURL(/\/login/);

    // Press browser back button
    await page.goBack();

    // Should be redirected to login (auth guard), NOT show dashboard
    await expect(page).toHaveURL(/\/login/);
  });
});
