import { create } from 'zustand';
import type { SocketEvent, PermissionRequestEvent, GoalStatusEvent, BackgroundTaskEvent, ToolFoldEvent } from '@/types/socket-event';
import type { TodoInfo, SubAgentTodoGroup } from '@/types/todo';
import type { TaskSummary } from '@/services/swarm-service';
import type { Message, ToolResult, ToolResultContentBlock, ContextReferences, QueuedMessage } from '@/types/message';
import type { CheckpointInfo, CheckpointListResponse, CheckpointState, RevertStateInfo } from '@/types/checkpoint';
import { bumpSessionEpoch } from '@/services/session-epoch';
import type {
  MessageSnapshot,
  PendingPermissionInfo,
} from '@/services/session-service';
import { TOOL_NAMES } from '@/constants/tools';
import {
  type StreamingBlock,
  type ThinkingBlockData,
  type TextBlockData,
  type StreamingToolCall,
  type ToolBlockData,
  type RetryInfo,
  type LivePreview,
} from './chat/types';
import { handleChatEvent, setLastRespondedPermissionToolCallId } from './chat/event-handler';
import { commitStreamingMessageImpl, loadSessionMessagesImpl, buildCheckpointMap } from './chat/session-loader';
import { getGoal } from '@/services/goal-service';
import { useTeamStore } from './team-store';

// Re-export for backward compatibility (consumed by MessageEditor, InlineEditMessage, NodeMessageList, etc.)
export { convertSnapshot } from './chat/message-converter';

/** Tracking data for a swarm run initiated from chat (keyed by toolCallId) */
export interface SwarmRunTracking {
  runId: string;
  presetName: string;
  title: string;
  status: string;
  tasks: TaskSummary[];
  totalInputTokens: number;
  totalOutputTokens: number;
}

