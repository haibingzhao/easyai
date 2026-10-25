import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { APP_CONFIG } from '@/constants/navigation';
import { notificationService, EMPTY_SUMMARY } from '@/services/notification-service';
import type { NotificationItem, NotificationPage } from '@/services/notification-service';
import { startNotificationPolling, useNotificationStore } from '@/services/stores/notification-store';

vi.mock('@/services/notification-service', async () => {
  const actual = await vi.importActual<typeof import('@/services/notification-service')>('@/services/notification-service');
  return { ...actual, notificationService: { list: vi.fn(), summary: vi.fn(), markRead: vi.fn(), markAllRead: vi.fn(), respond: vi.fn() } };
});

const list = vi.mocked(notificationService.list);
const summary = vi.mocked(notificationService.summary);
const markRead = vi.mocked(notificationService.markRead);
const markAllRead = vi.mocked(notificationService.markAllRead);
const respond = vi.mocked(notificationService.respond);

function item(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id: 'n1',
    eventId: 'e1',
    source: 'home',
    type: 'owner_transfer',
    direction: 'INCOMING',
    actionStatus: 'PENDING',
    title: '把「家」转让给你',
    body: null,
    url: '/notifications',
    scopeName: '我的家',
    actorName: '老王',
    targetName: '小李',
    createdAt: 1_000,
    expiresAt: null,
    actionAt: null,
    readAt: null,
    canRespond: true,
    canWithdraw: false,
    unread: true,
    ...overrides,
  } as NotificationItem;
}

function page(items: NotificationItem[], nextCursor: string | null = null): NotificationPage {
  return {
    items,
    nextCursor,
    summary: {
      unread: items.filter((i) => i.unread).length,
      actionable: 0,
      outgoing: 0,
      attention: items.filter((i) => i.unread).length,
    },
  };
}

beforeEach(() => {
  APP_CONFIG.notificationsPath = '/notifications';
  list.mockReset();
  summary.mockReset().mockResolvedValue(EMPTY_SUMMARY);
  markRead.mockReset();
  markAllRead.mockReset();
  respond.mockReset();
  useNotificationStore.setState({ items: [], nextCursor: null, summary: EMPTY_SUMMARY, error: null });
});

afterEach(() => {
  APP_CONFIG.notificationsPath = undefined;
});

describe('the host switch gates every request', () => {
  it('nothing is fetched while the host has not mounted an inbox', async () => {
    APP_CONFIG.notificationsPath = undefined;

    await useNotificationStore.getState().load();
    await useNotificationStore.getState().refreshSummary();
    startNotificationPolling();

    expect(list).not.toHaveBeenCalled();
    expect(summary).not.toHaveBeenCalled();
  });

  // The loop also listens for focus and visibility, which is browser-only wiring; the gate in
  // front of it is what the case above pins.
});

describe('read state is separate from the decision', () => {
  it('opening clears the mark and reports to the server', async () => {
    const open = item();
    useNotificationStore.setState({ items: [open] });
    markRead.mockResolvedValue({ ...open, unread: false, readAt: 2_000 });

    await useNotificationStore.getState().open(open);

    expect(markRead).toHaveBeenCalledWith('n1');
    expect(useNotificationStore.getState().items[0].unread).toBe(false);
    expect(useNotificationStore.getState().items[0].actionStatus).toBe('PENDING');
  });

  it('opening an already-read row makes no request', async () => {
    const read = item({ unread: false, readAt: 500 });

    await useNotificationStore.getState().open(read);

    expect(markRead).not.toHaveBeenCalled();
  });

  it('mark-all-read leaves the copies this account sent', async () => {
    useNotificationStore.setState({
      items: [item(), item({ id: 'n2', direction: 'OUTGOING', canRespond: false, canWithdraw: true, unread: false })],
    });
    markAllRead.mockResolvedValue({ read: 1 });

    await useNotificationStore.getState().markAllRead();

    const [incoming, outgoing] = useNotificationStore.getState().items;
    expect(incoming.unread).toBe(false);
    expect(outgoing.actionStatus).toBe('PENDING');
    expect(outgoing.canWithdraw).toBe(true);
  });
});

describe('errors', () => {
  it('a JSON error body is surfaced as its message, not as raw JSON', async () => {
    list.mockRejectedValue(new Error('{"error":"数据库暂不可用","status":503}'));

    await useNotificationStore.getState().load();

    expect(useNotificationStore.getState().error).toBe('数据库暂不可用');
  });
});

describe('decisions', () => {
  it('a settled answer replaces the card and succeeds for the host', async () => {    const open = item();
    useNotificationStore.setState({ items: [open] });
    const settled = { ...open, actionStatus: 'ACCEPTED' as const, canRespond: false, unread: false };
    respond.mockResolvedValue(settled);

    const result = await useNotificationStore.getState().respond(open, 'ACCEPT');

    expect(result).toEqual(settled);
    expect(useNotificationStore.getState().items[0].actionStatus).toBe('ACCEPTED');
  });

  it('a lost race reports the error and re-reads, so no dead button stays on screen', async () => {
    const open = item();
    useNotificationStore.setState({ items: [open] });
    respond.mockRejectedValue(new Error('该请求已被处理，请刷新后重试'));
    list.mockResolvedValue(page([{ ...open, actionStatus: 'REJECTED', canRespond: false }]));

    const result = await useNotificationStore.getState().respond(open, 'ACCEPT');

    expect(result).toBeNull();
    expect(useNotificationStore.getState().error).toContain('已被处理');
    expect(list).toHaveBeenCalledTimes(1);
    expect(useNotificationStore.getState().items[0].canRespond).toBe(false);
  });
});

describe('paging', () => {
  it('the next page neither repeats nor reorders what is already shown', async () => {
    const first = item({ id: 'a', createdAt: 3_000 });
    const shared = item({ id: 'b', createdAt: 2_000 });
    useNotificationStore.setState({ items: [first, shared], nextCursor: '2000~b' });
    // The second page returns `b` again, now answered from another device.
    list.mockResolvedValue(page([{ ...shared, actionStatus: 'ACCEPTED', canRespond: false }, item({ id: 'c', createdAt: 1_000 })], null));

    await useNotificationStore.getState().loadMore();

    const state = useNotificationStore.getState();
    expect(state.items.map((row) => row.id)).toEqual(['a', 'b', 'c']);
    expect(state.items[1].actionStatus).toBe('ACCEPTED');
    expect(state.nextCursor).toBeNull();
  });

  it('a missing cursor stops the call', async () => {
    await useNotificationStore.getState().loadMore();

    expect(list).not.toHaveBeenCalled();
  });
});
