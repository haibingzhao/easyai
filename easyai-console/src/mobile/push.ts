import { pushService, type PushSubscriptionInput } from '../services/push-service';

/**
 * Web Push plumbing for a mobile shell, shared by EasyAI applications.
 *
 * Everything here is best-effort: the application's own inbox is the source of truth, so a
 * failed registration or sync only logs a warning. The one hard rule is that the permission
 * prompt is never requested here — iOS requires a user gesture, which only a settings page has.
 *
 * The worker and every API path are generic (`/api/push/*`); an application supplies only the
 * notification content (including the URL a click should open) when it calls the backend.
 */

const SERVICE_WORKER_URL = '/api/push/sw.js';
const SERVICE_WORKER_SCOPE = '/';

interface NavigatorWithStandalone extends Navigator {
  /** iOS Safari's legacy flag; `matchMedia` is the standard way, this is the fallback. */
  standalone?: boolean;
}

export function supportsPush(): boolean {
  return (
    window.isSecureContext &&
    'Notification' in window &&
    'serviceWorker' in navigator &&
    'PushManager' in window
  );
}

/** True only for a Home-Screen (standalone) app, the only iOS context that receives push. */
export function isStandalone(): boolean {
  const matchesDisplayMode =
    typeof window.matchMedia === 'function' && window.matchMedia('(display-mode: standalone)').matches;
  return matchesDisplayMode || (navigator as NavigatorWithStandalone).standalone === true;
}

/**
 * VAPID keys travel as URL-safe Base64 without padding; the push API wants raw bytes.
 *
 * The typed-array constructor is deliberately given an explicit `ArrayBuffer` so the result
 * satisfies TS 5.9's `BufferSource` (`ArrayBufferView<ArrayBuffer>`) instead of the wider
 * `ArrayBufferLike` that `new Uint8Array(length)` infers.
 */
export function urlBase64ToUint8Array(value: string): Uint8Array<ArrayBuffer> {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const remainder = normalized.length % 4;
  const padded = remainder === 0 ? normalized : normalized + '='.repeat(4 - remainder);
  const binary = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

let registrationPromise: Promise<ServiceWorkerRegistration | null> | null = null;

/**
 * Register the push worker once per page load.
 *
 * `updateViaCache: 'none'` is only a belt: the browser still applies the HTTP cache to the
 * script, which is why the server sends `no-cache` for this path.
 */
export function registerPushServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!('serviceWorker' in navigator)) return Promise.resolve(null);
  if (!registrationPromise) {
    registrationPromise = navigator.serviceWorker
      .register(SERVICE_WORKER_URL, { scope: SERVICE_WORKER_SCOPE, updateViaCache: 'none' })
      .then((registration) => {
        void registration.update().catch(() => undefined);
        return registration;
      })
      .catch((error: unknown) => {
        console.warn('[easyai] push service worker registration failed', error);
        // A transient failure must not disable push for the rest of the page's life.
        registrationPromise = null;
        return null;
      });
  }
  return registrationPromise;
}

function matchesApplicationServerKey(
  existing: ArrayBuffer | null,
  expected: Uint8Array<ArrayBuffer>
): boolean {
  if (!existing) return false;
  const actual = new Uint8Array(existing);
  if (actual.length !== expected.length) return false;
  for (let index = 0; index < actual.length; index += 1) {
    if (actual[index] !== expected[index]) return false;
  }
  return true;
}

/**
 * Return a subscription bound to the current VAPID key, re-creating it after a key rotation.
 *
 * The browser keeps the old subscription (and the old `applicationServerKey`) until it is
 * explicitly unsubscribed, and messages encrypted for the new key would be rejected.
 */
export async function ensureSubscription(
  registration: ServiceWorkerRegistration,
  publicKey: string,
): Promise<PushSubscription> {
  // Parse first: a malformed key from a misconfigured server must not destroy a working subscription.
  const expected = urlBase64ToUint8Array(publicKey);
  const existing = await registration.pushManager.getSubscription();
  if (existing && matchesApplicationServerKey(existing.options.applicationServerKey, expected)) {
    return existing;
  }
  if (existing) await existing.unsubscribe();
  return registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: expected,
  });
}

/** Convert a browser subscription into the JSON shape the server stores. */
export function subscriptionInput(subscription: PushSubscription): PushSubscriptionInput {
  const json = subscription.toJSON();
  const endpoint = json.endpoint;
  const p256dh = json.keys?.p256dh;
  const auth = json.keys?.auth;
  if (!endpoint || !p256dh || !auth) {
    throw new Error('订阅缺少推送密钥，请关闭通知后重新开启');
  }
  return { endpoint, keys: { p256dh, auth } };
}

const bootSyncsByUser = new Map<string, Promise<void>>();
let lastSyncedUserId: string | null = null;

/**
 * Re-report an existing subscription after boot.
 *
 * This is what heals the server side when a row was lost, when the same device signed into
 * another account (the endpoint is unique, so the upsert rebinds it), when the browser
 * rotated the endpoint, or when the VAPID key pair was rotated. It never creates a
 * subscription and never prompts: without a stored subscription the user has not enabled
 * notifications on this device yet.
 */
export function syncPushOnBoot(userId: string): Promise<void> {
  if (!supportsPush() || Notification.permission !== 'granted') {
    return Promise.resolve();
  }
  if (lastSyncedUserId === userId) return Promise.resolve();
  const inFlight = bootSyncsByUser.get(userId);
  if (inFlight) return inFlight;

  const sync = (async () => {
    try {
      const config = await pushService.config();
      if (!config.enabled || !config.publicKey) return;
      const registration = await registerPushServiceWorker();
      if (!registration) return;
      const existing = await registration.pushManager.getSubscription();
      if (!existing) return;
      // A rotated VAPID key leaves the stored subscription unusable (the push service
      // rejects it with 403), and nothing else would ever replace it.
      const subscription = await ensureSubscription(registration, config.publicKey);
      await pushService.subscribe(subscriptionInput(subscription));
      lastSyncedUserId = userId;
    } catch (error) {
      console.warn('[easyai] push subscription sync failed', error);
    } finally {
      bootSyncsByUser.delete(userId);
    }
  })();

  bootSyncsByUser.set(userId, sync);
  return sync;
}
