import { sessionService } from '@/services/session-service';
import { getCheckpoints, getFileReviewState } from '@/services/checkpoint-service';
import { getStreamingStatus } from '@/services/chat-service';
import { useChatStore } from '@/services/stores/chat-store';
import { useSessionStore } from '@/services/stores/session-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { useSettingsStore } from '@/services/stores/settings-store';
import { adoptSessionWorkspace } from '@/services/stores/project-store';
import { useNavStore } from '@/services/stores/nav-store';
import { bumpSessionEpoch, isCurrentEpoch } from '@/services/session-epoch';
import { APP_CONFIG } from '@/constants/navigation';

/**
 * Load a session's full state (messages, agent/model selection, todos, review state,
 * streaming recovery) and switch the chat view to it.
 * Shared by the History tab, the Summary fork-branch list, and post-fork navigation.
 *
 * Ordering matters:
 * 1. the detail response alone is enough to paint — it is applied the moment it arrives;
 * 2. checkpoints, todos and review state arrive afterwards and are applied individually;
 * 3. the run-state probe never blocks the paint, and a pending permission keeps its veto over
 *    `isStreaming` (see `applyRunState`).
 */
export async function switchToSession(sessionId: string): Promise<void> {
  // Reselecting the current session must also invalidate another switch still in flight.
  const epoch = bumpSessionEpoch();
  const selected = useChatStore.getState();
  if (selected.sessionId === sessionId && !selected.transcriptMode) return;
  const current = () => isCurrentEpoch(epoch);

  const chat = useChatStore.getState();
  const { setCurrentSessionId } = useSessionStore.getState();
  const {
    applySessionLoad, setTodos, setAllSubAgentTodos, setFileReviewOverrides,
    setRunningSessionId, setForkRootId, setSnapshotEnabled, applyRunState,
    setRunStateFailed, setCheckpointState, applyCheckpointResponse, setTranscriptMode,
  } = chat;

  // Review/Files are the only consumers of checkpoint and review state; skip those calls when the
  // host hides the panels. The checkpoint STATE stays `unknown` — not shown is not "no files".
  const workspacePanelsEnabled = APP_CONFIG.showWorkspacePanels;

  const detailPromise = sessionService.getSessionDetail(sessionId);
  const statusPromise = getStreamingStatus(sessionId);

  const detail = await detailPromise;
  if (!current() || !detail) return;
  setTranscriptMode(false);

  // Identity and messages land in one update: setting the id first would make the loader keep
  // the previous session's unsent optimistic messages (ownerSessionId === sessionId).
  applySessionLoad({
    sessionId,
    ownerSessionId: sessionId,
    messages: detail.messages,
    pendingPermission: detail.pendingPermission,
    endReason: detail.endReason,
    variables: detail.variables,
    modelContextLength: detail.modelContextLength,
    newestSeq: detail.newestMessageSeq,
    contentRevision: detail.contentRevision,
  });
  setForkRootId(detail.forkRootSessionId ?? sessionId);
  setCheckpointState('unknown');
  // The session owns its workspace: adopt it so the file panel, mentions and memory UI point at
  // the directory the agent actually worked in, and drop a stale one when it has none yet.
  adoptSessionWorkspace(detail);
  const snapshotEnabled = detail.snapshotEnabled !== false;
  useNavStore.getState().setSelectedFile(null);

  // Restore Agent and Model selectors from the last message's config
  const lastAgentId = detail.lastAgentId;
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
  if (detail.lastConfigId) {
    useSettingsStore.getState().setSelectedModelConfig(detail.lastConfigId);
  }

  // Secondary data: each arrives on its own and is applied on its own.
  void (async () => {
    const [checkpoints, groupedTodos, reviewState] = await Promise.all([
      workspacePanelsEnabled
        ? getCheckpoints(sessionId).catch(() => null)
        : Promise.resolve(null),
      sessionService.getGroupedTodos(sessionId).catch(() => null),
      snapshotEnabled && workspacePanelsEnabled
        ? getFileReviewState(sessionId).catch(() => null)
        : Promise.resolve(null),
    ]);
    if (!current()) return;

    if (checkpoints) {
      applyCheckpointResponse(checkpoints);
    } else if (workspacePanelsEnabled) {
      // The request failed outright (the `catch` above): that is `failed`, not "no checkpoints".
      useChatStore.getState().setCheckpointState('failed', 'Failed to load checkpoints');
    }
    if (groupedTodos) {
      setTodos(groupedTodos.main);
      setAllSubAgentTodos(
        Object.fromEntries(groupedTodos.subAgents.map((g) => [g.agentName, { todos: g.todos, toolCallId: g.agentName }]))
      );
    }
    if (reviewState?.reviews) {
      setFileReviewOverrides(reviewState.reviews);
    }
  })();

  void statusPromise
    .then((status) => {
      if (!current()) return;
      applyRunState(status, detail.pendingPermission ?? null);
      if (status.local || status.streaming) {
        setRunningSessionId(sessionId);
      } else {
        setRunningSessionId(null);
      }
      setSnapshotEnabled(snapshotEnabled);
      setCurrentSessionId(sessionId);
    })
    .catch(() => {
      if (!current()) return;
      // Unknown run state must be retryable, not a permanent lock on stop/retry/send.
      setRunStateFailed(true);
      setSnapshotEnabled(snapshotEnabled);
      setCurrentSessionId(sessionId);
    });
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
  const epoch = bumpSessionEpoch();
  const current = () => isCurrentEpoch(epoch);

  const chat = useChatStore.getState();
  const {
    applySessionLoad, setTodos, setAllSubAgentTodos,
    setRunningSessionId, setStreaming, setForkRootId,
    setSnapshotEnabled, setTranscriptMode, applyCheckpointResponse,
  } = chat;

  const [detail, checkpoints] = await Promise.all([
    sessionService.getSessionDetail(sessionId),
    APP_CONFIG.showWorkspacePanels ? getCheckpoints(sessionId).catch(() => null) : Promise.resolve(null),
  ]);
  if (!current()) return;
  setTranscriptMode(true);

  applySessionLoad({
    sessionId,
    ownerSessionId: sessionId,
    messages: detail.messages,
    pendingPermission: detail.pendingPermission,
    endReason: detail.endReason,
    variables: detail.variables,
    modelContextLength: detail.modelContextLength,
    newestSeq: detail.newestMessageSeq,
    contentRevision: detail.contentRevision,
  });
  if (checkpoints) applyCheckpointResponse(checkpoints);
  setForkRootId(detail.forkRootSessionId ?? sessionId);

  const snapshotEnabled = detail.snapshotEnabled !== false;
  useNavStore.getState().setSelectedFile(null);

  // Load todos for context (useful in the Summary panel) but skip review state.
  const groupedTodos = await sessionService.getGroupedTodos(sessionId);
  if (!current()) return;
  setTodos(groupedTodos.main);
  setAllSubAgentTodos(
    Object.fromEntries(groupedTodos.subAgents.map((g) => [g.agentName, { todos: g.todos, toolCallId: g.agentName }]))
  );

  // Force read-only state regardless of the session's DB streaming status, and mark the view as a
  // transcript so session-mutating actions (fork/branch) stay hidden.
  setRunningSessionId(null);
  setSnapshotEnabled(snapshotEnabled);
  setStreaming(false);
}
