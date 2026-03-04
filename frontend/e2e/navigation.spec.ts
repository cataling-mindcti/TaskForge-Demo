import { test, expect } from '@playwright/test';
import { uniqueEmail, registerUserViaApi, setAuthInLocalStorage } from './helpers';

test.describe('Navigation and UX Flows', () => {
  test.describe('Registration Flow (BRD Section 5)', () => {
    test('should complete the full registration flow: landing → register → dashboard', async ({ page }) => {
      // Step 1: User lands on TaskForge → sees login page (not dashboard)
      await page.goto('/');
      await expect(page).toHaveURL(/\/login/);

      // Step 2: Clicks "Create Account" → registration form
      await page.getByLabel('Create a new account').click();
      await expect(page).toHaveURL(/\/register/);
      await expect(page.getByText('Create your account')).toBeVisible();

      // Step 3: Fills form and submits
      const email = uniqueEmail();
      await page.getByLabel('Full name (optional)').fill('New User');
      await page.getByLabel('Email address').fill(email);
      await page.getByLabel('Password', { exact: true }).fill('Secret1234');
      await page.getByLabel('Confirm password').fill('Secret1234');
      await page.getByLabel('Create account').click();

      // Step 4: Auto-logged-in → redirected to dashboard
      await expect(page).toHaveURL(/\/dashboard/);

      // In-app success message
      await expect(page.getByText('Welcome to TaskForge! Your account is ready.')).toBeVisible();

      // User display name visible
      await expect(page.getByText('New User')).toBeVisible();
    });
  });

  test.describe('Login Flow (BRD Section 5)', () => {
    test('should complete the full login flow: login → dashboard', async ({ page }) => {
      const email = uniqueEmail();
      await registerUserViaApi(page, email, 'Secret1234', 'Login User');

      // Step 1: Navigate to TaskForge → login page
      await page.goto('/login');
      await expect(page.getByText('Sign in to TaskForge')).toBeVisible();

      // Step 2: Submit valid credentials → dashboard
      await page.getByLabel('Email address').fill(email);
      await page.getByLabel('Password', { exact: true }).fill('Secret1234');
      await page.getByLabel('Sign in').click();

      await expect(page).toHaveURL(/\/dashboard/);
      await expect(page.getByText('Login User')).toBeVisible();
    });

    test('should allow retry after failed login attempt', async ({ page }) => {
      const email = uniqueEmail();
      await registerUserViaApi(page, email, 'Secret1234');

      await page.goto('/login');

      // Failed attempt
      await page.getByLabel('Email address').fill(email);
      await page.getByLabel('Password', { exact: true }).fill('WrongPass1');
      await page.getByLabel('Sign in').click();
      await expect(page.locator('[role="alert"]')).toBeVisible();

      // Retry with correct password
      await page.getByLabel('Password', { exact: true }).clear();
      await page.getByLabel('Password', { exact: true }).fill('Secret1234');
      await page.getByLabel('Sign in').click();

      await expect(page).toHaveURL(/\/dashboard/);
    });
  });

  test.describe('Logout Flow (BRD Section 5)', () => {
    test('should complete the full logout flow: logout → login page', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234', 'Logout User');
      await setAuthInLocalStorage(page, tokens);

      await page.goto('/dashboard');
      await expect(page.getByText('Logout User')).toBeVisible();

      // Step 1: Click "Logout" in navigation bar
      await page.getByLabel('Logout').click();

      // Step 2: Session terminated → redirected to login
      await expect(page).toHaveURL(/\/login/);

      // Step 3: Back button should not restore authenticated content
      await page.goBack();
      await expect(page).toHaveURL(/\/login/);
    });
  });

  test.describe('Sidenav Toggle', () => {
    test('should toggle sidenav with menu button', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234');
      await setAuthInLocalStorage(page, tokens);

      await page.goto('/dashboard');

      // Sidenav should be open by default
      await expect(page.locator('mat-sidenav')).toBeVisible();

      // Toggle closed
      await page.getByLabel('Toggle navigation').click();
      await expect(page.locator('mat-sidenav')).not.toBeVisible();

      // Toggle open
      await page.getByLabel('Toggle navigation').click();
      await expect(page.locator('mat-sidenav')).toBeVisible();
    });
  });

  test.describe('Cross-page Navigation Links', () => {
    test('should navigate between login and register pages', async ({ page }) => {
      // Login → Register
      await page.goto('/login');
      await page.getByLabel('Create a new account').click();
      await expect(page).toHaveURL(/\/register/);

      // Register → Login
      await page.getByLabel('Go to sign in page').click();
      await expect(page).toHaveURL(/\/login/);
    });
  });

  test.describe('Catch-all Route', () => {
    test('should redirect unknown routes to /login when not authenticated', async ({ page }) => {
      await page.goto('/nonexistent-page');
      await expect(page).toHaveURL(/\/login/);
    });
  });
});
