/** Colour-seeded fallback identity, shown until — or unless — an avatar resolves. */

const AVATAR_COLORS = [
  '#6366f1', '#8b5cf6', '#a855f7', '#d946ef',
  '#ec4899', '#f43f5e', '#ef4444', '#f97316',
  '#eab308', '#22c55e', '#14b8a6', '#06b6d4',
  '#3b82f6', '#2563eb',
];

function hashSeed(seed: string): number {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = seed.charCodeAt(i) + ((hash << 5) - hash);
  }
  return hash;
}

export function getAvatarColor(seed: string): string {
  return AVATAR_COLORS[Math.abs(hashSeed(seed)) % AVATAR_COLORS.length];
}

export function getInitials(name: string): string {
  return name.slice(0, 1).toUpperCase();
}

/** The stored value for "never picked a picture", mirroring AuthConstants.DEFAULT_AVATAR. */
const PRESET_AVATAR = 'avatar-1';

/** Mirrors the key grammar AvatarStorageService mints, so only our own objects are fetched with credentials. */
const AVATAR_OBJECT_KEY = /^avatars\/[A-Za-z0-9_-]{1,80}\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(?:png|jpe?g|gif|webp)$/;
/** Mirrors the backend's `AVATAR_URL` bound exactly, so a link that renders here is one that saves there. */
const EXTERNAL_IMAGE_URL = /^https?:\/\/\S{1,500}$/i;

export type AvatarSource =
  | { kind: 'initials' }
  /** A host the console does not control: render it, but never send it a token. */
  | { kind: 'external'; src: string }
  /** Our own object store: fetched with credentials and shown from an object URL. */
  | { kind: 'owned'; src: string };

/**
 * The avatar is the one user field that can name a remote host, so the shape decides the trust level:
 * a well-formed `avatars/…` key becomes the media endpoint, a bare http(s) link is used as typed, and
 * anything else — a `data:` URI, an empty value, a value a newer backend invented — falls back to initials.
 */
export function getAvatarSource(avatar: string | null | undefined): AvatarSource {
  const value = avatar?.trim();
  if (!value || value === PRESET_AVATAR) return { kind: 'initials' };
  if (AVATAR_OBJECT_KEY.test(value)) {
    return { kind: 'owned', src: `/api/media/file?key=${encodeURIComponent(value)}` };
  }
  if (EXTERNAL_IMAGE_URL.test(value)) return { kind: 'external', src: value };
  return { kind: 'initials' };
}
