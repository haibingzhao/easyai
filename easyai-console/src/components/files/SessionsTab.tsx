import React, { useEffect, useMemo } from 'react';
import { SessionItem } from '../ui/SessionItem';
import { useSessionStore } from '@/services/stores/session-store';
import { useChatStore } from '@/services/stores/chat-store';
import { useProjectStore } from '@/services/stores/project-store';
import { useNavStore } from '@/services/stores/nav-store';
import { switchToSession } from '@/services/session-switch';
import type { SessionListItem } from '@/services/session-service';
import { groupSessionsByTime } from '@/utils/session-time';
import { i18n } from '@/utils/i18n';

/**
 * Sessions tab: shows historical session list inline in the right panel.
 * Replaces the SessionHistoryDialog modal.
 */
export const SessionsTab: React.FC = () => {
  const { remoteSessions, remoteSessionHasMore, remoteSessionLoading, setCurrentSessionId, loadRemoteSessions, loadMoreRemoteSessions, deleteRemoteSession } = useSessionStore();
  const { clearChat, sessionId: chatSessionId, isStreaming, messages } = useChatStore();
  const { currentProject } = useProjectStore();

  // Load sessions on mount
  useEffect(() => {
    loadRemoteSessions(20, false, currentProject?.id);
  }, [currentProject?.id]);

  // Overlay the active chat session's live streaming state onto the list so the
  // "Running" indicator appears on send and clears on completion — without waiting
  // for a backend refetch. A brand-new session (not yet in remoteSessions) is
  // synthesized at the top from the first user message so it shows up immediately.
  const displaySessions = useMemo<SessionListItem[]>(() => {
    if (!chatSessionId) return remoteSessions;

    const exists = remoteSessions.some((s) => s.id === chatSessionId);
    let list = remoteSessions.map((s) =>
      s.id === chatSessionId ? { ...s, streaming: isStreaming } : s
    );

    if (!exists) {
      const firstUser = messages.find(
        (m) => m.role === 'user' || m.role === 'user-with-attachments'
      );
      const rawTitle =
        firstUser && 'content' in firstUser
          ? firstUser.content.replace(/\s+/g, ' ').trim().slice(0, 40)
          : '';
      const now = Date.now();
      list = [
        {
          id: chatSessionId,
          title: rawTitle || null,
          createdAt: now,
          updatedAt: now,
          messageCount: messages.length,
          streaming: isStreaming,
        },
        ...list,
      ];
    } else if (isStreaming) {
      // Pin the actively running session to the top so its state is immediately visible.
      const idx = list.findIndex((s) => s.id === chatSessionId);
      if (idx > 0) {
        const [active] = list.splice(idx, 1);
        list = [active, ...list];
      }
    }

    return list;
  }, [remoteSessions, chatSessionId, isStreaming, messages]);

  const handleSelectSession = async (sessionId: string) => {
    try {
      await switchToSession(sessionId);
    } catch (e) {
      console.error('Failed to load session:', e);
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
              showDelete={true}
              onSelect={handleSelectSession}
              onDelete={handleDeleteSession}
            />
          ))}
        </div>
      </div>
    );
  };

  return (
    <div className="h-full flex flex-col overflow-hidden">
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
            onClick={() => loadMoreRemoteSessions(20, currentProject?.id)}
            disabled={remoteSessionLoading}
          >
            {remoteSessionLoading ? i18n('Loading...') : i18n('Load More')}
          </button>
        </div>
      )}
    </div>
  );
};
