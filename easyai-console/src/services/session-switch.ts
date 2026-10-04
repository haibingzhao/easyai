import { sessionService } from '@/services/session-service';
import { getCheckpoints, getFileReviewState } from '@/services/checkpoint-service';
import { getStreamingStatus } from '@/services/chat-service';
import { useChatStore } from '@/services/stores/chat-store';
import { useSessionStore } from '@/services/stores/session-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { useSettingsStore } from '@/services/stores/settings-store';
import { useNavStore } from '@/services/stores/nav-store';
import type { CheckpointInfo } from '@/types/checkpoint';

/**
 * Load a session's full state (messages, agent/model selection, todos, review state,
 * streaming recovery) and switch the chat view to it.
 * Shared by the History tab, the Summary fork-branch list, and post-fork navigation.
 */
export async function switchToSession(sessionId: string): Promise<void> {
  const chat = useChatStore.getState();
  const { setCurrentSessionId } = useSessionStore.getState();
  const {
    loadSessionMessages, setSessionId, setTodos, setAllSubAgentTodos,
    setFileReviewOverrides, setRunningSessionId, setStreaming, setForkRootId,
    setSnapshotEnabled,
  } = chat;

  const streamingStatus = await getStreamingStatus(sessionId);

  const [detail, checkpoints] = await Promise.all([
    sessionService.getSessionDetail(sessionId),
    getCheckpoints(sessionId).catch(() => [] as CheckpointInfo[]),
  ]);
  loadSessionMessages(detail!.messages, detail!.pendingPermission, checkpoints, detail!.endReason, detail!.variables, detail!.modelContextLength, sessionId);
  setForkRootId(detail!.forkRootSessionId ?? sessionId);
  // Applied after setSessionId below — the identity-change reset restores the optimistic default.
  const snapshotEnabled = detail!.snapshotEnabled !== false;
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
    snapshotEnabled ? getFileReviewState(sessionId).catch(() => null) : Promise.resolve(null),
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
    setSnapshotEnabled(snapshotEnabled);
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
  setSnapshotEnabled(snapshotEnabled);
}
