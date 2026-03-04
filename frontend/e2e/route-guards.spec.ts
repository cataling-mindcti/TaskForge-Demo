import { test, expect } from '@playwright/test';
import { uniqueEmail, registerUserViaApi, setAuthInLocalStorage } from './helpers';

test.describe('Route Guards', () => {
  test.describe('Auth Guard — protecting authenticated routes', () => {
    test('should redirect unauthenticated user from /dashboard to /login', async ({ page }) => {
      await page.goto('/dashboard');
      await expect(page).toHaveURL(/\/login/);
    });

    test('should redirect unauthenticated user from root / to /login', async ({ page }) => {
      await page.goto('/');
      await expect(page).toHaveURL(/\/login/);
    });

    test('should store return URL when redirecting to login (MC-006)', async ({ page }) => {
      // Try to access a specific protected route
      await page.goto('/dashboard');
      await expect(page).toHaveURL(/\/login/);

      // The return URL should be stored in localStorage
      const returnUrl = await page.evaluate(() => localStorage.getItem('taskforge-return-url'));
      expect(returnUrl).toContain('/dashboard');
    });

    test('should redirect to return URL after successful login', async ({ page }) => {
      const email = uniqueEmail();
      await registerUserViaApi(page, email, 'Secret1234');

      // Try to access dashboard while not logged in
      await page.goto('/dashboard');
      await expect(page).toHaveURL(/\/login/);

      // Login
      await page.getByLabel('Email address').fill(email);
      await page.getByLabel('Password', { exact: true }).fill('Secret1234');
      await page.getByLabel('Sign in').click();

      // Should redirect to the originally requested URL
      await expect(page).toHaveURL(/\/dashboard/);
    });
  });

  test.describe('Public Guard — protecting public routes', () => {
    test('should redirect authenticated user from /login to /dashboard', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234');
      await setAuthInLocalStorage(page, tokens);

      await page.goto('/login');
      await expect(page).toHaveURL(/\/dashboard/);
    });

    test('should redirect authenticated user from /register to /dashboard', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234');
      await setAuthInLocalStorage(page, tokens);

      await page.goto('/register');
      await expect(page).toHaveURL(/\/dashboard/);
    });

    test('should allow unauthenticated user to access /login', async ({ page }) => {
      await page.goto('/login');
      await expect(page).toHaveURL(/\/login/);
      await expect(page.getByText('Sign in to TaskForge')).toBeVisible();
    });

    test('should allow unauthenticated user to access /register', async ({ page }) => {
      await page.goto('/register');
      await expect(page).toHaveURL(/\/register/);
      await expect(page.getByText('Create your account')).toBeVisible();
    });
  });
});

test.describe('Layout Switching (CR-005)', () => {
  test('should show minimal auth layout on login page (no sidenav)', async ({ page }) => {
    await page.goto('/login');

    // Auth layout: toolbar with "TaskForge" only
    await expect(page.locator('mat-toolbar').getByText('TaskForge')).toBeVisible();

    // No sidenav, no logout button
    await expect(page.locator('mat-sidenav')).not.toBeVisible();
    await expect(page.getByLabel('Logout')).not.toBeVisible();
  });

  test('should show full layout on dashboard (toolbar + sidenav + logout)', async ({ page }) => {
    const email = uniqueEmail();
    const tokens = await registerUserViaApi(page, email, 'Secret1234', 'Layout User');
    await setAuthInLocalStorage(page, tokens);

    await page.goto('/dashboard');

    // Main layout: toolbar with user name + logout
    await expect(page.getByText('Layout User')).toBeVisible();
    await expect(page.getByLabel('Logout')).toBeVisible();

    // Sidenav with navigation
    await expect(page.locator('mat-sidenav')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  });
});
