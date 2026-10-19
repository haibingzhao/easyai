import { sessionService } from '@/services/session-service';
import { getCheckpoints, getFileReviewState } from '@/services/checkpoint-service';
import { getStreamingStatus } from '@/services/chat-service';
import { useChatStore } from '@/services/stores/chat-store';
import { useSessionStore } from '@/services/stores/session-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { useSettingsStore } from '@/services/stores/settings-store';
import { adoptSessionWorkspace } from '@/services/stores/project-store';
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
    setSnapshotEnabled, setTranscriptMode,
  } = chat;

  const streamingStatus = await getStreamingStatus(sessionId);

  const [detail, checkpoints] = await Promise.all([
    sessionService.getSessionDetail(sessionId),
    getCheckpoints(sessionId).catch(() => [] as CheckpointInfo[]),
  ]);
  loadSessionMessages(detail!.messages, detail!.pendingPermission, checkpoints, detail!.endReason, detail!.variables, detail!.modelContextLength, sessionId);
  setForkRootId(detail!.forkRootSessionId ?? sessionId);
  setTranscriptMode(false);
  // The session owns its workspace: adopt it so the file panel, mentions and memory UI point at
  // the directory the agent actually worked in, and drop a stale one when it has none yet.
  adoptSessionWorkspace(detail);
  // Applied after setSessionId below — the identity-change reset restores the optimistic default.
  const snapshotEnabled = detail!.snapshotEnabled !== false;
  useNavStore.getState().setSelectedFile(null);

  // Restore Agent and Model selectors from the last message's config
  const lastAgentId = detail!.lastAgentId;
  const knownAgents = useAgentStore.getState().agents;
  // A background task's transcript runs under an agent with no row of its own (`memory-consolidation`);
  // selecting it would point the composer at an agent the user can neither see nor pick. An empty list
  // means the agents have not loaded yet, so that case keeps the previous behaviour.
  if (
    lastAgentId &&
    (knownAgents.length === 0 || knownAgents.some((agent) => agent.id === lastAgentId))
  ) {
    useAgentStore.getState().selectAgent(lastAgentId);
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

/**
 * Load a session's messages in read-only mode for a background task transcript.
 *
 * Unlike {@link switchToSession}, this never marks the session as streaming (the task row
 * is the authority for background work, not the session DB status), skips agent/model
 * restoration (the task agent has no user-visible row), and skips workspace adoption
 * (the file panel is not shown). The chat store is left in a clean, idle state so the
 * MessageList renders a static transcript.
 */
export async function loadTranscriptSession(sessionId: string): Promise<void> {
  const chat = useChatStore.getState();
  const {
    loadSessionMessages, setSessionId, setTodos, setAllSubAgentTodos,
    setRunningSessionId, setStreaming, setForkRootId,
    setSnapshotEnabled, setTranscriptMode,
  } = chat;

  const [detail, checkpoints] = await Promise.all([
    sessionService.getSessionDetail(sessionId),
    getCheckpoints(sessionId).catch(() => [] as CheckpointInfo[]),
  ]);

  loadSessionMessages(detail!.messages, detail!.pendingPermission, checkpoints, detail!.endReason, detail!.variables, detail!.modelContextLength, sessionId);
  setForkRootId(detail!.forkRootSessionId ?? sessionId);

  const snapshotEnabled = detail!.snapshotEnabled !== false;
  useNavStore.getState().setSelectedFile(null);

  // Load todos for context (useful in the Summary panel) but skip review state.
  const groupedTodos = await sessionService.getGroupedTodos(sessionId);
  setTodos(groupedTodos.main);
  setAllSubAgentTodos(
    Object.fromEntries(groupedTodos.subAgents.map((g) => [g.agentName, { todos: g.todos, toolCallId: g.agentName }]))
  );

  // Force read-only state regardless of the session's DB streaming status, and mark the view as a
  // transcript so session-mutating actions (fork/branch) stay hidden.
  setRunningSessionId(null);
  setSessionId(sessionId);
  setSnapshotEnabled(snapshotEnabled);
  setStreaming(false);
  setTranscriptMode(true);
}