interface ChatState {
  sessionId: string | null;
  agentId: string;
  messages: Message[];
  isStreaming: boolean;
  /** Whether a file-modifying tool is currently executing (drives FileChangesPanel 'generating' state) */
  isFileWriting: boolean;
  streamingBlocks: StreamingBlock[];
  streamingToolOutputs: Record<string, string>;
  /** Isolated live preview of the in-flight round from the watch tap during recovery; never committed to `messages`. */
  livePreview: LivePreview | null;
  usage: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number } | null;
  cumulativeUsage: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number } | null;
  /** Current context window token count (from last LLM response's inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens) */
  contextTokens: number;
  contextWindow: number;
  hasArtifacts: boolean;
  artifactCount: number;
  cancelReason: string | null;
  todos: TodoInfo[];
  subAgentTodos: Record<string, SubAgentTodoGroup>;
  /** Session-scoped variables (key -> value) tracked from update_variable tool calls / restored from backend */
  sessionVariables: Record<string, string>;
  swarmRuns: Record<string, SwarmRunTracking>;
  pendingPermission: PermissionRequestEvent | null;
  isCompacting: boolean;
  /** LLM timeout retry state (set on retry event, cleared when content resumes or stream ends) */
  retryInfo: RetryInfo | null;
  /** Checkpoint data keyed by messageId */
  checkpointsByMessageId: Record<string, CheckpointInfo>;
  /**
   * The same checkpoints in the order the server returned them (newest first).
   *
   * The map loses that order and [evaluateEditGate] needs it: an edit rolls back every turn from
   * the edited message to the newest one, so "is any entry in that range incomplete?" has to be
   * answered against a sequence, not a dictionary.
   */
  checkpointList: CheckpointInfo[];
  /**
   * Freshness of [checkpointsByMessageId]. `unknown` (not loaded) and `failed` are NOT the same
   * as `ok` with an empty list: the edit path must not read "no files to roll back" out of them.
   */
  checkpointState: CheckpointState;
  checkpointError: string | null;
  /** True once the run-state probe for the current session succeeded (or was not needed). */
  runStateResolved: boolean;
  /** True when the run-state probe failed — the UI offers a retry instead of locking forever. */
  runStateFailed: boolean;
  /** Current revert state for the session */
  revertState: RevertStateInfo | null;
  /** Per-file review overrides (accepted/rejected) for file changes panel */
  fileReviewOverrides: Record<string, 'accepted' | 'rejected'>;
  /** Current goal status for the session (null if no active goal) */
  currentGoal: GoalStatusEvent | null;
  /** Messages queued for delivery to the agent (steering / followUp) */
  queuedMessages: QueuedMessage[];
  /** Pending message data keyed by messageId (usage stored on message_end, used in commitStreamingMessage) */
  pendingMessageData: Record<string, { usage?: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number; durationMs?: number; modelName?: string }; references?: ContextReferences }>;
  /** Background task events keyed by taskId (for real-time status updates in tool cards) */
  backgroundTasks: Record<string, BackgroundTaskEvent>;
  /** Latest tool_fold event of the current session (null when no folding happened in this run yet) */
  toolFoldInfo: ToolFoldEvent | null;
  /** ID of a session detected as still running on the backend */
  runningSessionId: string | null;
  /** The live SSE stream dropped while the run continues under the recovery poll — renders where updates come from, not an error */
  liveStreamDropped: boolean;
  /** Internal: raw MessageSnapshot[] from last full/incremental load, used for incremental merge. */
  _lastSnapshots: MessageSnapshot[];
  _snapshotSessionId: string | null;
  /** Newest message seq the client holds — the incremental polling cursor. */
  messageCursor: number | null;
  /** History revision of the loaded transcript; a delta may only be stitched at the same value. */
  contentRevision: number | null;
  /** Root main session the currently viewed session belongs to (own id for a main session; null before a session exists) */
  forkRootId: string | null;
  /**
   * True while the chat view is showing a read-only background-task transcript (Tasks → open transcript).
   * Session-mutating actions such as fork/branch are meaningless on a hidden async-task session, so the
   * meta bar hides them while this is set. Reset by clearChat and by switchToSession.
   */
  transcriptMode: boolean;
  /** Whether the backend snapshot/checkpoint system is active for the current session (gates the Review panel). Defaults to true for older backends. */
  snapshotEnabled: boolean;
  /** App-wide snapshot capability from GET /api/chat/snapshot-capability (false for no-op backend overrides). Global — not reset on session switch. */
  snapshotCapability: boolean;
  setRunningSessionId: (id: string | null) => void;
  setForkRootId: (id: string | null) => void;
  setTranscriptMode: (on: boolean) => void;
  setSnapshotEnabled: (enabled: boolean) => void;
  setSnapshotCapability: (enabled: boolean) => void;

  setSessionId: (id: string | null) => void;
  setAgentId: (id: string) => void;
  addMessage: (message: Message) => void;
  /** Set messageId on the last user message that doesn't have one (called when user_message_ack arrives) */
  setLastUserMessageId: (messageId: string) => void;
  setMessages: (messages: Message[]) => void;
  setStreaming: (streaming: boolean) => void;
  setIsFileWriting: (v: boolean) => void;
  appendToTextBlock: (delta: string, messageId?: string) => void;
  appendToThinkingBlock: (delta: string, messageId?: string) => void;
  finishThinkingBlock: (durationMs?: number) => void;
  startToolBlock: (id: string, toolName: string, args?: Record<string, unknown>) => void;
  appendToolArgs: (id: string, delta: string) => void;
  appendToolOutput: (id: string, output: string) => void;
  getStreamingToolOutput: (id: string) => string | undefined;
  commitToolResult: (id: string, result: string | undefined, isError: boolean, exitCode?: number | null, mimeType?: string | null, truncated?: boolean, usage?: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens?: number; cacheWriteTokens?: number }, durationMs?: number, details?: Record<string, unknown>) => void;
  setUsage: (usage: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number }) => void;
  setCumulativeUsage: (usage: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number } | null) => void;
  setContextWindow: (size: number) => void;
  setHasArtifacts: (has: boolean) => void;
  setArtifactCount: (count: number) => void;
  setCancelReason: (reason: string | null) => void;
  setTodos: (todos: TodoInfo[]) => void;
  setSubAgentTodos: (agentName: string, todos: TodoInfo[], toolCallId?: string) => void;
  setAllSubAgentTodos: (map: Record<string, SubAgentTodoGroup>) => void;
  /** Replace the entire session variables map (used on session restore). */
  setSessionVariables: (vars: Record<string, string>) => void;
  /** Merge variable updates and apply deletions (used on update_variable tool events). */
  applyVariableUpdate: (variables: Record<string, string>, deleteKeys?: string[]) => void;
  updateSwarmRun: (toolCallId: string, data: SwarmRunTracking) => void;
  setPendingPermission: (permission: PermissionRequestEvent | null) => void;
  markPermissionResponded: (toolCallId: string | null) => void;
  setCompacting: (compacting: boolean) => void;
  setRevertState: (state: RevertStateInfo | null) => void;
  setFileReviewOverride: (path: string, status: 'accepted' | 'rejected') => void;
  setFileReviewOverrides: (entries: Record<string, 'accepted' | 'rejected'>) => void;
  removeFileReviewOverride: (path: string) => void;
  truncateMessagesFrom: (fromIndex: number) => void;
  addCheckpoint: (messageId: string, checkpoint: CheckpointInfo) => void;
  isAwaitingAskQuestion: () => boolean;
  isAwaitingPermission: () => boolean;
  clearChat: () => void;
  handleEvent: (event: SocketEvent) => void;
  commitStreamingMessage: () => void;
  /** Drop the watch-tap live preview (round committed, watch detached, or session changed). */
  clearLivePreview: () => void;
  loadSessionMessages: (messages: MessageSnapshot[], pendingPermission?: PendingPermissionInfo | null, checkpoints?: CheckpointInfo[], endReason?: string | null, variables?: Record<string, string> | null, modelContextLength?: number | null, ownerSessionId?: string) => void;
  /**
   * Replace session identity AND messages in one `set()`.
   *
   * Setting the id first and loading afterwards is a bug: the loader keeps "local messages that
   * have not been persisted yet" when `ownerSessionId === sessionId`, which is then true for the
   * *previous* session's optimistic messages, and they get appended to the new transcript.
   */
  applySessionLoad: (payload: {
    sessionId: string;
    messages: MessageSnapshot[];
    ownerSessionId: string;
    pendingPermission?: PendingPermissionInfo | null;
    checkpoints?: CheckpointInfo[];
    endReason?: string | null;
    variables?: Record<string, string> | null;
    modelContextLength?: number | null;
    newestSeq?: number | null;
    contentRevision?: number | null;
  }) => void;
  setCheckpointState: (state: CheckpointState, error?: string | null) => void;
  /** Apply a checkpoint listing, keeping `status` as the state (never "ok with []"). */
  applyCheckpointResponse: (response: CheckpointListResponse) => void;
  /**
   * Advance the transcript cursors after an incremental fetch or a reload.
   *
   * Separate from `loadSessionMessages*` because the cursor belongs to the *request* that
   * succeeded, not to the message list: a delta that carried no new rows still moves the server's
   * newest seq, and re-asking from the old cursor would re-read the same rows every poll.
   */
  applySessionCursors: (cursors: {
    newestSeq?: number | null;
    contentRevision?: number | null;
  }) => void;
  setRunStateResolved: (resolved: boolean) => void;
  setRunStateFailed: (failed: boolean) => void;
  /**
   * Apply a streaming-status probe. A pending permission wins: while the user is being asked to
   * approve, `isStreaming` must stay false or PermissionBar disables its own buttons.
   *
   * Accepts the snapshot shape as well as the SSE event: a session being *recovered* knows about
   * the pending request only through `GET /session/{id}`, and only its non-nullness matters here.
   */
  applyRunState: (status: { streaming?: boolean; local?: boolean }, pendingPermission: PermissionRequestEvent | PendingPermissionInfo | null) => void;
  /** Merge delta snapshots into _lastSnapshots and re-process. Used for incremental recovery after streaming. */
  loadSessionMessagesIncremental: (deltaSnapshots: MessageSnapshot[], pendingPermission?: PendingPermissionInfo | null, checkpoints?: CheckpointInfo[], endReason?: string | null, variables?: Record<string, string> | null, modelContextLength?: number | null, ownerSessionId?: string) => void;
  /** Refresh goal state from backend for the given session. Sets currentGoal to null if no goal exists. */
  refreshGoal: (sessionId: string) => Promise<void>;
  addQueuedMessage: (msg: QueuedMessage) => void;
  removeQueuedMessage: (id: string) => void;
  updateQueuedMessage: (id: string, content: string) => void;
  reorderQueuedMessages: (ids: string[]) => void;
  setQueuedMessages: (messages: QueuedMessage[]) => void;
}

