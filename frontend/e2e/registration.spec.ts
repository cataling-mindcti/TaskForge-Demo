import { test, expect } from '@playwright/test';
import { uniqueEmail } from './helpers';

test.describe('US-101: Self-service Registration', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/register');
  });

  test('should display the registration form with all required fields', async ({ page }) => {
    await expect(page.getByText('Create your account')).toBeVisible();
    await expect(page.getByLabel('Full name (optional)')).toBeVisible();
    await expect(page.getByLabel('Email address')).toBeVisible();
    await expect(page.getByLabel('Password', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Confirm password')).toBeVisible();
    await expect(page.getByLabel('Create account')).toBeVisible();
  });

  test('should register successfully and redirect to dashboard with welcome snackbar', async ({ page }) => {
    const email = uniqueEmail();

    await page.getByLabel('Full name (optional)').fill('Test User');
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('Secret1234');
    await page.getByLabel('Confirm password').fill('Secret1234');
    await page.getByLabel('Create account').click();

    // Should redirect to dashboard
    await expect(page).toHaveURL(/\/dashboard/);

    // Should show welcome snackbar (SBQ-001: 5-second auto-dismiss)
    await expect(page.getByText('Welcome to TaskForge! Your account is ready.')).toBeVisible();

    // Should show the user display name in toolbar
    await expect(page.getByText('Test User')).toBeVisible();
  });

  test('should register without full name (optional field - SBQ-002)', async ({ page }) => {
    const email = uniqueEmail();

    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('Secret1234');
    await page.getByLabel('Confirm password').fill('Secret1234');
    await page.getByLabel('Create account').click();

    // Should redirect to dashboard
    await expect(page).toHaveURL(/\/dashboard/);

    // Without full name, toolbar should show email
    await expect(page.getByText(email)).toBeVisible();
  });

  test('should auto-dismiss welcome snackbar after 5 seconds (SBQ-001)', async ({ page }) => {
    const email = uniqueEmail();

    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('Secret1234');
    await page.getByLabel('Confirm password').fill('Secret1234');
    await page.getByLabel('Create account').click();

    await expect(page.getByText('Welcome to TaskForge! Your account is ready.')).toBeVisible();

    // Wait for auto-dismiss (5 seconds + buffer)
    await expect(page.getByText('Welcome to TaskForge! Your account is ready.')).toBeHidden({
      timeout: 8000,
    });
  });

  test('should show full name hint as "Optional"', async ({ page }) => {
    await expect(page.getByText('Optional')).toBeVisible();
  });

  test('should enforce full name max length of 100 characters (AQ-001)', async ({ page }) => {
    const nameInput = page.getByLabel('Full name (optional)');
    // maxlength="100" on the input prevents typing more than 100 chars
    await nameInput.fill('A'.repeat(110));
    const value = await nameInput.inputValue();
    expect(value.length).toBeLessThanOrEqual(100);
  });
});

test.describe('US-102: Registration Validation and Error Handling', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/register');
  });

  test('should show inline error when email is empty and form is submitted', async ({ page }) => {
    await page.getByLabel('Create account').click();
    await expect(page.getByText('Email is required')).toBeVisible();
  });

  test('should show inline error for invalid email format', async ({ page }) => {
    await page.getByLabel('Email address').fill('not-an-email');
    await page.getByLabel('Email address').blur();
    await expect(page.getByText('Enter a valid email address')).toBeVisible();
  });

  test('should show password strength requirements when password is touched', async ({ page }) => {
    const passwordField = page.getByLabel('Password', { exact: true });
    await passwordField.fill('a');
    await passwordField.blur();

    await expect(page.getByText('At least 8 characters')).toBeVisible();
    await expect(page.getByText('One uppercase letter')).toBeVisible();
    await expect(page.getByText('One lowercase letter')).toBeVisible();
    await expect(page.getByText('One digit')).toBeVisible();
  });

  test('should update password requirement indicators as user types', async ({ page }) => {
    const passwordField = page.getByLabel('Password', { exact: true });

    // Type a lowercase letter only
    await passwordField.fill('a');
    await passwordField.blur();

    // "One lowercase letter" should be met (green check)
    const lowercaseReq = page.locator('.requirement.met', { hasText: 'One lowercase letter' });
    await expect(lowercaseReq).toBeVisible();

    // "One uppercase letter" should NOT be met
    const uppercaseReq = page.locator('.requirement:not(.met)', { hasText: 'One uppercase letter' });
    await expect(uppercaseReq).toBeVisible();

    // Type a strong password
    await passwordField.fill('Secret1234');
    // All requirements should be met
    const metRequirements = page.locator('.requirement.met');
    await expect(metRequirements).toHaveCount(4);
  });

  test('should show error when passwords do not match', async ({ page }) => {
    await page.getByLabel('Email address').fill(uniqueEmail());
    await page.getByLabel('Password', { exact: true }).fill('Secret1234');
    await page.getByLabel('Confirm password').fill('Different1234');
    await page.getByLabel('Create account').click();

    await expect(page.getByText('Passwords do not match')).toBeVisible();
  });

  test('should show generic error when email is already registered', async ({ page }) => {
    const email = uniqueEmail();

    // Register via API so we stay on /register page
    await page.request.post('http://localhost:8080/api/v1/auth/register', {
      data: { email, password: 'Secret1234', confirmPassword: 'Secret1234' },
    });

    // Try to register with same email via UI
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('Secret1234');
    await page.getByLabel('Confirm password').fill('Secret1234');
    await page.getByLabel('Create account').click();

    // Should show generic error (not revealing email exists)
    await expect(page.getByText('Unable to register with this email')).toBeVisible();
  });

  test('should preserve form data except password on validation failure', async ({ page }) => {
    const email = uniqueEmail();

    // Register via API so we stay on /register page
    await page.request.post('http://localhost:8080/api/v1/auth/register', {
      data: { email, password: 'Secret1234', confirmPassword: 'Secret1234' },
    });

    // Fill form and try same email via UI
    await page.getByLabel('Full name (optional)').fill('Another User');
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('Secret1234');
    await page.getByLabel('Confirm password').fill('Secret1234');
    await page.getByLabel('Create account').click();

    // Error shown, but form data preserved (SPA behavior)
    await expect(page.getByText('Unable to register with this email')).toBeVisible();
    await expect(page.getByLabel('Full name (optional)')).toHaveValue('Another User');
    await expect(page.getByLabel('Email address')).toHaveValue(email);
  });
});
