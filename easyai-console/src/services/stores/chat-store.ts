import { create } from 'zustand';
import type { SocketEvent, PermissionRequestEvent, GoalStatusEvent } from '@/types/socket-event';
import type { TodoInfo, SubAgentTodoGroup } from '@/types/todo';
import type { Message, ToolResult, ToolResultContentBlock, ContextReferences, QueuedMessage } from '@/types/message';
import type { CheckpointInfo, RevertStateInfo } from '@/types/checkpoint';
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
} from './chat/types';
import { handleChatEvent, setLastRespondedPermissionToolCallId } from './chat/event-handler';
import { commitStreamingMessageImpl, loadSessionMessagesImpl } from './chat/session-loader';
import { getGoal } from '@/services/goal-service';

// Re-export for backward compatibility (consumed by MessageEditor, InlineEditMessage, NodeMessageList, etc.)
export { convertSnapshot } from './chat/message-converter';

interface ChatState {
  sessionId: string | null;
  agentId: string;
  messages: Message[];
  isStreaming: boolean;
  /** Whether a file-modifying tool is currently executing (drives FileChangesPanel 'generating' state) */
  isFileWriting: boolean;
  streamingBlocks: StreamingBlock[];
  streamingToolOutputs: Record<string, string>;
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
  pendingPermission: PermissionRequestEvent | null;
  isCompacting: boolean;
  /** Checkpoint data keyed by messageId */
  checkpointsByMessageId: Record<string, CheckpointInfo>;
  /** Current revert state for the session */
  revertState: RevertStateInfo | null;
  /** Per-file review overrides (accepted/rejected) for file changes panel */
  fileReviewOverrides: Record<string, 'accepted' | 'rejected'>;
  /** Current goal status for the session (null if no active goal) */
  currentGoal: GoalStatusEvent | null;
  /** Messages queued for delivery to the agent (steering / followUp) */
  queuedMessages: QueuedMessage[];
  /** Pending message data keyed by messageId (usage stored on message_end, used in commitStreamingMessage) */
  pendingMessageData: Record<string, { usage?: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number; durationMs?: number }; references?: ContextReferences }>;
  /** ID of a session detected as still running on the backend */
  runningSessionId: string | null;
  /** Internal: raw MessageSnapshot[] from last full/incremental load, used for incremental merge. */
  _lastSnapshots: MessageSnapshot[];
  setRunningSessionId: (id: string | null) => void;

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
  commitToolResult: (id: string, result: string | undefined, isError: boolean, exitCode?: number | null, mimeType?: string | null, truncated?: boolean, usage?: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens?: number; cacheWriteTokens?: number }, durationMs?: number) => void;
  setUsage: (usage: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number }) => void;
  setCumulativeUsage: (usage: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number } | null) => void;
  setContextWindow: (size: number) => void;
  setHasArtifacts: (has: boolean) => void;
  setArtifactCount: (count: number) => void;
  setCancelReason: (reason: string | null) => void;
  setTodos: (todos: TodoInfo[]) => void;
  setSubAgentTodos: (agentName: string, todos: TodoInfo[], toolCallId?: string) => void;
  setAllSubAgentTodos: (map: Record<string, SubAgentTodoGroup>) => void;
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
  loadSessionMessages: (messages: MessageSnapshot[], pendingPermission?: PendingPermissionInfo | null, checkpoints?: CheckpointInfo[], endReason?: string | null) => void;
  /** Merge delta snapshots into _lastSnapshots and re-process. Used for incremental recovery after streaming. */
  loadSessionMessagesIncremental: (deltaSnapshots: MessageSnapshot[], pendingPermission?: PendingPermissionInfo | null, checkpoints?: CheckpointInfo[], endReason?: string | null) => void;
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
  pendingPermission: null,
  isCompacting: false,
  pendingMessageData: {},
  checkpointsByMessageId: {},
  revertState: null,
  fileReviewOverrides: {},
  currentGoal: null,
  queuedMessages: [],
  runningSessionId: null,
  _lastSnapshots: [],

  setSessionId: (id) => set({ sessionId: id }),
  setRunningSessionId: (id) => set({ runningSessionId: id, ...(id !== null ? { _lastSnapshots: [] } : {}) }),

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

  commitToolResult: (id, result, isError, exitCode?, mimeType?, truncated?, usage?, durationMs?) => set((state) => {
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
      fileReviewOverrides: {},
      revertState: null,
    };
  }),

  addCheckpoint: (messageId, checkpoint) => set((state) => ({
    checkpointsByMessageId: { ...state.checkpointsByMessageId, [messageId]: checkpoint },
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

  clearChat: () => set({
    sessionId: null,
    messages: [],
    isStreaming: false,
    isFileWriting: false,
    streamingBlocks: [],
    streamingToolOutputs: {},
    usage: null,
    cumulativeUsage: null,
    contextTokens: 0,
    isCompacting: false,
    cancelReason: null,
    agentId: 'default',
    todos: [],
    subAgentTodos: {},
    pendingPermission: null,
    pendingMessageData: {},
    checkpointsByMessageId: {},
    revertState: null,
    fileReviewOverrides: {},
    currentGoal: null,
    queuedMessages: [],
    runningSessionId: null,
    _lastSnapshots: [],
  }),

  commitStreamingMessage: () => set((state) => commitStreamingMessageImpl(state)),

  loadSessionMessages: (messages, pendingPermission, checkpoints, endReason) => {
    // Store raw snapshots for incremental merge support
    set({ _lastSnapshots: messages, currentGoal: null }); // Reset goal on full session load (session switch)
    // Safe cast: Zustand's set (Partial<ChatState>) is structurally compatible with
    // LoadSetFn (Partial<LoadSessionStateShape>) because ChatState extends LoadSessionStateShape.
    // The callback in loadSessionMessagesImpl currently ignores the state parameter.
    loadSessionMessagesImpl(messages, pendingPermission, checkpoints, set as Parameters<typeof loadSessionMessagesImpl>[3], endReason);
  },

  loadSessionMessagesIncremental: (deltaSnapshots, pendingPermission, checkpoints, endReason) => {
    const existingSnapshots = get()._lastSnapshots;
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
    set({ _lastSnapshots: mergedSnapshots });
    loadSessionMessagesImpl(mergedSnapshots, pendingPermission, checkpoints, set as Parameters<typeof loadSessionMessagesImpl>[3], endReason);
  },

  refreshGoal: async (sessionId) => {
    const goal = await getGoal(sessionId);
    set({ currentGoal: goal });
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
    handleChatEvent(event, get, set);
  },
}));
