import type { CandidateLoginResponse, CandidateProfile } from '../types/recruitment';

const TOKEN_KEY = 'ig_candidate_token';
const EXPIRES_KEY = 'ig_candidate_token_expires_at';
const PROFILE_KEY = 'ig_candidate_profile';
const EXPIRY_SKEW_MS = 30_000;

export const CANDIDATE_TOKEN_HEADER = 'X-Candidate-Token';
export const CANDIDATE_AUTH_CHANGED_EVENT = 'ig-candidate-auth-changed';
export const CANDIDATE_LOGIN_PATH = '/careers/login';

export function isCandidateApiUrl(url: string | undefined): boolean {
  if (!url) return false;
  try {
    const path = new URL(url, window.location.origin).pathname;
    return path.startsWith('/api/candidate/');
  } catch {
    return url.startsWith('/api/candidate/');
  }
}

export function saveCandidateAuth(response: CandidateLoginResponse): void {
  localStorage.setItem(TOKEN_KEY, response.token);
  localStorage.setItem(EXPIRES_KEY, response.expiresAt);
  localStorage.setItem(PROFILE_KEY, JSON.stringify(response.candidate));
  window.dispatchEvent(new Event(CANDIDATE_AUTH_CHANGED_EVENT));
}

export function updateStoredCandidate(profile: CandidateProfile): void {
  localStorage.setItem(PROFILE_KEY, JSON.stringify(profile));
  window.dispatchEvent(new Event(CANDIDATE_AUTH_CHANGED_EVENT));
}

export function clearCandidateAuth(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(EXPIRES_KEY);
  localStorage.removeItem(PROFILE_KEY);
  window.dispatchEvent(new Event(CANDIDATE_AUTH_CHANGED_EVENT));
}

export function getValidCandidateToken(): string | null {
  const token = localStorage.getItem(TOKEN_KEY);
  if (!token) return null;
  const expiresAt = localStorage.getItem(EXPIRES_KEY);
  if (expiresAt) {
    const expiresMs = Date.parse(expiresAt);
    if (!Number.isNaN(expiresMs) && expiresMs - EXPIRY_SKEW_MS <= Date.now()) {
      clearCandidateAuth();
      return null;
    }
  }
  return token;
}

export function getStoredCandidate(): CandidateProfile | null {
  if (!getValidCandidateToken()) return null;
  const raw = localStorage.getItem(PROFILE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as CandidateProfile;
  } catch {
    return null;
  }
}

export function isCandidateAuthenticated(): boolean {
  return getValidCandidateToken() !== null;
}

export function redirectToCandidateLogin(): void {
  const { pathname, search } = window.location;
  if (pathname.startsWith(CANDIDATE_LOGIN_PATH)) return;
  const redirect = encodeURIComponent(pathname + search);
  window.location.assign(`${CANDIDATE_LOGIN_PATH}?redirect=${redirect}`);
}
