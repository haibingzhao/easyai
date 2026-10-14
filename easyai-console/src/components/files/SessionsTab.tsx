import React, { useEffect, useState } from 'react';
import { SessionItem } from '../ui/SessionItem';
import { useSessionStore } from '@/services/stores/session-store';
import { useChatStore } from '@/services/stores/chat-store';
import { useProjectStore } from '@/services/stores/project-store';
import { useNavStore } from '@/services/stores/nav-store';
import { useDisplaySessions } from '@/hooks/useDisplaySessions';
import { switchToSession } from '@/services/session-switch';
import type { SessionListItem } from '@/services/session-service';
import { groupSessionsByTime } from '@/utils/session-time';
import { i18n } from '@/utils/i18n';

/**
 * Sessions tab: shows historical session list inline in the right panel.
 * Replaces the SessionHistoryDialog modal.
 */
export const SessionsTab: React.FC = () => {
  const {
    remoteSessionHasMore, remoteSessionLoading,
    setCurrentSessionId, loadRemoteSessions, loadMoreRemoteSessions, deleteRemoteSession,
    availableTags, activeTagFilter, setTagFilter, loadSessionTags, updateRemoteSessionTags,
  } = useSessionStore();
  const { clearChat, sessionId: chatSessionId } = useChatStore();
  const { currentProject } = useProjectStore();
  const [switchingId, setSwitchingId] = useState<string | null>(null);

  // A temporary workspace belongs to one session only, so filtering the list by it would show
  // exactly that session. Project-less mode instead lists the project-less sessions: those
  // running in a temporary workspace, plus any that carry no project at all.
  const listProjectId = currentProject && currentProject.kind !== 'temp' ? currentProject.id : undefined;
  const tempWorkspaceOnly = listProjectId === undefined;
  const filterActive = activeTagFilter.length > 0;

  // Load sessions + available tags on mount / project change / filter change
  useEffect(() => {
    loadRemoteSessions(20, false, listProjectId, tempWorkspaceOnly);
  }, [listProjectId, activeTagFilter]);

  useEffect(() => {
    loadSessionTags(listProjectId, tempWorkspaceOnly);
  }, [listProjectId]);

  // Overlay the active chat session's live streaming state onto the list, and synthesize a
  // brand-new session row so it shows up immediately (shared with the mobile history page).
  const displaySessions = useDisplaySessions();

  const handleSelectSession = async (sessionId: string) => {
    if (switchingId) return;
    setSwitchingId(sessionId);
    try {
      await switchToSession(sessionId);
    } catch (e) {
      console.error('Failed to load session:', e);
    } finally {
      setSwitchingId(null);
    }
  };

  const handleDeleteSession = async (sessionId: string) => {
    if (confirm(i18n('Delete session and its branches?'))) {
      const isCurrentSession = chatSessionId === sessionId;
      await deleteRemoteSession(sessionId);
      if (isCurrentSession) {
        clearChat();
        useNavStore.getState().setSelectedFile(null);
        setCurrentSessionId(null);
      }
    }
  };

  const toggleTagFilter = (tag: string) => {
    const next = activeTagFilter.includes(tag)
      ? activeTagFilter.filter((t) => t !== tag)
      : [...activeTagFilter, tag];
    setTagFilter(next);
  };

  const grouped = groupSessionsByTime(displaySessions);

  const renderGroup = (title: string, sessions: SessionListItem[]) => {
    if (sessions.length === 0) return null;
    return (
      <div className="mb-3">
        <h3 className="text-xs font-medium text-muted-foreground mb-1.5 px-3">{i18n(title)}</h3>
        <div className="space-y-1 px-2">
          {sessions.map((session) => (
            <SessionItem
              key={session.id}
              session={session}
              isSelected={chatSessionId === session.id}
              loading={switchingId === session.id}
              showDelete={true}
              availableTags={availableTags}
              onSelect={handleSelectSession}
              onDelete={handleDeleteSession}
              onUpdateTags={updateRemoteSessionTags}
            />
          ))}
        </div>
      </div>
    );
  };

  return (
    <div className="h-full flex flex-col overflow-hidden">
      {/* Tag filter chip row */}
      {availableTags.length > 0 && (
        <div className="shrink-0 px-3 pb-2 flex flex-wrap gap-1.5 border-b border-border">
          <button
            type="button"
            onClick={() => setTagFilter([])}
            className={`px-2 py-0.5 rounded-full text-[11px] border transition-colors ${
              !filterActive
                ? 'bg-primary/15 border-primary/30 text-primary'
                : 'bg-muted border-border text-muted-foreground hover:text-foreground'
            }`}
          >
            {i18n('All')}
          </button>
          {availableTags.map((tag) => {
            const on = activeTagFilter.includes(tag);
            return (
              <button
                key={tag}
                type="button"
                title={tag}
                onClick={() => toggleTagFilter(tag)}
                className={`px-2 py-0.5 rounded-full text-[11px] border max-w-[160px] truncate transition-colors ${
                  on
                    ? 'bg-primary/15 border-primary/30 text-primary'
                    : 'bg-muted border-border text-muted-foreground hover:text-foreground'
                }`}
              >
                {tag}
              </button>
            );
          })}
        </div>
      )}

      {/* Session list */}
      <div className="flex-1 overflow-y-auto py-2">
        {displaySessions.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground text-sm">
            {i18n('No sessions yet')}
          </div>
        ) : (
          <>
            {renderGroup('Today', grouped.today)}
            {renderGroup('This Week', grouped.thisWeek)}
            {renderGroup('Older', grouped.older)}
          </>
        )}
      </div>

      {/* Load more */}
      {remoteSessionHasMore && (
        <div className="shrink-0 border-t border-border">
          <button
            className="w-full py-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
            onClick={() => loadMoreRemoteSessions(20, listProjectId, tempWorkspaceOnly)}
            disabled={remoteSessionLoading}
          >
            {remoteSessionLoading ? i18n('Loading...') : i18n('Load More')}
          </button>
        </div>
      )}
    </div>
  );
};
