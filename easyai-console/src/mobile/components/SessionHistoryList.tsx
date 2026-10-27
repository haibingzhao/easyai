import React, { useEffect, useMemo, useState } from 'react';
import { RefreshCw, Search, Trash2, X } from 'lucide-react';
import { SessionItem } from '@/components/ui/SessionItem';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useChatStore } from '@/services/stores/chat-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { useSessionStore } from '@/services/stores/session-store';
import { useProjectStore } from '@/services/stores/project-store';
import { useNavStore } from '@/services/stores/nav-store';
import { useTeamStore } from '@/services/stores/team-store';
import { useDisplaySessions } from '@/hooks/useDisplaySessions';
import { groupSessionsByTime } from '@/utils/session-time';
import { switchToSession } from '@/services/session-switch';
import { i18n } from '@/utils/i18n';
import { MobileSwipeRow } from './MobileSwipeRow';

const GROUP_LABELS: { key: 'today' | 'thisWeek' | 'older'; labelKey: string }[] = [
  { key: 'today', labelKey: 'Today' },
  { key: 'thisWeek', labelKey: 'This Week' },
  { key: 'older', labelKey: 'Older' },
];

/** Search walks a few extra pages until a title match appears, then stops and leaves paging to the user. */
const SEARCH_PAGE_SIZE = 20;
const SEARCH_LOAD_CAP = 100;
const SEARCH_DEBOUNCE_MS = 300;

interface SessionHistoryListProps {
  open: boolean;
  /** Called after a session is switched to, so the host can close its sheet. */
  onPicked: () => void;
}

/**
 * Full session history: search, tag filter, time groups, paging and swipe-to-delete.
 *
 * Lives in the chat header's sheet instead of a tab of its own — the phone has four tab slots
 * and history is only ever a lookup, not a place to stay. The same store drives the desktop
 * Sessions panel, so scoping (project vs project-less) and the live streaming overlay agree.
 */
