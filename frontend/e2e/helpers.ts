import { type Page, expect } from '@playwright/test';

const API_BASE = 'http://localhost:8080/api/v1';

/** Unique email for test isolation */
export function uniqueEmail(): string {
  return `test-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** Register a user via API and return tokens + user */
export async function registerUserViaApi(
  page: Page,
  email: string,
  password = 'Secret1234',
  fullName?: string,
): Promise<{ accessToken: string; refreshToken: string; user: { id: string; email: string; fullName: string | null } }> {
  const res = await page.request.post(`${API_BASE}/auth/register`, {
    data: {
      email,
      password,
      confirmPassword: password,
      ...(fullName ? { fullName } : {}),
    },
  });
  expect(res.status()).toBe(201);
  return res.json();
}

/** Log in a user via API and return tokens */
export async function loginUserViaApi(
  page: Page,
  email: string,
  password = 'Secret1234',
): Promise<{ accessToken: string; refreshToken: string }> {
  const res = await page.request.post(`${API_BASE}/auth/login`, {
    data: { email, password },
  });
  expect(res.status()).toBe(200);
  return res.json();
}

/** Set auth tokens in localStorage (simulates logged-in state) */
export async function setAuthInLocalStorage(
  page: Page,
  tokens: { accessToken: string; refreshToken: string; user: { id: string; email: string; fullName: string | null } },
): Promise<void> {
  await page.addInitScript((t) => {
    localStorage.setItem('taskforge-access-token', t.accessToken);
    localStorage.setItem('taskforge-refresh-token', t.refreshToken);
    localStorage.setItem('taskforge-user', JSON.stringify(t.user));
  }, tokens);
}

/** Clear auth state from localStorage */
export async function clearAuth(page: Page): Promise<void> {
  await page.evaluate(() => {
    localStorage.removeItem('taskforge-access-token');
    localStorage.removeItem('taskforge-refresh-token');
    localStorage.removeItem('taskforge-user');
    localStorage.removeItem('taskforge-return-url');
  });
}

/** Clean up test user from database via API (best-effort) */
export async function cleanupUser(page: Page, accessToken: string): Promise<void> {
  await page.request.post(`${API_BASE}/auth/logout`, {
    data: { accessToken },
  });
}
