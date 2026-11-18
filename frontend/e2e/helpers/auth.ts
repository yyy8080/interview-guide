import type { Page } from '@playwright/test';

export const E2E_ADMIN_TOKEN = 'e2e-admin-token';

export async function seedAdminAuth(
  page: Page,
  role: 'ADMIN' | 'HR' | 'INTERVIEWER' = 'ADMIN',
): Promise<void> {
  await page.addInitScript(
    ({ token, adminRole }) => {
      localStorage.setItem('ig_admin_token', token);
      localStorage.setItem('ig_admin_token_expires_at', '2099-12-31T23:59:59.000Z');
      localStorage.setItem(
        'ig_admin_user',
        JSON.stringify({
          id: 1,
          username: 'admin',
          displayName: '系统管理员',
          role: adminRole,
        }),
      );
    },
    { token: E2E_ADMIN_TOKEN, adminRole: role },
  );
}
