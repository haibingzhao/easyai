import React, { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CheckCheck, RefreshCw } from 'lucide-react';
import { NotificationCard } from '@/components/notification/NotificationCard';
import type { NotificationDecision, NotificationItem } from '@/services/notification-service';
import { useNotificationStore } from '@/services/stores/notification-store';
import { useTranslation } from '@/utils/use-translation';

/** Redraws the deadline text; data reloads on demand, while the badge summary polls on focus. */
const CLOCK_TICK_MS = 30_000;

export interface NotificationsPageProps {
  /**
   * Runs after a decision succeeded, with the settled copy.
   *
   * A host whose own permissions change as a result (accepting an ownership transfer, say) reloads
   * its identity here. A callback of this shape is how the framework stays out of business rules.
   */
  onActionComplete?: (item: NotificationItem) => void;
  /**
   * Heading inside the toolbar. Defaults to the translated word; pass `null` when the surrounding
   * chrome already names the page, which is what the mobile route does with its own header.
   */
  title?: string | null;
  /**
   * Whether clicking a card also follows its `url`.
   *
   * Defaults to true for the framework's own console, where `url` is a route of the same document.
   * A host whose url is a **push-only** deep link — the mobile document of a dual-document app —
   * passes false: the card then only marks the item read, and the link affordance is not rendered.
   */
  openUrlOnClick?: boolean;
}

/**
 * The shared inbox, in three groups that answer three different questions: what must I decide, what
 * am I waiting on, and what happened.
 *
 * Opening an item marks it read; only a decision removes it from the first group. The unread filter
 * lives in the URL, so the avatar's deep link (`?filter=unread`) and the toggle cannot disagree.
 */
export const NotificationsPage: React.FC<NotificationsPageProps> = ({
  onActionComplete,
  title,
  openUrlOnClick = true,
}) => {
  const t = useTranslation();
  const heading = title === undefined ? t('Notifications') : title;
  const [params, setParams] = useSearchParams();
  const items = useNotificationStore((state) => state.items);
  const summary = useNotificationStore((state) => state.summary);
  const nextCursor = useNotificationStore((state) => state.nextCursor);
  const loading = useNotificationStore((state) => state.loading);
  const loadingMore = useNotificationStore((state) => state.loadingMore);
  const error = useNotificationStore((state) => state.error);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const onlyUnread = params.get('filter') === 'unread';

  useEffect(() => {
    void useNotificationStore.getState().load();
  }, []);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), CLOCK_TICK_MS);
    return () => clearInterval(timer);
  }, []);

  const visible = useMemo(
    () => (onlyUnread ? items.filter((item) => item.unread) : items),
    [items, onlyUnread],
  );
  const groups = useMemo(
    () => ({
      pending: visible.filter((item) => item.canRespond),
      waiting: visible.filter((item) => item.canWithdraw),
      notices: visible.filter((item) => !item.canRespond && !item.canWithdraw),
    }),
    [visible],
  );

  const openItem = async (item: NotificationItem) => {
    await useNotificationStore.getState().open(item);
    if (!openUrlOnClick) return;
    // Assigned rather than router-navigated: `url` is authored as the same value the Service Worker
    // opens on a banner tap, so both entry points land on the same address. Within the current
    // document it degrades to a hash change; no reload, no second url format.
    if (item.url) window.location.assign(item.url);
  };

  const respond = async (item: NotificationItem, decision: NotificationDecision) => {
    setBusyId(item.id);
    try {
      const settled = await useNotificationStore.getState().respond(item, decision);
      if (settled) onActionComplete?.(settled);
    } finally {
      setBusyId(null);
    }
  };

  const handlers = { now, busyId, onOpen: openItem, onRespond: respond, showUrl: openUrlOnClick };

  return (
    <div className="flex flex-col flex-1 min-h-0">
      <div className="flex items-center gap-3 px-4 py-3 border-b border-border shrink-0">
        {heading && <h2 className="text-base font-medium truncate">{heading}</h2>}
        <div className="flex items-center gap-1 text-xs">
          <button
            type="button"
            onClick={() => setParams({}, { replace: true })}
            className={`px-2 py-1 rounded-md ${!onlyUnread ? 'bg-muted text-foreground' : 'text-muted-foreground hover:bg-muted'}`}
          >
            {t('All')}
          </button>
          <button
            type="button"
            onClick={() => setParams({ filter: 'unread' }, { replace: true })}
            className={`px-2 py-1 rounded-md ${onlyUnread ? 'bg-muted text-foreground' : 'text-muted-foreground hover:bg-muted'}`}
          >
            {t('Unread')}
            {summary.unread > 0 && <span className="ml-1 tabular-nums">{summary.unread}</span>}
          </button>
        </div>
        <div className="flex-1" />
        {summary.unread > 0 && (
          <button
            type="button"
            onClick={() => void useNotificationStore.getState().markAllRead()}
            className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted"
          >
            <CheckCheck className="w-3.5 h-3.5" />
            {t('Mark all read')}
          </button>
        )}
        <button
          type="button"
          onClick={() => void useNotificationStore.getState().load()}
          title={t('Refresh')}
          className="p-1.5 rounded-md text-muted-foreground hover:bg-muted"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
        </button>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto message-list-scroll px-4 py-3">
        {error && (
          <div className="mb-3 px-3 py-2 rounded-md bg-destructive/10 text-destructive text-xs break-words">{error}</div>
        )}

        {loading && items.length === 0 ? (
          <p className="py-10 text-center text-sm text-muted-foreground">{t('Loading...')}</p>
        ) : visible.length === 0 ? (
          <p className="py-10 text-center text-sm text-muted-foreground">
            {onlyUnread ? t('Nothing unread') : t('No notifications yet')}
          </p>
        ) : (
          <div className="space-y-4">
            <Group label={t('Needs your decision')} items={groups.pending} {...handlers} />
            <Group label={t('Waiting for the other side')} items={groups.waiting} {...handlers} />
            <Group label={t('Notices')} items={groups.notices} {...handlers} />

            {nextCursor && (
              <button
                type="button"
                disabled={loadingMore}
                onClick={() => void useNotificationStore.getState().loadMore()}
                className="w-full py-2 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted disabled:opacity-50"
              >
                {loadingMore ? t('Loading...') : t('Load more')}
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

interface GroupProps {
  label: string;
  items: NotificationItem[];
  now: number;
  busyId: string | null;
  onOpen: (item: NotificationItem) => void;
  onRespond: (item: NotificationItem, decision: NotificationDecision) => void;
  /** Mirrors `openUrlOnClick`: also controls the per-card external-link affordance. */
  showUrl: boolean;
}

/** A section of the inbox. An empty group renders nothing — three empty headers read as a broken page. */
const Group: React.FC<GroupProps> = ({ label, items, now, busyId, onOpen, onRespond, showUrl }) => {
  if (items.length === 0) return null;
  return (
    <section>
      <h3 className="mb-2 text-xs font-medium text-muted-foreground">{label}</h3>
      <div className="space-y-2">
        {items.map((item) => (
          <NotificationCard
            key={item.id}
            item={item}
            now={now}
            busy={busyId === item.id}
            onOpen={onOpen}
            onRespond={onRespond}
            showUrl={showUrl}
          />
        ))}
      </div>
    </section>
  );
};