export const SessionHistoryList: React.FC<SessionHistoryListProps> = ({ open, onPicked }) => {
  const {
    remoteSessionHasMore,
    remoteSessionOffset,
    remoteSessionLoading,
    loadRemoteSessions,
    loadMoreRemoteSessions,
    deleteRemoteSession,
    availableTags,
    activeTagFilter,
    setTagFilter,
    setCurrentSessionId,
  } = useSessionStore();
  const { clearChat, sessionId: chatSessionId } = useChatStore();
  const { currentProject } = useProjectStore();
  const resetTeam = useTeamStore((s) => s.resetTeam);
  const displaySessions = useDisplaySessions();

  const [query, setQuery] = useState('');
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);
  const [switchingId, setSwitchingId] = useState<string | null>(null);

  // A temporary workspace belongs to one session only, so filtering by it would show exactly that
  // session; project-less mode instead lists the sessions that carry no project.
  const listProjectId = currentProject && currentProject.kind !== 'temp' ? currentProject.id : undefined;
  const tempWorkspaceOnly = listProjectId === undefined;

  // Reload on every open so a session started on the laptop shows up here as well. The tag filter
  // is deliberately inherited: the chips below show why the list is narrowed.
  useEffect(() => {
    if (!open) return;
    void loadRemoteSessions(20, false, listProjectId, tempWorkspaceOnly);
  }, [open, loadRemoteSessions, listProjectId, activeTagFilter, tempWorkspaceOnly]);

  // Titles only: the list endpoint returns no message preview, so a body search would need a
  // per-session fetch. With no hit in the loaded pages yet, page forward until one shows up or the
  // window cap is reached — a broad query must not walk the entire history one request at a time.
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return displaySessions;
    return displaySessions.filter((s) => (s.title ?? '').toLowerCase().includes(q));
  }, [displaySessions, query]);

  const searchActive = query.trim().length > 0;
  useEffect(() => {
    if (!open || !searchActive || filtered.length > 0) return;
    if (!remoteSessionHasMore || remoteSessionLoading) return;
    if (remoteSessionOffset >= SEARCH_LOAD_CAP) return;
    const timer = setTimeout(() => {
      void loadMoreRemoteSessions(SEARCH_PAGE_SIZE, listProjectId, tempWorkspaceOnly);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [
    open,
    searchActive,
    filtered.length,
    remoteSessionHasMore,
    remoteSessionLoading,
    remoteSessionOffset,
    loadMoreRemoteSessions,
    listProjectId,
    tempWorkspaceOnly,
  ]);

  const grouped = useMemo(() => groupSessionsByTime(filtered), [filtered]);

  const openSession = async (id: string) => {
    if (switchingId) return;
    setSwitchingId(id);
    try {
      await switchToSession(id);
      onPicked();
    } catch (e) {
      console.error('Failed to load session:', e);
    } finally {
      setSwitchingId(null);
    }
  };

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    const isCurrentSession = chatSessionId === pendingDelete;
    setPendingDelete(null);
    await deleteRemoteSession(pendingDelete);
    if (isCurrentSession) {
      clearChat();
      resetTeam();
      useAgentStore.getState().resetAgentSelection();
      useNavStore.getState().setSelectedFile(null);
      setCurrentSessionId(null);
    }
  };

  const toggleTag = (tag: string) => {
    setTagFilter(activeTagFilter.includes(tag) ? activeTagFilter.filter((t) => t !== tag) : [...activeTagFilter, tag]);
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="shrink-0 space-y-2 border-b border-border px-3 py-2">
        <div className="flex items-center gap-2">
          <div className="relative min-w-0 flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={i18n('Search sessions...')}
              className="h-10 w-full rounded-lg border border-input bg-transparent pl-9 pr-8 text-base focus:outline-none focus:ring-2 focus:ring-ring"
            />
            {query && (
              <button
                className="mobile-hit !min-w-0 absolute right-1 top-1/2 -translate-y-1/2 p-1.5"
                onClick={() => setQuery('')}
                aria-label={i18n('Clear')}
              >
                <X className="h-4 w-4 text-muted-foreground" />
              </button>
            )}
          </div>
          <button
            className="mobile-hit p-1.5 text-muted-foreground"
            onClick={() => void loadRemoteSessions(20, false, listProjectId, tempWorkspaceOnly)}
            disabled={remoteSessionLoading}
            aria-label={i18n('Retry')}
          >
            <RefreshCw className={`h-5 w-5 ${remoteSessionLoading ? 'animate-spin' : ''}`} />
          </button>
        </div>

        {availableTags.length > 0 && (
          <div className="flex gap-1.5 overflow-x-auto pb-0.5">
            <button
              className={`shrink-0 rounded-full border px-2.5 py-1 text-xs ${
                activeTagFilter.length === 0
                  ? 'border-primary/30 bg-primary/15 text-primary'
                  : 'border-border bg-muted text-muted-foreground'
              }`}
              onClick={() => setTagFilter([])}
            >
              {i18n('All')}
            </button>
            {availableTags.map((tag) => (
              <button
                key={tag}
                onClick={() => toggleTag(tag)}
                className={`max-w-[140px] shrink-0 truncate rounded-full border px-2.5 py-1 text-xs ${
                  activeTagFilter.includes(tag)
                    ? 'border-primary/30 bg-primary/15 text-primary'
                    : 'border-border bg-muted text-muted-foreground'
                }`}
              >
                {tag}
              </button>
            ))}
          </div>
        )}
      </div>

      <div
        className="flex-1 overflow-y-auto overscroll-contain pb-[env(safe-area-inset-bottom)]"
        onScroll={(e) => {
          const el = e.currentTarget;
          if (!remoteSessionHasMore || remoteSessionLoading) return;
          if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) {
            void loadMoreRemoteSessions(20, listProjectId, tempWorkspaceOnly);
          }
        }}
      >
        {remoteSessionLoading && filtered.length === 0 ? (
          <p className="py-10 text-center text-sm text-muted-foreground">{i18n('Loading...')}</p>
        ) : filtered.length === 0 ? (
          <p className="py-10 text-center text-sm text-muted-foreground">
            {searchActive ? i18n('No matching sessions') : i18n('No sessions yet')}
          </p>
        ) : (
          GROUP_LABELS.map(({ key, labelKey }) => {
            const rows = grouped[key] ?? [];
            if (rows.length === 0) return null;
            return (
              <div key={key}>
                <div className="mobile-section-title">{i18n(labelKey)}</div>
                {rows.map((session) => (
                  <MobileSwipeRow
                    key={session.id}
                    actions={[
                      {
                        id: 'delete',
                        labelKey: 'Delete',
                        icon: Trash2,
                        tone: 'destructive',
                        onSelect: () => setPendingDelete(session.id),
                      },
                    ]}
                  >
                    <SessionItem
                      session={session}
                      isSelected={chatSessionId === session.id}
                      loading={switchingId === session.id}
                      showDelete={false}
                      onSelect={(id) => void openSession(id)}
                    />
                  </MobileSwipeRow>
                ))}
              </div>
            );
          })
        )}

        {remoteSessionHasMore && (
          <button
            className="mobile-row w-full justify-center text-sm text-muted-foreground disabled:opacity-40"
            onClick={() => void loadMoreRemoteSessions(20, listProjectId, tempWorkspaceOnly)}
            disabled={remoteSessionLoading}
          >
            {remoteSessionLoading ? i18n('Loading...') : i18n('Load More')}
          </button>
        )}
      </div>

      <ConfirmDialog
        open={!!pendingDelete}
        message={i18n('Delete session and its branches?')}
        confirmLabel={i18n('Delete')}
        danger
        onConfirm={() => void confirmDelete()}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
};