export const useChatStore = create<ChatState>()((set, get) => ({
  sessionId: null,
  messages: [],
  isStreaming: false,
  isFileWriting: false,
  streamingBlocks: [],
  streamingToolOutputs: {},
  livePreview: null,
  usage: null,
  cumulativeUsage: null,
  contextTokens: 0,
  contextWindow: 200_000,
  hasArtifacts: false,
  artifactCount: 0,
  cancelReason: null,
  agentId: 'default',
  todos: [],
  subAgentTodos: {},
  sessionVariables: {},
  swarmRuns: {},
  pendingPermission: null,
  isCompacting: false,
  retryInfo: null,
  pendingMessageData: {},
  backgroundTasks: {},
  toolFoldInfo: null,
  checkpointsByMessageId: {},
  checkpointList: [],
  checkpointState: 'unknown',
  checkpointError: null,
  runStateResolved: false,
  runStateFailed: false,
  revertState: null,
  fileReviewOverrides: {},
  currentGoal: null,
  queuedMessages: [],
  runningSessionId: null,
  liveStreamDropped: false,
  _lastSnapshots: [],
  _snapshotSessionId: null,
  messageCursor: null,
  contentRevision: null,
  forkRootId: null,
  transcriptMode: false,
  snapshotEnabled: true,
  snapshotCapability: true,

  // When the session identity changes, clear streaming render state left over from the
  // old session (streamingBlocks / isStreaming / ...), otherwise the previous session's
  // in-flight tool cards would be rendered at the end of the new session's message list.
  // Same-id calls (e.g. re-selecting the current session) keep live streaming untouched.
  // snapshotEnabled resets to the optimistic default here; detail-loading paths
  // (switchToSession, NewSessionDialog) must apply the backend value AFTER setSessionId.
  setSessionId: (id) => {
    if (id === get().sessionId) return;
    bumpSessionEpoch();
    set({
      sessionId: id,
      _lastSnapshots: [],
      _snapshotSessionId: null,
      streamingBlocks: [],
      streamingToolOutputs: {},
      livePreview: null,
      isStreaming: false,
      isFileWriting: false,
      isCompacting: false,
      retryInfo: null,
      pendingMessageData: {},
      backgroundTasks: {},
      snapshotEnabled: true,
      checkpointState: 'unknown' as CheckpointState,
      checkpointError: null,
      checkpointsByMessageId: {},
      checkpointList: [],
      runStateResolved: false,
      runStateFailed: false,
      runningSessionId: null,
      todos: [],
      subAgentTodos: {},
      messageCursor: null,
      contentRevision: null,
    });
  },
  // Deliberately does NOT clear `_lastSnapshots`: the recovery poll continues from
  // `messageCursor` (initialised by the detail load), and wiping the snapshots here forced a
  // full `after=0` reload.
  setRunningSessionId: (id) => set({ runningSessionId: id, liveStreamDropped: false, ...(id === null ? { livePreview: null } : {}) }),
  setForkRootId: (id) => set({ forkRootId: id }),
  setTranscriptMode: (on) => set({ transcriptMode: on }),
  setSnapshotEnabled: (enabled) => set({ snapshotEnabled: enabled }),
  setSnapshotCapability: (enabled) => set({ snapshotCapability: enabled }),

  setAgentId: (id) => set({ agentId: id }),

  addMessage: (message) => set((state) => ({
    messages: [...state.messages, message]
  })),

  setLastUserMessageId: (messageId) => set((state) => {
    const messages = [...state.messages];
    // Find the last user message without a messageId (the optimistically-added one)
    for (let i = messages.length - 1; i >= 0; i--) {
      const msg = messages[i];
      if ((msg.role === 'user' || msg.role === 'user-with-attachments') && !msg.messageId) {
        messages[i] = { ...msg, messageId };
        break;
      }
    }
    return { messages };
  }),

  setMessages: (messages) => set({ messages }),

  setStreaming: (streaming) => set({ isStreaming: streaming }),

  setIsFileWriting: (v) => set({ isFileWriting: v }),

  appendToTextBlock: (delta, messageId) => set((state) => {
    const blocks = [...state.streamingBlocks];
    const lastBlock = blocks[blocks.length - 1];
    if (lastBlock && lastBlock.type === 'text' && (!messageId || (lastBlock as TextBlockData).messageId === messageId)) {
      blocks[blocks.length - 1] = { ...lastBlock, content: lastBlock.content + delta };
    } else {
      blocks.push({ type: 'text', content: delta, id: `text-${Date.now()}-${Math.random().toString(36).slice(2)}`, messageId });
    }
    return { streamingBlocks: blocks };
  }),

  appendToThinkingBlock: (delta, messageId) => set((state) => {
    const blocks = [...state.streamingBlocks];
    const lastBlock = blocks[blocks.length - 1];
    if (lastBlock && lastBlock.type === 'thinking' && !(lastBlock as ThinkingBlockData).isFinished && (!messageId || (lastBlock as ThinkingBlockData).messageId === messageId)) {
      blocks[blocks.length - 1] = { type: 'thinking', content: lastBlock.content + delta, id: lastBlock.id, messageId: (lastBlock as ThinkingBlockData).messageId || messageId };
    } else {
      blocks.push({ type: 'thinking', content: delta, id: `thinking-${Date.now()}`, messageId });
    }
    return { streamingBlocks: blocks };
  }),

  finishThinkingBlock: (durationMs?: number) => set((state) => {
    const blocks = [...state.streamingBlocks];
    for (let i = blocks.length - 1; i >= 0; i--) {
      const block = blocks[i];
      if (block.type === 'thinking' && !(block as ThinkingBlockData).isFinished) {
        blocks[i] = { ...block, isFinished: true, durationMs } as ThinkingBlockData;
        break;
      }
    }
    return { streamingBlocks: blocks };
  }),

  startToolBlock: (id, toolName, args) => set((state) => {
    const argsStr = args ? JSON.stringify(args, null, 2) : '';
    // Deduplication: if a tool block with the same ID already exists, update it instead of creating a duplicate
    const existingIndex = state.streamingBlocks.findIndex(b => b.type === 'tool' && b.toolCall.id === id);
    if (existingIndex !== -1) {
      const blocks = [...state.streamingBlocks];
      const existing = blocks[existingIndex] as ToolBlockData;
      blocks[existingIndex] = {
        ...existing,
        toolCall: { ...existing.toolCall, toolName: toolName || existing.toolCall.toolName, args: argsStr || existing.toolCall.args },
      };
      return { streamingBlocks: blocks };
    }
    const toolCall: StreamingToolCall = { id, toolName, args: argsStr, status: 'PENDING' };
    return {
      streamingBlocks: [...state.streamingBlocks, { type: 'tool', toolCall }],
    };
  }),

  appendToolArgs: (id, delta) => set((state) => {
    const blocks = [...state.streamingBlocks];
    const toolBlockIndex = blocks.findIndex(b => b.type === 'tool' && b.toolCall.id === id);
    if (toolBlockIndex !== -1) {
      const block = blocks[toolBlockIndex] as ToolBlockData;
      blocks[toolBlockIndex] = {
        ...block,
        toolCall: { ...block.toolCall, args: block.toolCall.args + delta },
      };
    }
    return { streamingBlocks: blocks };
  }),

  appendToolOutput: (id, output) => set((state) => {
    const currentOutput = state.streamingToolOutputs[id] || '';
    return {
      streamingToolOutputs: {
        ...state.streamingToolOutputs,
        [id]: currentOutput + output
      }
    };
  }),

  getStreamingToolOutput: (id) => {
    return get().streamingToolOutputs[id];
  },

  commitToolResult: (id, result, isError, exitCode?, mimeType?, truncated?, usage?, durationMs?, details?) => set((state) => {
    const rawResult = result ?? state.streamingToolOutputs[id] ?? '';
    // Try to parse JSON result into content blocks
    let contentBlocks: ToolResultContentBlock[] | undefined;
    try {
      const parsed = JSON.parse(rawResult);
      if (Array.isArray(parsed)) {
        contentBlocks = parsed as ToolResultContentBlock[];
      }
    } catch {
      // Not valid JSON, keep as plain text (backward compatibility)
    }
    // Look up toolName from streaming blocks (it's stored there with status)
    const toolBlock = state.streamingBlocks.find(b => b.type === 'tool' && b.toolCall.id === id) as ToolBlockData | undefined;
    const toolName = toolBlock?.toolCall.toolName ?? '';
    const toolResult: ToolResult = { 
      id, 
      toolName,
      result: rawResult, 
      contentBlocks, 
      details,
      isError, 
      exitCode, 
      mimeType, 
      truncated,
      usage,
      durationMs,
    };
    const blocks = [...state.streamingBlocks];
    const toolBlockIndex = blocks.findIndex(b => b.type === 'tool' && b.toolCall.id === id);
    if (toolBlockIndex !== -1) {
      const block = blocks[toolBlockIndex] as ToolBlockData;
      blocks[toolBlockIndex] = {
        ...block,
        toolResult,
        // Sync terminal status so renderers don't stay in streaming state
        toolCall: { ...block.toolCall, status: isError ? 'FAILED' : 'COMPLETED' },
      };
    }
    const remainingOutputs = { ...state.streamingToolOutputs };
    delete remainingOutputs[id];
    return {
      streamingBlocks: blocks,
      streamingToolOutputs: remainingOutputs,
    };
  }),

  setUsage: (usage) => set({ usage }),

  setCumulativeUsage: (usage) => set({ cumulativeUsage: usage }),

  setContextWindow: (size) => set({ contextWindow: size }),

  setHasArtifacts: (has) => set({ hasArtifacts: has }),

  setArtifactCount: (count) => set({ artifactCount: count }),

  setCancelReason: (reason) => set({ cancelReason: reason }),

  setTodos: (todos) => set({ todos }),

  setSubAgentTodos: (agentName, todos, toolCallId) => set((state) => ({
    subAgentTodos: { ...state.subAgentTodos, [agentName]: { todos, toolCallId } },
  })),

  setAllSubAgentTodos: (map) => set({ subAgentTodos: map }),

  setSessionVariables: (vars) => set({ sessionVariables: vars }),

  applyVariableUpdate: (variables, deleteKeys) => set((state) => {
    const next = { ...state.sessionVariables, ...variables };
    if (deleteKeys) {
      for (const key of deleteKeys) {
        delete next[key];
      }
    }
    return { sessionVariables: next };
  }),

  updateSwarmRun: (toolCallId, data) => set((state) => ({
    swarmRuns: { ...state.swarmRuns, [toolCallId]: data },
  })),

  setPendingPermission: (permission) => set({ pendingPermission: permission }),
  markPermissionResponded: (toolCallId) => {
    setLastRespondedPermissionToolCallId(toolCallId);
  },
  setCompacting: (compacting) => set({ isCompacting: compacting }),

  setRevertState: (state) => set({ revertState: state }),

  setFileReviewOverride: (path, status) => set((state) => ({
    fileReviewOverrides: { ...state.fileReviewOverrides, [path]: status },
  })),

  setFileReviewOverrides: (entries) => set((state) => ({
    fileReviewOverrides: { ...state.fileReviewOverrides, ...entries },
  })),

  removeFileReviewOverride: (path) => set((state) => {
    const { [path]: _, ...rest } = state.fileReviewOverrides;
    return { fileReviewOverrides: rest };
  }),

  truncateMessagesFrom: (fromIndex) => set((state) => {
    const removedMessages = state.messages.slice(fromIndex);
    const removedMessageIds = new Set(
      removedMessages
        .map(m => (m as { messageId?: string }).messageId)
        .filter((id): id is string => !!id)
    );
    // Delete checkpoints whose key OR messageId field matches any removed message.
    // Checkpoints are keyed by assistantMessageId, but their messageId field holds
    // the user message ID — both must be checked to avoid stale file-change data.
    const newCheckpoints = { ...state.checkpointsByMessageId };
    for (const [key, cp] of Object.entries(newCheckpoints)) {
      if (removedMessageIds.has(key) || (cp.messageId && removedMessageIds.has(cp.messageId))) {
        delete newCheckpoints[key];
      }
    }
    return {
      messages: state.messages.slice(0, fromIndex),
      checkpointsByMessageId: newCheckpoints,
      /**
       * The flattened listing has to lose the same rows.
       *
       * The map is looked up by anchor message id, so a removed message simply stops being found.
       * The listing is different: the edit gate scans it as a whole and refuses when *any* entry
       * is incomplete, so an entry left behind for a deleted message keeps blocking edits until
       * the next full listing arrives.
       */
      checkpointList: state.checkpointList.filter(
        (cp) =>
          !(cp.assistantMessageId && removedMessageIds.has(cp.assistantMessageId)) &&
          !(cp.messageId && removedMessageIds.has(cp.messageId)),
      ),
      fileReviewOverrides: {},
      revertState: null,
    };
  }),

  /**
   * Record a checkpoint that just arrived mid-stream.
   *
   * Writes both stores, because they are read by different code and neither can be derived from
   * the other: `checkpointsByMessageId` is a lookup by anchor, while `checkpointList` is the
   * ordered listing the edit gate slices (`slice(0, indexOf(anchor) + 1)` over a **newest-first**
   * array). Appending here would put the new entry last, i.e. outside every range the gate
   * computes, and the turn that just produced it would be missing from its own rollback scope —
   * the same asymmetry that made `truncateMessagesFrom` delete from both.
   */
  addCheckpoint: (messageId, checkpoint) => set((state) => ({
    checkpointsByMessageId: { ...state.checkpointsByMessageId, [messageId]: checkpoint },
    checkpointList: [
      checkpoint,
      ...state.checkpointList.filter((cp) =>
        checkpoint.assistantMessageId && cp.assistantMessageId
          ? cp.assistantMessageId !== checkpoint.assistantMessageId
          : cp.messageId !== checkpoint.messageId,
      ),
    ],
  })),

  /**
   * Whether there is a pending permission request waiting for user response.
   */
  isAwaitingPermission: (): boolean => {
    return get().pendingPermission !== null;
  },

  /**
   * Whether the last assistant message has ask_question tool calls
   * that are waiting for user answers (no tool results yet).
   */
  isAwaitingAskQuestion: (): boolean => {
    const state = get();
    const lastMsg = state.messages[state.messages.length - 1];
    if (!lastMsg || lastMsg.role !== 'assistant') return false;

    const assistantMsg = lastMsg as Extract<Message, { role: 'assistant' }>;
    if (!assistantMsg.toolCalls || assistantMsg.toolCalls.length === 0) return false;

    // Check if any ask_question tool call is missing a result
    const hasPendingAskQuestion = assistantMsg.toolCalls.some(
      (tc) =>
        tc.toolName === TOOL_NAMES.ASK_QUESTION &&
        !(assistantMsg.toolResults || []).some((tr) => tr.id === tc.id)
    );

    return hasPendingAskQuestion;
  },

  clearChat: () => {
    // Closing the transcript is an identity change like any other: a detail load or poll that is
    // still in flight would otherwise land on an empty view and repopulate it.
    bumpSessionEpoch();
    set({
    sessionId: null,
    messages: [],
    isStreaming: false,
    isFileWriting: false,
    streamingBlocks: [],
    streamingToolOutputs: {},
    livePreview: null,
    usage: null,
    cumulativeUsage: null,
    contextTokens: 0,
    isCompacting: false,
    retryInfo: null,
    cancelReason: null,
    agentId: 'default',
    todos: [],
    subAgentTodos: {},
    sessionVariables: {},
    swarmRuns: {},
    pendingPermission: null,
    pendingMessageData: {},
    backgroundTasks: {},
    toolFoldInfo: null,
    checkpointsByMessageId: {},
    checkpointList: [],
    checkpointState: 'unknown' as CheckpointState,
    checkpointError: null,
    runStateResolved: false,
    runStateFailed: false,
    messageCursor: null,
    contentRevision: null,
    revertState: null,
    fileReviewOverrides: {},
    currentGoal: null,
    queuedMessages: [],
    runningSessionId: null,
    liveStreamDropped: false,
    _lastSnapshots: [],
    _snapshotSessionId: null,
    forkRootId: null,
    transcriptMode: false,
    snapshotEnabled: true,
    });
  },

  commitStreamingMessage: () => set((state) => commitStreamingMessageImpl(state)),

  clearLivePreview: () => set((state) => (state.livePreview === null ? {} : { livePreview: null })),

  loadSessionMessages: (messages, pendingPermission, checkpoints, endReason, variables, modelContextLength, ownerSessionId) => {
    const sessionId = get().sessionId;
    if (ownerSessionId !== undefined && ownerSessionId !== sessionId) return;
    set({ _lastSnapshots: messages, _snapshotSessionId: sessionId, currentGoal: null, swarmRuns: {} });
    // Safe cast: Zustand's set (Partial<ChatState>) is structurally compatible with
    // LoadSetFn (Partial<LoadSessionStateShape>) because ChatState extends LoadSessionStateShape.
    // The callback in loadSessionMessagesImpl currently ignores the state parameter.
    // Use `variables ?? {}` (not `?? undefined`) so that switching to a session without
    // variables explicitly clears the previous session's variables (fixes #1).
    const localMessages = ownerSessionId !== undefined && ownerSessionId === get().sessionId ? get().messages : undefined;
    loadSessionMessagesImpl(messages, pendingPermission, checkpoints, set as Parameters<typeof loadSessionMessagesImpl>[3], endReason, variables ?? {}, modelContextLength, localMessages);
  },

  loadSessionMessagesIncremental: (deltaSnapshots, pendingPermission, checkpoints, endReason, variables, modelContextLength, ownerSessionId) => {
    const state = get();
    const sessionId = state.sessionId;
    if (ownerSessionId !== undefined && ownerSessionId !== sessionId) return;
    const existingSnapshots = state._snapshotSessionId === sessionId ? state._lastSnapshots : [];
    // Pre-compute merged snapshots for _lastSnapshots cache (so future incremental calls chain correctly)
    const snapshotMap = new Map<string, MessageSnapshot>();
    for (const snap of existingSnapshots) {
      const key = snap.id ?? `${snap.role}:${snap.timestamp}`;
      snapshotMap.set(key, snap);
    }
    for (const snap of deltaSnapshots) {
      const key = snap.id ?? `${snap.role}:${snap.timestamp}`;
      snapshotMap.set(key, snap);
    }
    const mergedSnapshots = [...snapshotMap.values()]
      .sort((a, b) => a.timestamp - b.timestamp);
    // Update cache and re-run full processing pipeline on merged set
    set({ _lastSnapshots: mergedSnapshots, _snapshotSessionId: sessionId });
    const localMessages = ownerSessionId !== undefined && ownerSessionId === get().sessionId ? get().messages : undefined;
    loadSessionMessagesImpl(mergedSnapshots, pendingPermission, checkpoints, set as Parameters<typeof loadSessionMessagesImpl>[3], endReason, variables ?? undefined, modelContextLength, localMessages);
  },

  refreshGoal: async (sessionId) => {
    const goal = await getGoal(sessionId);
    set({ currentGoal: goal });
  },

  applySessionLoad: ({ sessionId, messages, ownerSessionId, pendingPermission, checkpoints, endReason, variables, modelContextLength, newestSeq, contentRevision }) => {
    const state = get();
    if (sessionId === state.sessionId && !state.transcriptMode && (state.isStreaming || state.streamingBlocks.length > 0 || state.runningSessionId === sessionId)) return;
    const keepLocal = ownerSessionId === state.sessionId;
    const localMessages = keepLocal ? state.messages : undefined;
    set({
      sessionId,
      _lastSnapshots: messages,
      _snapshotSessionId: sessionId,
      currentGoal: null,
      swarmRuns: {},
      // Identity change clears render state left over from the previous session, exactly as
      // setSessionId does — here it happens in the same update as the message swap.
      streamingBlocks: [],
      streamingToolOutputs: {},
      livePreview: null,
      isStreaming: false,
      isFileWriting: false,
      isCompacting: false,
      retryInfo: null,
      pendingMessageData: {},
      backgroundTasks: {},
      snapshotEnabled: true,
      /**
       * Drop the previous session's run state.
       *
       * Without this the *previous* session's poller stays armed: `useSessionRecovery` keys its
       * effect on `runningSessionId` and guards writes with `streamingBlocks`, and an old session
       * id that was still "running" keeps writing deltas into the transcript now on screen. The
       * caller re-derives the run state from a status probe and sets it again if this one runs.
       */
      runningSessionId: null,
      // Todo lists are per session: carrying them over shows the new session's turns with the old
      // session's plan until the first poll answers.
      todos: [],
      subAgentTodos: {},
      // Checkpoints were NOT loaded for this payload: unknown, not ok.
      checkpointState: 'unknown' as CheckpointState,
      checkpointError: null,
      checkpointsByMessageId: {},
      checkpointList: [],
      runStateResolved: false,
      runStateFailed: false,
      messageCursor: newestSeq ?? null,
      contentRevision: contentRevision ?? null,
    });
    loadSessionMessagesImpl(
      messages,
      pendingPermission,
      checkpoints,
      set as Parameters<typeof loadSessionMessagesImpl>[3],
      endReason,
      variables ?? {},
      modelContextLength,
      localMessages,
    );
  },

  setCheckpointState: (state, error = null) => set({ checkpointState: state, checkpointError: error }),

  applyCheckpointResponse: (response) => {
    const checkpoints = response.status === 'ok' ? response.checkpoints : [];
    set({
      // `disabled` and `failed` stay distinguishable from a successful empty list.
      checkpointState: response.status as CheckpointState,
      checkpointError: response.error ?? null,
      checkpointsByMessageId: buildCheckpointMap(checkpoints, get().messages),
      checkpointList: checkpoints,
    });
  },

  applySessionCursors: ({ newestSeq, contentRevision }) => set((state) => ({
    // `?? state.x` keeps a field the caller omitted: a rewrite report carries no newest seq.
    messageCursor: newestSeq ?? state.messageCursor,
    contentRevision: contentRevision ?? state.contentRevision,
  })),

  setRunStateResolved: (resolved) => set({ runStateResolved: resolved, ...(resolved ? { runStateFailed: false } : {}) }),
  setRunStateFailed: (failed) => set({ runStateFailed: failed, ...(failed ? { runStateResolved: false } : {}) }),

  applyRunState: (status, pendingPermission) => {
    // Waiting for approval is not "streaming": PermissionBar disables its buttons while
    // isStreaming is true, so marking it here would make the approval unclickable.
    if (pendingPermission) {
      set({ isStreaming: false, runStateResolved: true, runStateFailed: false });
      return;
    }
    set({
      isStreaming: !!(status.streaming || status.local),
      runStateResolved: true,
      runStateFailed: false,
    });
  },

  addQueuedMessage: (msg) => set((state) => ({
    queuedMessages: [...state.queuedMessages, msg],
  })),

  removeQueuedMessage: (id) => set((state) => ({
    queuedMessages: state.queuedMessages.filter(m => m.id !== id),
  })),

  updateQueuedMessage: (id, content) => set((state) => ({
    queuedMessages: state.queuedMessages.map(m => m.id === id ? { ...m, content } : m),
  })),

  reorderQueuedMessages: (ids) => set((state) => {
    const msgMap = new Map(state.queuedMessages.map(m => [m.id, m]));
    const reordered = ids.map(id => msgMap.get(id)).filter((m): m is QueuedMessage => !!m);
    // Append any messages not in the ids list (safety net)
    for (const m of state.queuedMessages) {
      if (!ids.includes(m.id)) reordered.push(m);
    }
    return { queuedMessages: reordered };
  }),

  setQueuedMessages: (messages) => set({ queuedMessages: messages }),

  handleEvent: (event) => {
    // Recovery mode (re-entering a running session via switch/NewSessionDialog or
    // post-refresh takeover): the DB snapshot + polling is the single source of
    // truth. Live SSE events (orphaned send stream, watch tap) must not write the
    // store, or committed turns render twice (messages copy + streaming copy).
    const state = get();
    if (state.runningSessionId !== null && state.runningSessionId === state.sessionId) {
      return;
    }
    handleChatEvent(event, get, set);
    // Team coordination tools: refresh member executions immediately on SSE events
    const teamToolNames: string[] = [TOOL_NAMES.DELEGATE_TO_MEMBER, TOOL_NAMES.WAIT_FOR_MEMBER_EVENTS, TOOL_NAMES.RESUME_MEMBER];
    if (
      (event.type === 'tool_execution_start' || event.type === 'tool_execution_end') &&
      teamToolNames.includes((event as { toolName?: string }).toolName ?? '')
    ) {
      const sid = get().sessionId;
      if (sid) void useTeamStore.getState().refreshExecutions(sid);
    }
  },
}));
