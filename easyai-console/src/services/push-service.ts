import { fetchJson, JSON_HEADERS } from './api-client';

/**
 * Account-level Web Push API (`/api/push/*`), shared by EasyAI applications.
 *
 * A subscription belongs to the login account, so no application-scoped header or id is
 * involved: the host's normal request defaults plus the bearer token are all the server needs.
 */

const BASE = '/api/push';

export interface PushConfig {
  /** Effective enablement: server switch **and** a loadable VAPID key pair. */
  enabled: boolean;
  publicKey: string | null;
  /** Human-readable reason when `enabled` is false; never contains a filesystem path. */
  reason: string | null;
}

export interface PushSubscriptionInput {
  endpoint: string;
  keys: { p256dh: string; auth: string };
}

export interface PushTestResult {
  sent: number;
  failed: number;
  gone: number;
  /** The server-side batch deadline elapsed; the counters only cover attempted devices. */
  timedOut: boolean;
}

export const pushService = {
  config: (): Promise<PushConfig> => fetchJson(`${BASE}/config`),

  subscribe: (input: PushSubscriptionInput): Promise<{ subscribed: boolean }> =>
    fetchJson(`${BASE}/subscriptions`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(input),
    }),

  unsubscribe: (endpoint: string): Promise<{ removed: number }> =>
    fetchJson(`${BASE}/unsubscribe`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ endpoint }),
    }),

  test: (): Promise<PushTestResult> => fetchJson(`${BASE}/test`, { method: 'POST' }),
};
