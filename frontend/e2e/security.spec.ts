import { test, expect } from '@playwright/test';
import { uniqueEmail, registerUserViaApi } from './helpers';

const API_BASE = 'http://localhost:8080/api/v1';

test.describe('Security Requirements', () => {
  test.describe('Password Storage (DP-003: bcrypt)', () => {
    test('should not expose password hash in any API response', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234');

      // Register response should not contain password
      expect(JSON.stringify(tokens)).not.toContain('$2a$');
      expect(JSON.stringify(tokens)).not.toContain('Secret1234');

      // /me endpoint should not contain password
      const meRes = await page.request.get(`${API_BASE}/auth/me`, {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      });
      const user = await meRes.json();
      expect(JSON.stringify(user)).not.toContain('$2a$');
      expect(JSON.stringify(user)).not.toContain('passwordHash');
    });
  });

  test.describe('Generic Error Messages (NFR: Security)', () => {
    test('should return same error for wrong password and non-existent user', async ({ page }) => {
      const email = uniqueEmail();
      await registerUserViaApi(page, email, 'Secret1234');

      // Wrong password for existing user
      const wrongPwRes = await page.request.post(`${API_BASE}/auth/login`, {
        data: { email, password: 'WrongPassword1' },
      });
      const wrongPwBody = await wrongPwRes.json();

      // Non-existent user
      const noUserRes = await page.request.post(`${API_BASE}/auth/login`, {
        data: { email: 'nobody@example.com', password: 'Secret1234' },
      });
      const noUserBody = await noUserRes.json();

      // Both should return same status and same error message
      expect(wrongPwRes.status()).toBe(401);
      expect(noUserRes.status()).toBe(401);
      expect(wrongPwBody.detail).toBe(noUserBody.detail);
      expect(wrongPwBody.detail).toBe('Invalid email or password');
    });

    test('should return generic message for duplicate email registration', async ({ page }) => {
      const email = uniqueEmail();
      await registerUserViaApi(page, email, 'Secret1234');

      // Try to register again
      const res = await page.request.post(`${API_BASE}/auth/register`, {
        data: { email, password: 'Secret1234', confirmPassword: 'Secret1234' },
      });
      expect(res.status()).toBe(409);
      const body = await res.json();
      // Should NOT reveal that the email exists — generic message
      expect(body.detail).toBe('Unable to register with this email');
    });
  });

  test.describe('Security Headers (SBQ-008, AQ-004)', () => {
    test('should set Cache-Control: no-store on authenticated responses', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234');

      const res = await page.request.get(`${API_BASE}/auth/me`, {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      });
      expect(res.status()).toBe(200);

      const cacheControl = res.headers()['cache-control'];
      expect(cacheControl).toContain('no-store');
    });

    test('should set Content-Security-Policy header', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234');

      const res = await page.request.get(`${API_BASE}/auth/me`, {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      });

      const csp = res.headers()['content-security-policy'];
      expect(csp).toBe("script-src 'self'");
    });

    test('should set X-Frame-Options: DENY', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234');

      const res = await page.request.get(`${API_BASE}/auth/me`, {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      });

      const frameOptions = res.headers()['x-frame-options'];
      expect(frameOptions).toBe('DENY');
    });
  });

  test.describe('Password Validation', () => {
    const testCases = [
      { password: 'short1A', reason: 'less than 8 characters' },
      { password: 'nouppercase1', reason: 'no uppercase letter' },
      { password: 'NOLOWERCASE1', reason: 'no lowercase letter' },
      { password: 'NoDigitsHere', reason: 'no digit' },
    ];

    for (const { password, reason } of testCases) {
      test(`should reject password with ${reason}`, async ({ page }) => {
        const res = await page.request.post(`${API_BASE}/auth/register`, {
          data: {
            email: uniqueEmail(),
            password,
            confirmPassword: password,
          },
        });
        expect(res.status()).toBe(400);
      });
    }

    test('should accept a strong password', async ({ page }) => {
      const res = await page.request.post(`${API_BASE}/auth/register`, {
        data: {
          email: uniqueEmail(),
          password: 'StrongPass1',
          confirmPassword: 'StrongPass1',
        },
      });
      expect(res.status()).toBe(201);
    });
  });

  test.describe('User ID Format (DP-004: UUID)', () => {
    test('should use UUID format for user IDs', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234');

      expect(tokens.user.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
      );
    });
  });

  test.describe('Login Audit (NFR: Audit)', () => {
    test('should not expose audit data in API responses', async ({ page }) => {
      const email = uniqueEmail();
      const tokens = await registerUserViaApi(page, email, 'Secret1234');

      // Attempt login (creates audit entries)
      await page.request.post(`${API_BASE}/auth/login`, {
        data: { email, password: 'WrongPass1' },
      });

      // /me response should not include audit information
      const meRes = await page.request.get(`${API_BASE}/auth/me`, {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      });
      const user = await meRes.json();
      expect(user).not.toHaveProperty('loginAudits');
      expect(user).not.toHaveProperty('failedAttemptCount');
      expect(user).not.toHaveProperty('lockedUntil');
    });
  });

  test.describe('Protected API Endpoints', () => {
    test('should return 401 for /api/v1/auth/me without token', async ({ page }) => {
      const res = await page.request.get(`${API_BASE}/auth/me`);
      expect(res.status()).toBe(401);
    });

    test('should return 401 for /api/v1/tasks without token', async ({ page }) => {
      const res = await page.request.get(`${API_BASE}/tasks`);
      expect(res.status()).toBe(401);
    });

    test('should return 401 for /api/v1/projects without token', async ({ page }) => {
      const res = await page.request.get(`${API_BASE}/projects`);
      expect(res.status()).toBe(401);
    });

    test('should allow public endpoints without token', async ({ page }) => {
      // Register endpoint accepts requests (may fail validation, but not 401)
      const res = await page.request.post(`${API_BASE}/auth/register`, {
        data: { email: 'x', password: 'x', confirmPassword: 'x' },
      });
      // Should be 400 (validation) not 401 (unauthorized)
      expect(res.status()).toBe(400);
    });
  });
});
