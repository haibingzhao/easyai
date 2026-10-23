import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authFetch, setAccessToken, setDefaultRequestHeaders } from '../api-client';

type FetchMock = ReturnType<typeof vi.fn>;

function mockFetch(): FetchMock {
  const fetchMock = vi.fn().mockResolvedValue(new Response('', { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function callOf(fetchMock: FetchMock, index = 0): [string, RequestInit] {
  return fetchMock.mock.calls[index] as [string, RequestInit];
}

beforeEach(() => {
  setAccessToken('tok');
});

afterEach(() => {
  setDefaultRequestHeaders(null);
  setAccessToken(null);
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('authFetch request headers', () => {
  it('sends host default headers on FormData requests and never a Content-Type', async () => {
    const fetchMock = mockFetch();
    setDefaultRequestHeaders(() => ({
      'X-Home-Family-Id': 'family-1',
      'Content-Type': 'application/json',
    }));

    await authFetch('/api/skills/upload', {
      method: 'POST',
      body: new FormData(),
      headers: { 'X-Call-Only': '1' },
    });

    const [url, init] = callOf(fetchMock);
    const headers = new Headers(init.headers);
    expect(url).toBe('/api/skills/upload?token=tok');
    expect(headers.get('X-Home-Family-Id')).toBe('family-1');
    expect(headers.get('Content-Type')).toBeNull();
    expect(headers.get('X-Call-Only')).toBeNull();
  });

  it('omits the headers object entirely when no host defaults are registered', async () => {
    const fetchMock = mockFetch();

    await authFetch('/api/user/avatar', { method: 'POST', body: new FormData() });

    const [url, init] = callOf(fetchMock);
    expect(url).toBe('/api/user/avatar?token=tok');
    expect(init.headers).toBeUndefined();
  });

  it('appends host defaults and the bearer token to JSON requests', async () => {
    const fetchMock = mockFetch();
    setDefaultRequestHeaders(() => ({ 'X-Home-Family-Id': 'family-1' }));

    await authFetch('/api/chat/session', { method: 'POST', body: '{}' });

    const [, init] = callOf(fetchMock);
    const headers = new Headers(init.headers);
    expect(headers.get('X-Home-Family-Id')).toBe('family-1');
    expect(headers.get('Authorization')).toBe('Bearer tok');
  });

  it('fails the request when the host header provider throws', async () => {
    mockFetch();
    setDefaultRequestHeaders(() => {
      throw new Error('no active family');
    });

    await expect(authFetch('/api/chat/session')).rejects.toThrow('no active family');
  });
});
