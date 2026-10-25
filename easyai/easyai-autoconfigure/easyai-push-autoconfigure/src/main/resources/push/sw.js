/*
 * Web Push service worker shared by EasyAI applications.
 *
 * Deliberately does NOT register a `fetch` handler: it must never take over caching or
 * navigation, otherwise a stale worker can serve an old console. The only jobs are showing
 * a notification and routing a click back into the app.
 *
 * Served at /api/push/sw.js (public) with `Service-Worker-Allowed: /` and `no-store`, so the
 * worker can claim the root scope and a deployed fix is picked up immediately.
 */
const DEFAULT_TITLE = 'EasyAI';
const DEFAULT_BODY = '你有一条新通知';
const DEFAULT_URL = '/';

self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

function readPayload(event) {
  if (!event.data) return {};
  try {
    const value = event.data.json();
    return value && typeof value === 'object' ? value : {};
  } catch (error) {
    // A non-JSON payload must still surface a notification with the defaults.
    return {};
  }
}

function textOr(value, fallback) {
  return typeof value === 'string' && value.length > 0 ? value : fallback;
}

self.addEventListener('push', (event) => {
  const payload = readPayload(event);
  const options = {
    body: textOr(payload.body, DEFAULT_BODY),
    tag: textOr(payload.notificationId, undefined),
    data: { url: textOr(payload.url, DEFAULT_URL) },
  };
  const icon = textOr(payload.icon, undefined);
  if (icon) options.icon = icon;

  event.waitUntil(self.registration.showNotification(textOr(payload.title, DEFAULT_TITLE), options));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  const url = textOr(data.url, DEFAULT_URL);
  event.waitUntil(openOrFocus(url));
});

async function openOrFocus(url) {
  const target = new URL(url, self.location.origin);
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });

  for (const client of windows) {
    let clientOrigin;
    try {
      clientOrigin = new URL(client.url).origin;
    } catch (error) {
      // A malformed client URL must not abort the whole click handling.
      continue;
    }
    if (clientOrigin !== target.origin) continue;
    try {
      await client.focus();
    } catch (error) {
      // The window may be closing; try the next one or open a fresh window.
      continue;
    }
    if (typeof client.navigate === 'function') {
      try {
        await client.navigate(target.href);
      } catch (error) {
        // Navigation can be rejected while the client unloads; focusing is enough.
      }
    }
    return;
  }

  await self.clients.openWindow(target.href);
}
