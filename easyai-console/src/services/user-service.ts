import type { UserProfile } from './auth-service';
import { fetchJson, JSON_HEADERS } from './api-client';

/**
 * The signed-in user's own profile. Every mutation answers with the fresh profile, so a caller can write
 * it straight into the auth store instead of re-reading `/api/auth/me`.
 */

export function updateProfile(input: { displayName?: string; email?: string }): Promise<UserProfile> {
  return fetchJson<UserProfile>('/api/users/me', {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify(input),
  });
}

/** Replace the avatar with a picture this client already resized to a square. */
export function uploadAvatar(file: File): Promise<UserProfile> {
  const form = new FormData();
  form.append('file', file);
  // No headers: authFetch strips them for a FormData body so the browser can set the multipart boundary.
  return fetchJson<UserProfile>('/api/users/me/avatar', { method: 'POST', body: form });
}

export function setAvatarUrl(url: string): Promise<UserProfile> {
  return fetchJson<UserProfile>('/api/users/me/avatar', {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify({ url }),
  });
}

export function removeAvatar(): Promise<UserProfile> {
  return fetchJson<UserProfile>('/api/users/me/avatar', { method: 'DELETE' });
}
