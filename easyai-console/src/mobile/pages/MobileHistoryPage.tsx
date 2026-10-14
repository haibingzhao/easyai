import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, RefreshCw, Search, X } from 'lucide-react';
import { SessionItem } from '@/components/ui/SessionItem';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { MobileHeader } from '../components/MobileHeader';
import { MobileSwipeRow } from '../components/MobileSwipeRow';
import { useChatStore } from '@/services/stores/chat-store';
import { useSessionStore } from '@/services/stores/session-store';
import { useProjectStore } from '@/services/stores/project-store';
import { useNavStore } from '@/services/stores/nav-store';
import { useTeamStore } from '@/services/stores/team-store';
import { useDisplaySessions } from '@/hooks/useDisplaySessions';
import { groupSessionsByTime } from '@/utils/session-time';
import { switchToSession } from '@/services/session-switch';
import { i18n } from '@/utils/i18n';

const GROUP_LABELS: { key: 'today' | 'thisWeek' | 'older'; labelKey: string }[] = [
  { key: 'today', labelKey: 'Today' },
  { key: 'thisWeek', labelKey: 'This Week' },
  { key: 'older', labelKey: 'Older' },
];

/**
 * History tab: the session list the desktop keeps in `RightPanel` → Sessions.
 *
 * Same store, same scoping rules, same live-state overlay (`useDisplaySessions`), so a session
 * that is running on the laptop shows as running here.
 */
export const MobileHistoryPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    remoteSessionHasMore,
    remoteSessionLoading,
    loadRemoteSessions,
    loadMoreRemoteSessions,
    deleteRemoteSession,
    availableTags,
    activeTagFilter,
    setTagFilter,
    loadSessionTags,
    setCurrentSessionId,
  } = useSessionStore();
  const { clearChat, sessionId: chatSessionId } = useChatStore();
  const { currentProject } = useProjectStore();
  const resetTeam = useTeamStore((s) => s.resetTeam);
  const displaySessions = useDisplaySessions();

  const [query, setQuery] = useState('');
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);

  // A temporary workspace belongs to one session only, so filtering by it would show exactly that
  // session; project-less mode instead lists the sessions that carry no project.
  const listProjectId = currentProject && currentProject.kind !== 'temp' ? currentProject.id : undefined;
  const tempWorkspaceOnly = listProjectId === undefined;

  useEffect(() => {
    void loadRemoteSessions(20, false, listProjectId, tempWorkspaceOnly);
  }, [loadRemoteSessions, listProjectId, activeTagFilter, tempWorkspaceOnly]);

  useEffect(() => {
    void loadSessionTags(listProjectId, tempWorkspaceOnly);
  }, [loadSessionTags, listProjectId]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return displaySessions;
    // Titles only: the list endpoint returns no message preview, so a body search would need a
    // per-session fetch. Deep search stays on the desktop console.
    return displaySessions.filter((s) => (s.title ?? '').toLowerCase().includes(q));
  }, [displaySessions, query]);

  const grouped = useMemo(() => groupSessionsByTime(filtered), [filtered]);

  const openSession = async (id: string) => {
    try {
      await switchToSession(id);
      navigate('/chat');
    } catch (e) {
      console.error('Failed to load session:', e);
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
      useNavStore.getState().setSelectedFile(null);
      setCurrentSessionId(null);
    }
  };

  const toggleTag = (tag: string) => {
    setTagFilter(activeTagFilter.includes(tag) ? activeTagFilter.filter((t) => t !== tag) : [...activeTagFilter, tag]);
  };

  return (
    <div className="flex flex-col flex-1 min-h-0">
      <MobileHeader
        title={i18n('Session History')}
        right={
          <button
            className="mobile-hit p-1.5 -mr-1.5"
            onClick={() => {
              clearChat();
              resetTeam();
              navigate('/chat');
            }}
            aria-label={i18n('New Session')}
          >
            <Plus className="w-5 h-5" />
          </button>
        }
      />

      <div className="px-3 py-2 border-b border-border flex items-center gap-2">
        <div className="relative flex-1 min-w-0">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground pointer-events-none" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={i18n('Search sessions...')}
            className="w-full h-11 pl-9 pr-8 rounded-lg border border-input bg-transparent text-base focus:outline-none focus:ring-2 focus:ring-ring"
          />
          {query && (
            <button className="absolute right-1 top-1/2 -translate-y-1/2 mobile-hit !min-w-0 p-1.5" onClick={() => setQuery('')} aria-label={i18n('Clear')}>
              <X className="w-4 h-4 text-muted-foreground" />
            </button>
          )}
        </div>
        <button
          className="mobile-hit p-1.5 text-muted-foreground"
          onClick={() => void loadRemoteSessions(20, false, listProjectId, tempWorkspaceOnly)}
          disabled={remoteSessionLoading}
          aria-label={i18n('Retry')}
        >
          <RefreshCw className={`w-5 h-5 ${remoteSessionLoading ? 'animate-spin' : ''}`} />
        </button>
      </div>

      {availableTags.length > 0 && (
        <div className="flex gap-1.5 overflow-x-auto px-3 py-2 border-b border-border">
          <button
            className="px-2.5 py-1 rounded-full text-xs border shrink-0 bg-muted border-border text-muted-foreground"
            onClick={() => setTagFilter([])}
          >
            {i18n('All')}
          </button>
          {availableTags.map((tag) => (
            <button
              key={tag}
              onClick={() => toggleTag(tag)}
              className={`px-2.5 py-1 rounded-full text-xs border shrink-0 max-w-[140px] truncate ${
                activeTagFilter.includes(tag) ? 'bg-primary/15 border-primary/30 text-primary' : 'bg-muted border-border text-muted-foreground'
              }`}
            >
              {tag}
            </button>
          ))}
        </div>
      )}

      <div
        className="flex-1 min-h-0 overflow-y-auto message-list-scroll"
        onScroll={(e) => {
          const el = e.currentTarget;
          if (!remoteSessionHasMore || remoteSessionLoading) return;
          if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) {
            void loadMoreRemoteSessions(20, listProjectId);
          }
        }}
      >
        {filtered.length === 0 ? (
          <p className="text-center py-10 text-sm text-muted-foreground">{i18n('No sessions yet')}</p>
        ) : (
          GROUP_LABELS.map(({ key, labelKey }) => {
            const rows = grouped[key] ?? [];
            if (rows.length === 0) return null;
            return (
              <div key={key}>
                <div className="mobile-section-title">{i18n(labelKey)}</div>
                {rows.map((session) => (
                  <MobileSwipeRow key={session.id} onDelete={() => setPendingDelete(session.id)}>
                    <SessionItem
                      session={session}
                      isSelected={chatSessionId === session.id}
                      showDelete={false}
                      onSelect={(id) => void openSession(id)}
                    />
                  </MobileSwipeRow>
                ))}
              </div>
            );
          })
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
