import { fetchJson } from './api-client';

/**
 * Account-level notification API (`/api/notifications/*`), shared by EasyAI applications.
 *
 * Everything is scoped to the login account and to the server's configured source, so no route
 * takes a user id or an application id. There is no publish call on purpose: only server-side
 * business code may create a notification.
 */

const BASE = '/api/notifications';

/** Which side of the event the signed-in account is on, as decided by the server. */
export type NotificationDirection = 'INCOMING' | 'OUTGOING' | 'INFO';

/** Lifecycle of the decision an event carries. */
export type NotificationActionStatus = 'NONE' | 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';

export type NotificationDecision = 'ACCEPT' | 'REJECT' | 'WITHDRAW';

export interface NotificationItem {
  id: string;
  /** Copies of one business event share this; answering settles all of them. */
  eventId: string;
  source: string;
  type: string;
  direction: NotificationDirection;
  actionStatus: NotificationActionStatus;
  title: string;
  body: string | null;
  /** Same-site relative path the client opens. */
  url: string | null;
  scopeName: string | null;
  actorName: string | null;
  targetName: string | null;
  createdAt: number;
  expiresAt: number | null;
  actionAt: number | null;
  readAt: number | null;
  canRespond: boolean;
  canWithdraw: boolean;
  unread: boolean;
}

export interface NotificationSummary {
  unread: number;
  actionable: number;
  outgoing: number;
  /** Unread ∪ live incoming decisions, each row counted once — the badge number. */
  attention: number;
}

export interface NotificationPage {
  items: NotificationItem[];
  nextCursor: string | null;
  summary: NotificationSummary;
}

export const EMPTY_SUMMARY: NotificationSummary = { unread: 0, actionable: 0, outgoing: 0, attention: 0 };

/** Path parameters are row ids today, but they are opaque strings from here. */
function path(id: string, action: string): string {
  return `${BASE}/${encodeURIComponent(id)}/${action}`;
}

function query(cursor?: string, limit?: number): string {
  const params = new URLSearchParams();
  if (cursor) params.set('cursor', cursor);
  if (limit) params.set('limit', String(limit));
  const suffix = params.toString();
  return suffix ? `?${suffix}` : '';
}

export const notificationService = {
  list: (cursor?: string, limit?: number): Promise<NotificationPage> =>
    fetchJson<NotificationPage>(`${BASE}${query(cursor, limit)}`),

  summary: (): Promise<NotificationSummary> => fetchJson<NotificationSummary>(`${BASE}/summary`),

  markRead: (id: string): Promise<NotificationItem> =>
    fetchJson<NotificationItem>(path(id, 'read'), { method: 'POST' }),

  markAllRead: (): Promise<{ read: number }> => fetchJson<{ read: number }>(`${BASE}/read-all`, { method: 'POST' }),

  respond: (id: string, decision: NotificationDecision): Promise<NotificationItem> => {
    const action = decision === 'ACCEPT' ? 'accept' : decision === 'REJECT' ? 'reject' : 'withdraw';
    return fetchJson<NotificationItem>(path(id, action), { method: 'POST' });
  },
};
