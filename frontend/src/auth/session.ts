// Session storage, per constitution XII: the access token lives in memory only (a module-level
// variable, never written to any storage); the refresh token is the one thing that survives a
// reload, held in sessionStorage so a reload keeps the user signed in (FR-013) but a new tab does
// not (sessionStorage is per-tab). Nothing is ever written to localStorage.
const REFRESH_TOKEN_KEY = "learnhub.refreshToken";

let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getRefreshToken(): string | null {
  return sessionStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setTokens(access: string, refresh: string): void {
  accessToken = access;
  sessionStorage.setItem(REFRESH_TOKEN_KEY, refresh);
}

/** Clears both the in-memory access token and the persisted refresh token. */
export function clear(): void {
  accessToken = null;
  sessionStorage.removeItem(REFRESH_TOKEN_KEY);
}
