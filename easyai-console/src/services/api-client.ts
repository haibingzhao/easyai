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
  const fetchOptions: RequestInit = { ...options };

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
 * Which ownership bucket a write targets. `group` writes the shared bucket and is refused (403) for
 * non-owners by the backend gate; the default (omitted) is the caller's personal bucket. Reads do not
 * take a scope — the backend folds the active group's bucket in from the token claims automatically.
 */
export type AssetScope = 'personal' | 'group';

/**
 * Fallback write scope for every console write that does not name one explicitly. Hosts that fold
 * group ownership into the login (e.g. home-console) set this once after auth so all service calls —
 * present and future — route into the group bucket without threading `scope` through each page.
 * Left `undefined`, writes default to the caller's personal bucket (zero impact on standalone use).
 */
let defaultWriteScope: AssetScope | undefined;

/** Route every console write into `scope`'s bucket; pass `undefined` to restore personal-bucket default. */
export function setDefaultAssetScope(scope: AssetScope | undefined): void {
  defaultWriteScope = scope;
}

/** The effective write scope for a call: the explicit one, else the host default. */
export function resolveWriteScope(scope?: AssetScope): AssetScope | undefined {
  return scope ?? defaultWriteScope;
}

/** Build the `?scope=` suffix for a write endpoint; empty when the effective scope is personal. */
export function scopeQuery(scope?: AssetScope): string {
  const s = resolveWriteScope(scope);
  return s ? `?scope=${s}` : '';
}

/** Append the effective `scope` to a URL that may already carry a query string. */
export function withScope(url: string, scope?: AssetScope): string {
  const s = resolveWriteScope(scope);
  if (!s) return url;
  const separator = url.includes('?') ? '&' : '?';
  return `${url}${separator}scope=${s}`;
}

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
 * Human-readable text from an Error thrown by fetchJson/fetchVoid: validation
 * failures arrive as a `{"error": "..."}` body rather than plain text.
 */
export function readErrorMessage(e: unknown, fallback = 'Request failed'): string {
  if (!(e instanceof Error)) return fallback;
  try {
    const body = JSON.parse(e.message) as { error?: unknown };
    if (typeof body.error === 'string' && body.error) return body.error;
  } catch {
    // Body wasn't a JSON error object — the message is already readable.
  }
  return e.message || fallback;
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
