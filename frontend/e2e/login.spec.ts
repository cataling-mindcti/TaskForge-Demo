import { test, expect } from '@playwright/test';
import { uniqueEmail, registerUserViaApi } from './helpers';

test.describe('US-201: Email and Password Login', () => {
  let email: string;
  const password = 'Secret1234';

  test.beforeEach(async ({ page }) => {
    email = uniqueEmail();
    await registerUserViaApi(page, email, password, 'Test User');
    await page.goto('/login');
  });

  test('should display the login form', async ({ page }) => {
    await expect(page.getByText('Sign in to TaskForge')).toBeVisible();
    await expect(page.getByLabel('Email address')).toBeVisible();
    await expect(page.getByLabel('Password', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Sign in')).toBeVisible();
  });

  test('should login with valid credentials and redirect to dashboard', async ({ page }) => {
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByLabel('Sign in').click();

    await expect(page).toHaveURL(/\/dashboard/);
    await expect(page.getByText('Test User')).toBeVisible();
  });

  test('should show generic error on wrong password — no field hints (US-201 AC3)', async ({ page }) => {
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('WrongPassword1');
    await page.getByLabel('Sign in').click();

    const errorBanner = page.locator('[role="alert"]');
    await expect(errorBanner).toBeVisible();
    await expect(errorBanner).toContainText('Invalid email or password');
  });

  test('should show generic error on non-existent email', async ({ page }) => {
    await page.getByLabel('Email address').fill('nobody@example.com');
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByLabel('Sign in').click();

    await expect(page.locator('[role="alert"]')).toContainText('Invalid email or password');
  });

  test('should show inline validation when fields are empty', async ({ page }) => {
    await page.getByLabel('Sign in').click();

    await expect(page.getByText('Email is required')).toBeVisible();
    await expect(page.getByText('Password is required')).toBeVisible();
  });

  test('should show inline validation for invalid email format', async ({ page }) => {
    await page.getByLabel('Email address').fill('not-email');
    await page.getByLabel('Email address').blur();
    await expect(page.getByText('Enter a valid email address')).toBeVisible();
  });

  test('should toggle password visibility', async ({ page }) => {
    const passwordInput = page.getByLabel('Password', { exact: true });
    await passwordInput.fill('Secret1234');

    // Initially hidden
    await expect(passwordInput).toHaveAttribute('type', 'password');

    // Click show password
    await page.getByLabel('Show password').click();
    await expect(passwordInput).toHaveAttribute('type', 'text');

    // Click hide password
    await page.getByLabel('Hide password').click();
    await expect(passwordInput).toHaveAttribute('type', 'password');
  });

  test('should navigate to registration page via "Create Account" link', async ({ page }) => {
    await page.getByLabel('Create a new account').click();
    await expect(page).toHaveURL(/\/register/);
  });

  test('should persist login across page reload (SBQ-005: always persist)', async ({ page }) => {
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByLabel('Sign in').click();
    await expect(page).toHaveURL(/\/dashboard/);

    // Reload the page — session should persist (localStorage)
    await page.reload();
    await expect(page).toHaveURL(/\/dashboard/);
    await expect(page.getByText('Test User')).toBeVisible();
  });
});

test.describe('US-201 AC4: Account Lockout', () => {
  let email: string;
  const password = 'Secret1234';

  test.beforeEach(async ({ page }) => {
    email = uniqueEmail();
    await registerUserViaApi(page, email, password);
    await page.goto('/login');
  });

  test('should show lockout message after 5 failed attempts (SBQ-004, SBQ-006)', async ({ page }) => {
    // 5 failed attempts
    for (let i = 0; i < 5; i++) {
      await page.getByLabel('Email address').fill(email);
      await page.getByLabel('Password', { exact: true }).fill('WrongPassword1');
      await page.getByLabel('Sign in').click();

      if (i < 4) {
        await expect(page.locator('[role="alert"]')).toContainText('Invalid email or password');
        // Clear for next attempt
        await page.getByLabel('Password', { exact: true }).clear();
      }
    }

    // 6th attempt should show lockout message
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('WrongPassword1');
    await page.getByLabel('Sign in').click();

    await expect(page.locator('[role="alert"]')).toContainText(
      'Too many failed attempts',
    );
  });
});
