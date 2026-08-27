import React, { useEffect } from 'react';
import { SessionItem } from '../ui/SessionItem';
import { useSessionStore } from '@/services/stores/session-store';
import { useChatStore } from '@/services/stores/chat-store';
import { useProjectStore } from '@/services/stores/project-store';
import { useNavStore } from '@/services/stores/nav-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { useSettingsStore } from '@/services/stores/settings-store';
import type { SessionListItem } from '@/services/session-service';
import { sessionService } from '@/services/session-service';
import { getCheckpoints, getFileReviewState } from '@/services/checkpoint-service';
import { getStreamingStatus } from '@/services/chat-service';
import type { CheckpointInfo } from '@/types/checkpoint';
import { groupSessionsByTime } from '@/utils/session-time';
import { i18n } from '@/utils/i18n';

/**
 * Sessions tab: shows historical session list inline in the right panel.
 * Replaces the SessionHistoryDialog modal.
 */
export const SessionsTab: React.FC = () => {
  const { remoteSessions, remoteSessionHasMore, remoteSessionLoading, setCurrentSessionId, loadRemoteSessions, loadMoreRemoteSessions, deleteRemoteSession } = useSessionStore();
  const { setSessionId, loadSessionMessages, clearChat, setTodos, setAllSubAgentTodos, setFileReviewOverrides, setRunningSessionId, setStreaming, sessionId: chatSessionId } = useChatStore();
  const { currentProject } = useProjectStore();

  // Load sessions on mount
  useEffect(() => {
    loadRemoteSessions(20, false, currentProject?.id);
  }, [currentProject?.id]);

  const handleSelectSession = async (sessionId: string) => {
    try {
      const streamingStatus = await getStreamingStatus(sessionId);

      const [detail, checkpoints] = await Promise.all([
        sessionService.getSessionDetail(sessionId),
        getCheckpoints(sessionId).catch(() => [] as CheckpointInfo[]),
      ]);
      loadSessionMessages(detail!.messages, detail!.pendingPermission, checkpoints, detail!.endReason, detail!.variables, detail!.modelContextLength);
      useNavStore.getState().setSelectedFile(null);

      // Restore Agent and Model selectors from the last message's config
      if (detail!.lastAgentId) {
        useAgentStore.getState().selectAgent(detail!.lastAgentId);
      }
      if (detail!.lastConfigId) {
        useSettingsStore.getState().setSelectedModelConfig(detail!.lastConfigId);
      }

      const [groupedTodos, reviewState] = await Promise.all([
        sessionService.getGroupedTodos(sessionId),
        getFileReviewState(sessionId).catch(() => null),
      ]);
      setTodos(groupedTodos.main);
      setAllSubAgentTodos(
        Object.fromEntries(groupedTodos.subAgents.map((g) => [g.agentName, { todos: g.todos, toolCallId: g.agentName }]))
      );
      if (reviewState?.reviews) {
        setFileReviewOverrides(reviewState.reviews);
      }

      if (streamingStatus.local || streamingStatus.streaming) {
        setSessionId(sessionId);
        setRunningSessionId(sessionId);
        if (!detail!.pendingPermission) {
          setStreaming(true);
        }
        setCurrentSessionId(sessionId);
        return;
      }

      // Session completed — stop any running polling and show this session
      setRunningSessionId(null);
      setCurrentSessionId(sessionId);
      setSessionId(sessionId);
    } catch (e) {
      console.error('Failed to load session:', e);
    }
  };

  const handleDeleteSession = async (sessionId: string) => {
    if (confirm(i18n('Are you sure?'))) {
      const isCurrentSession = chatSessionId === sessionId;
      await deleteRemoteSession(sessionId);
      if (isCurrentSession) {
        clearChat();
        useNavStore.getState().setSelectedFile(null);
        setCurrentSessionId(null);
      }
    }
  };

  const grouped = groupSessionsByTime(remoteSessions);

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
        {remoteSessions.length === 0 ? (
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
