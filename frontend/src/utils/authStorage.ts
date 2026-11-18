import type { AdminUser, LoginResponse } from '../types/auth';

const TOKEN_KEY = 'ig_admin_token';
const EXPIRES_KEY = 'ig_admin_token_expires_at';
const USER_KEY = 'ig_admin_user';

/** 令牌到期前预留的缓冲时间，避免请求途中过期 */
const EXPIRY_SKEW_MS = 30_000;

export const AUTH_CHANGED_EVENT = 'ig-auth-changed';

/** 公开路径（候选人门户）不携带后台令牌，401 时也不跳转后台登录页 */
const PUBLIC_API_PREFIXES = ['/api/public/', '/api/candidate/', '/api/admin/auth/login'];

export function isPublicApiUrl(url: string | undefined): boolean {
  if (!url) return false;
  try {
    const path = new URL(url, window.location.origin).pathname;
    return PUBLIC_API_PREFIXES.some(prefix => path.startsWith(prefix));
  } catch {
    return PUBLIC_API_PREFIXES.some(prefix => url.startsWith(prefix));
  }
}

export function saveAuth(response: LoginResponse): void {
  localStorage.setItem(TOKEN_KEY, response.token);
  localStorage.setItem(EXPIRES_KEY, response.expiresAt);
  localStorage.setItem(USER_KEY, JSON.stringify(response.user));
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

export function updateStoredUser(user: AdminUser): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user));
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(EXPIRES_KEY);
  localStorage.removeItem(USER_KEY);
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

/** 返回未过期的令牌；已过期时自动清理并返回 null */
export function getValidToken(): string | null {
  const token = localStorage.getItem(TOKEN_KEY);
  if (!token) return null;
  const expiresAt = localStorage.getItem(EXPIRES_KEY);
  if (expiresAt) {
    const expiresMs = Date.parse(expiresAt);
    if (!Number.isNaN(expiresMs) && expiresMs - EXPIRY_SKEW_MS <= Date.now()) {
      clearAuth();
      return null;
    }
  }
  return token;
}

export function getStoredUser(): AdminUser | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AdminUser;
  } catch {
    return null;
  }
}

export function isAuthenticated(): boolean {
  return getValidToken() !== null;
}

export const ADMIN_LOGIN_PATH = '/admin/login';

/** 跳转后台登录页，并记录当前地址用于登录后返回 */
export function redirectToLogin(): void {
  const { pathname, search } = window.location;
  if (pathname.startsWith(ADMIN_LOGIN_PATH)) return;
  const redirect = encodeURIComponent(pathname + search);
  window.location.assign(`${ADMIN_LOGIN_PATH}?redirect=${redirect}`);
}
