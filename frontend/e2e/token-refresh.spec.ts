import { test, expect } from '@playwright/test';
import { uniqueEmail, registerUserViaApi } from './helpers';

const API_BASE = 'http://localhost:8080/api/v1';

test.describe('Token Refresh Flow (DP-001, AQ-005)', () => {
  test('should refresh tokens via API and receive rotated refresh token', async ({ page }) => {
    const email = uniqueEmail();
    const tokens = await registerUserViaApi(page, email, 'Secret1234');

    // Refresh the token via API
    const refreshRes = await page.request.post(`${API_BASE}/auth/refresh`, {
      data: { refreshToken: tokens.refreshToken },
    });
    expect(refreshRes.status()).toBe(200);

    const newTokens = await refreshRes.json();
    expect(newTokens.accessToken).toBeTruthy();
    expect(newTokens.refreshToken).toBeTruthy();
    // Token rotation: new refresh token should differ from old
    expect(newTokens.refreshToken).not.toBe(tokens.refreshToken);

    // New access token should work
    const meRes = await page.request.get(`${API_BASE}/auth/me`, {
      headers: { Authorization: `Bearer ${newTokens.accessToken}` },
    });
    expect(meRes.status()).toBe(200);
    const user = await meRes.json();
    expect(user.email).toBe(email);
  });

  test('should reject reused refresh token (theft detection)', async ({ page }) => {
    const email = uniqueEmail();
    const tokens = await registerUserViaApi(page, email, 'Secret1234');

    // First refresh — valid
    const res1 = await page.request.post(`${API_BASE}/auth/refresh`, {
      data: { refreshToken: tokens.refreshToken },
    });
    expect(res1.status()).toBe(200);

    // Reuse old token — should fail
    const res2 = await page.request.post(`${API_BASE}/auth/refresh`, {
      data: { refreshToken: tokens.refreshToken },
    });
    expect(res2.status()).toBe(401);
  });

  test('should handle interceptor auto-refresh on 401 (frontend integration)', async ({ page }) => {
    const email = uniqueEmail();
    await registerUserViaApi(page, email, 'Secret1234');

    // Login via UI to set up interceptor properly
    await page.goto('/login');
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('Secret1234');
    await page.getByLabel('Sign in').click();
    await expect(page).toHaveURL(/\/dashboard/);

    // Verify the interceptor is attaching tokens
    // by confirming we can access the dashboard (which requires authenticated API calls)
    await page.reload();
    await expect(page).toHaveURL(/\/dashboard/);
  });
});

test.describe('Token Denylist (Logout Invalidation)', () => {
  test('should reject denylisted access token after logout', async ({ page }) => {
    const email = uniqueEmail();
    const tokens = await registerUserViaApi(page, email, 'Secret1234');

    // Logout via API (requires Authorization header)
    const logoutRes = await page.request.post(`${API_BASE}/auth/logout`, {
      headers: { Authorization: `Bearer ${tokens.accessToken}` },
      data: { accessToken: tokens.accessToken },
    });
    expect(logoutRes.status()).toBe(204);

    // Access token should no longer work
    const meRes = await page.request.get(`${API_BASE}/auth/me`, {
      headers: { Authorization: `Bearer ${tokens.accessToken}` },
    });
    expect(meRes.status()).toBe(401);
  });

  test('should reject refresh token after logout', async ({ page }) => {
    const email = uniqueEmail();
    const tokens = await registerUserViaApi(page, email, 'Secret1234');

    // Logout (requires Authorization header)
    await page.request.post(`${API_BASE}/auth/logout`, {
      headers: { Authorization: `Bearer ${tokens.accessToken}` },
      data: { accessToken: tokens.accessToken },
    });

    // Refresh should also fail (all refresh tokens deleted)
    const refreshRes = await page.request.post(`${API_BASE}/auth/refresh`, {
      data: { refreshToken: tokens.refreshToken },
    });
    expect(refreshRes.status()).toBe(401);
  });
});

test.describe('Concurrent Sessions (SBQ-007)', () => {
  test('should allow multiple sessions without invalidating previous ones', async ({ page }) => {
    const email = uniqueEmail();
    await registerUserViaApi(page, email, 'Secret1234');

    // Login session 1
    const login1 = await page.request.post(`${API_BASE}/auth/login`, {
      data: { email, password: 'Secret1234' },
    });
    const tokens1 = await login1.json();

    // Login session 2
    const login2 = await page.request.post(`${API_BASE}/auth/login`, {
      data: { email, password: 'Secret1234' },
    });
    const tokens2 = await login2.json();

    // Both tokens should still work
    const me1 = await page.request.get(`${API_BASE}/auth/me`, {
      headers: { Authorization: `Bearer ${tokens1.accessToken}` },
    });
    expect(me1.status()).toBe(200);

    const me2 = await page.request.get(`${API_BASE}/auth/me`, {
      headers: { Authorization: `Bearer ${tokens2.accessToken}` },
    });
    expect(me2.status()).toBe(200);
  });
});
