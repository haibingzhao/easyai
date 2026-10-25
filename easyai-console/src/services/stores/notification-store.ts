import { create } from 'zustand';
import { APP_CONFIG } from '@/constants/navigation';
import { readErrorMessage } from '@/services/api-client';
import {
  EMPTY_SUMMARY,
  notificationService,
  type NotificationDecision,
  type NotificationItem,
  type NotificationSummary,
} from '@/services/notification-service';

interface NotificationState {
  items: NotificationItem[];
  nextCursor: string | null;
  summary: NotificationSummary;
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  /** First page plus the badges, as one round trip. */
  load: () => Promise<void>;
  loadMore: () => Promise<void>;
  refreshSummary: () => Promise<void>;
  /** Opens one item: marks it read and returns, leaving navigation to the caller. */
  open: (item: NotificationItem) => Promise<void>;
  markAllRead: () => Promise<void>;
  /** The settled copy, or `null` when the decision failed — the host reloads only on success. */
  respond: (item: NotificationItem, decision: NotificationDecision) => Promise<NotificationItem | null>;
  reset: () => void;
}

/**
 * The one inbox state, shared by the avatar menu badge, the desktop page and the mobile page.
 *
 * Read state and decision state are separate on purpose: `open` clears the unread mark and nothing
 * else, so a request the user has merely looked at keeps nagging until they accept, reject or
 * withdraw it. `reset` runs on sign-out so the next account never inherits an open sheet.
 */
export const useNotificationStore = create<NotificationState>((set, get) => ({
  items: [],
  nextCursor: null,
  summary: EMPTY_SUMMARY,
  loading: false,
  loadingMore: false,
  error: null,

  load: async () => {
    if (!APP_CONFIG.notificationsPath) return;
    set({ loading: true, error: null });
    try {
      const page = await notificationService.list();
      set({ items: page.items, nextCursor: page.nextCursor, summary: page.summary, loading: false });
    } catch (error) {
      set({ loading: false, error: readErrorMessage(error) });
    }
  },

  loadMore: async () => {
    const { nextCursor, loadingMore } = get();
    if (!APP_CONFIG.notificationsPath || !nextCursor || loadingMore) return;
    set({ loadingMore: true });
    try {
      const page = await notificationService.list(nextCursor);
      // Append only what is still unknown: a row answered in another tab keeps its id but changes
      // state, and the fresh copy must win over the stale one already on screen.
      const incoming = new Map(page.items.map((item) => [item.id, item]));
      const merged = get().items.map((item) => incoming.get(item.id) ?? item);
      const known = new Set(merged.map((item) => item.id));
      set({
        items: [...merged, ...page.items.filter((item) => !known.has(item.id))],
        nextCursor: page.nextCursor,
        summary: page.summary,
        loadingMore: false,
      });
    } catch (error) {
      set({ loadingMore: false, error: readErrorMessage(error) });
    }
  },

  refreshSummary: async () => {
    if (!APP_CONFIG.notificationsPath) return;
    try {
      set({ summary: await notificationService.summary() });
    } catch {
      // A badge poll is not a user action: keep the last known count rather than announce a
      // transient failure. The page's own load reports errors.
    }
  },

  open: async (item) => {
    if (!item.unread) return;
    set({
      items: get().items.map((candidate) => (candidate.id === item.id ? { ...candidate, unread: false, readAt: Date.now() } : candidate)),
    });
    try {
      await notificationService.markRead(item.id);
      await get().refreshSummary();
    } catch {
      // The item is already on screen; a failed read marker only means the badge stays lit.
    }
  },

  markAllRead: async () => {
    try {
      await notificationService.markAllRead();
      const now = Date.now();
      // Outgoing copies keep their state: the server's read-all skips them too.
      set({
        items: get().items.map((item) =>
          item.direction === 'OUTGOING' || !item.unread ? item : { ...item, unread: false, readAt: now },
        ),
      });
      await get().refreshSummary();
    } catch (error) {
      set({ error: readErrorMessage(error) });
    }
  },

  respond: async (item, decision) => {
    try {
      const settled = await notificationService.respond(item.id, decision);
      set({ items: get().items.map((candidate) => (candidate.id === item.id ? settled : candidate)) });
      await get().refreshSummary();
      return settled;
    } catch (error) {
      // Re-read first: a 409 means someone else settled it, and the stale card must not keep
      // offering buttons that cannot work. Then report — `load` clears the error slot, so setting
      // the message before refreshing would lose it.
      await get().load();
      set({ error: readErrorMessage(error) });
      return null;
    }
  },

  reset: () =>
    set({
      items: [],
      nextCursor: null,
      summary: EMPTY_SUMMARY,
      loading: false,
      loadingMore: false,
      error: null,
    }),
}));

const POLL_INTERVAL_MS = 60_000;

let pollTimer: ReturnType<typeof setInterval> | undefined;
let windowHandler: (() => void) | undefined;
let documentHandler: (() => void) | undefined;

/**
 * Starts the badge loop: one summary request every minute while the tab is visible, plus one on
 * focus, so a banner the user acted on another device is reflected without a websocket.
 *
 * Idempotent, and a no-op when the host has not opted in — an app that never mounts a notification
 * page must not spend a request per minute on a 404.
 */
export function startNotificationPolling(): void {
  if (pollTimer || !APP_CONFIG.notificationsPath) return;
  const refresh = () => void useNotificationStore.getState().refreshSummary();
  refresh();
  pollTimer = setInterval(() => {
    if (document.visibilityState === 'visible') refresh();
  }, POLL_INTERVAL_MS);
  windowHandler = refresh;
  window.addEventListener('focus', windowHandler);
  documentHandler = () => {
    if (document.visibilityState === 'visible') refresh();
  };
  document.addEventListener('visibilitychange', documentHandler);
}

export function stopNotificationPolling(): void {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = undefined;
  if (windowHandler) window.removeEventListener('focus', windowHandler);
  if (documentHandler) document.removeEventListener('visibilitychange', documentHandler);
  windowHandler = undefined;
  documentHandler = undefined;
  useNotificationStore.getState().reset();
}
