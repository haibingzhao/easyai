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
  // multipart boundary.  An explicit Content-Type prevents that in some
  // environments, so for FormData we authenticate via the `token` query
  // parameter (already supported by JwtAuthenticationFilter) and only keep the
  // host's default headers, never caller-supplied ones.
  const isFormData = typeof FormData !== 'undefined' && options?.body instanceof FormData;

  let effectiveUrl = url;
  const fetchOptions: RequestInit = { ...options };

  if (isFormData) {
    // Call-specific headers (including any Content-Type) are dropped: they would break the
    // multipart boundary. Default headers carry the host's request scope and must still apply.
    const defaults = Object.entries(defaultRequestHeadersProvider?.() ?? {})
      .filter(([key]) => key.toLowerCase() !== 'content-type');
    if (defaults.length > 0) {
      fetchOptions.headers = new Headers(defaults);
    } else {
      delete fetchOptions.headers;
    }

    // Append auth token as query parameter
    if (accessToken) {
      const separator = effectiveUrl.includes('?') ? '&' : '?';
      effectiveUrl = `${effectiveUrl}${separator}token=${encodeURIComponent(accessToken)}`;
    }
  } else {
    // Non-FormData: build headers normally. Default (host) headers go first so a call's own
    // headers and the bearer token always win over them.
    const headers = new Headers(defaultRequestHeadersProvider?.() ?? {});
    if (options?.headers) {
      new Headers(options.headers).forEach((value, key) => headers.set(key, value));
    }
    if (accessToken) {
      headers.set('Authorization', `Bearer ${accessToken}`);
    }
    fetchOptions.headers = headers;
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
 * Headers every non-FormData request should carry, decided by the host at request time.
 *
 * Hosts that scope requests by a mutable selection (e.g. home-console's active family) register
 * a provider once; returning a fresh object per call keeps the value live without re-registering.
 * Call-specific headers win over these. FormData requests carry these headers too, minus any
 * `Content-Type` (the browser must set the multipart boundary) and minus caller-supplied headers.
 *
 * A provider that throws fails the request: `authFetch` never swallows the error, because an
 * unscoped request could act in the wrong scope. Keep the provider total.
 */
type DefaultRequestHeadersProvider = () => Record<string, string>;
let defaultRequestHeadersProvider: DefaultRequestHeadersProvider | null = null;

/** Register (or clear, with null) the host-provided default request headers. */
export function setDefaultRequestHeaders(provider: DefaultRequestHeadersProvider | null): void {
  defaultRequestHeadersProvider = provider;
}

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

/**
 * Build the `?scope=` suffix for a write endpoint; empty when the effective scope is personal.
 * A read whose absent scope means *effective* (e.g. `chatPreferenceService.get()`) must name the
 * bucket itself instead: the host write default would silently narrow it to one bucket's own row.
 */
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
 * The readable text of an HTTP error body: validation failures arrive as a `{"error": "..."}`
 * body (WebFlux renders `ResponseStatusException` as RFC 7807 problem details, hence `detail`)
 * rather than plain text. Callers that cap length must run this first, or the slice turns the
 * JSON into an unparseable prefix.
 */
export function readErrorBody(body: string): string {
  try {
    const parsed = JSON.parse(body) as { error?: unknown; detail?: unknown } | null;
    if (parsed && typeof parsed.error === 'string' && parsed.error) return parsed.error;
    if (parsed && typeof parsed.detail === 'string' && parsed.detail) return parsed.detail;
  } catch {
    // Body wasn't a JSON error object — it is already readable.
  }
  return body;
}

/**
 * Human-readable text from an Error thrown by fetchJson/fetchVoid, whose message carries the
 * raw error body.
 */
export function readErrorMessage(e: unknown, fallback = 'Request failed'): string {
  if (!(e instanceof Error)) return fallback;
  return readErrorBody(e.message) || fallback;
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
