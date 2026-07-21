/**
 * HTTP client with automatic JWT Authorization header injection
 * and transparent token refresh on 401.
 *
 * Access tokens are persisted to sessionStorage so they survive
 * page reloads without needing a refresh-token round-trip.
 */

const TOKEN_KEY = 'easyai_access_token';

let accessToken: string | null = sessionStorage.getItem(TOKEN_KEY);
let refreshPromise: Promise<string | null> | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
  if (token) {
    sessionStorage.setItem(TOKEN_KEY, token);
  } else {
    sessionStorage.removeItem(TOKEN_KEY);
  }
}

export function getAccessToken(): string | null {
  return accessToken;
}

async function doRefresh(): Promise<string | null> {
  const res = await fetch('/api/auth/refresh', {
    method: 'POST',
    credentials: 'same-origin',
  });
  if (!res.ok) return null;
  const data = await res.json();
  return data.accessToken ?? null;
}

async function refreshTokenIfNeeded(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => {
      refreshPromise = null;
    });
  }
  const newToken = await refreshPromise;
  if (newToken) {
    setAccessToken(newToken);
  }
  return newToken;
}

type AuthFetchOptions = RequestInit & { _retried?: boolean };

/**
 * Drop-in replacement for fetch() that adds Authorization header
 * and handles 401 → refresh → retry transparently.
 */
export async function authFetch(
  url: string,
  options?: AuthFetchOptions,
): Promise<Response> {
  const headers = new Headers(options?.headers);
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }

  const res = await fetch(url, { ...options, headers });

  // Retry once on 401 by refreshing the token
  if (res.status === 401 && !options?._retried) {
    const skipAuth = url.includes('/api/auth/');
    if (!skipAuth) {
      const newToken = await refreshTokenIfNeeded();
      if (newToken) {
        headers.set('Authorization', `Bearer ${newToken}`);
        return authFetch(url, { ...options, headers, _retried: true });
      }
    }
  }

  return res;
}
