import { test, expect } from '@playwright/test';
import { uniqueEmail, registerUserViaApi, setAuthInLocalStorage } from './helpers';

test.describe('US-202: Session Management', () => {
  test.describe('Session Persistence (SBQ-005, AQ-006)', () => {
    test('should store tokens in localStorage after login', async ({ page }) => {
      const email = uniqueEmail();
      await registerUserViaApi(page, email, 'Secret1234');

      await page.goto('/login');
      await page.getByLabel('Email address').fill(email);
      await page.getByLabel('Password', { exact: true }).fill('Secret1234');
      await page.getByLabel('Sign in').click();
      await expect(page).toHaveURL(/\/dashboard/);

      // Verify localStorage has tokens
      const accessToken = await page.evaluate(() => localStorage.getItem('taskforge-access-token'));
      const refreshToken = await page.evaluate(() => localStorage.getItem('taskforge-refresh-token'));
      const user = await page.evaluate(() => localStorage.getItem('taskforge-user'));

      expect(accessToken).toBeTruthy();
      expect(refreshToken).toBeTruthy();
      expect(user).toBeTruthy();

      // Verify user data is correctly stored
      const userData = JSON.parse(user!);
      expect(userData.email).toBe(email);
    });

    test('should restore session from localStorage on page reload', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234', 'Session User');
      await setAuthInLocalStorage(page, tokens);

      await page.goto('/dashboard');
      await expect(page.getByText('Session User')).toBeVisible();

      // Reload should maintain session
      await page.reload();
      await expect(page).toHaveURL(/\/dashboard/);
      await expect(page.getByText('Session User')).toBeVisible();
    });
  });

  test.describe('Session Expiry Messages', () => {
    test('should show session expired message on login page', async ({ page }) => {
      await page.goto('/login?message=session-expired');
      await expect(page.locator('[role="status"]')).toContainText(
        'Your session has expired due to inactivity.',
      );
    });

    test('should show logged out message on login page', async ({ page }) => {
      await page.goto('/login?message=logged-out');
      await expect(page.locator('[role="status"]')).toContainText(
        'You have been logged out.',
      );
    });

    test('should not show message for unknown message keys (security)', async ({ page }) => {
      await page.goto('/login?message=hacked');
      await expect(page.locator('[role="status"]')).not.toBeVisible();
    });
  });
});

test.describe('Idle Timeout Detection', () => {
  test('should have idle service running after login', async ({ page }) => {
    const email = uniqueEmail();
    const tokens = await registerUserViaApi(page, email, 'Secret1234');
    await setAuthInLocalStorage(page, tokens);

    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/dashboard/);

    // The idle service tracks activity events - verify it doesn't immediately expire
    await page.waitForTimeout(2000);
    await expect(page).toHaveURL(/\/dashboard/);
  });
});
