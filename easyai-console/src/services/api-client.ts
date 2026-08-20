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
  // Detect FormData body — the browser must auto-set Content-Type with the
  // multipart boundary.  Passing an explicit `headers` object prevents that
  // in some environments, so for FormData we authenticate via the `token`
  // query parameter (already supported by JwtAuthenticationFilter) and
  // omit `headers` from the fetch init entirely.
  const isFormData = typeof FormData !== 'undefined' && options?.body instanceof FormData;

  let effectiveUrl = url;
  let fetchOptions: RequestInit = { ...options };

  if (isFormData) {
    // Remove headers so the browser auto-sets Content-Type: multipart/form-data
    delete fetchOptions.headers;

    // Append auth token as query parameter
    if (accessToken) {
      const separator = effectiveUrl.includes('?') ? '&' : '?';
      effectiveUrl = `${effectiveUrl}${separator}token=${encodeURIComponent(accessToken)}`;
    }
  } else {
    // Non-FormData: build headers normally
    const hasOwnHeaders = options?.headers != null;
    const headers = hasOwnHeaders ? new Headers(options.headers) : new Headers();
    if (accessToken) {
      headers.set('Authorization', `Bearer ${accessToken}`);
    }
    if (hasOwnHeaders || accessToken) {
      fetchOptions.headers = headers;
    }
  }

  const res = await fetch(effectiveUrl, fetchOptions);

  // Retry once on 401 by refreshing the token
  if (res.status === 401 && !options?._retried) {
    const skipAuth = url.includes('/api/auth/');
    if (!skipAuth) {
      const newToken = await refreshTokenIfNeeded();
      if (newToken) {
        return authFetch(url, { ...options, _retried: true });
      }
    }
  }

  return res;
}

export const JSON_HEADERS = { 'Content-Type': 'application/json' } as const;

/**
 * Convenience wrapper: authFetch + ok-check + JSON parse.
 * Throws an Error with the response body (or status) on non-ok responses.
 */
export async function fetchJson<T>(url: string, options?: AuthFetchOptions): Promise<T> {
  const response = await authFetch(url, options);
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(body || `Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

/**
 * Convenience wrapper for endpoints that return no body (DELETE, etc.).
 */
export async function fetchVoid(url: string, options?: AuthFetchOptions): Promise<void> {
  const response = await authFetch(url, options);
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(body || `Request failed: ${response.status}`);
  }
}

/**
 * Trigger a browser file-download from a Blob.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
