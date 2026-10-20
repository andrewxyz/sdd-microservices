// The one request() every page and hook calls through (constitution XIII, research.md R1, R6).
// Targets same-origin /api/... only, attaches the bearer token when one exists, parses every
// failure through problem.ts, and performs a module-level single-flight refresh-and-retry on a
// 401 that was returned to an authenticated request.
import { clear, getAccessToken, getRefreshToken, setTokens } from "../auth/session";
import { ApiError, parseProblemResponse } from "./problem";

export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  /** Internal: set on the single retry after a refresh, so that retry can never itself refresh. */
  skipAuthRetry?: boolean;
}

type UnauthorizedHandler = (returnTo: string) => void;

let onUnauthorized: UnauthorizedHandler | null = null;

/** AuthProvider registers this once, so client.ts can trigger the redirect without importing React. */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  onUnauthorized = handler;
}

// The single in-flight refresh, shared by every concurrent caller (constitution XII, research.md R6).
let refreshPromise: Promise<boolean> | null = null;

async function performRefresh(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return false;
  }
  try {
    const response = await fetch("/api/v1/auth/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    if (!response.ok) {
      return false;
    }
    const pair = (await response.json()) as { accessToken?: string; refreshToken?: string };
    if (!pair.accessToken || !pair.refreshToken) {
      return false;
    }
    setTokens(pair.accessToken, pair.refreshToken);
    return true;
  } catch {
    return false;
  }
}

function refreshOnce(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

function currentPath(): string {
  return window.location.pathname + window.location.search;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, headers = {}, skipAuthRetry = false } = options;

  const finalHeaders: Record<string, string> = { ...headers };
  if (body !== undefined && !finalHeaders["Content-Type"]) {
    finalHeaders["Content-Type"] = "application/json";
  }
  const token = getAccessToken();
  if (token) {
    finalHeaders.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers: finalHeaders,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch (cause) {
    throw ApiError.fromNetworkFailure(cause);
  }

  if (response.status === 401 && token && !skipAuthRetry) {
    const refreshed = await refreshOnce();
    if (refreshed) {
      return request<T>(path, { ...options, skipAuthRetry: true });
    }
    clear();
    onUnauthorized?.(currentPath());
    throw await parseProblemResponse(response);
  }

  if (!response.ok) {
    throw await parseProblemResponse(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
