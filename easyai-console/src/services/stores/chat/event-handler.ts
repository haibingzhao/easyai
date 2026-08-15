import type {
  SocketEvent,
  DoneEvent,
  TextEndEvent,
  ThinkingEndEvent,
  ToolExecutionEndEvent,
  MessageEndEvent,
  CheckpointEvent,
  RevertEvent,
  UserMessageAddedEvent,
  UserMessageAckEvent,
  GoalStatusEvent,
  CompactionEndEvent,
  RetryEvent,
} from '@/types/socket-event';
import type { Message, ToolResult, ToolResultContentBlock, ContextReferences, QueuedMessage } from '@/types/message';
import type { TodoInfo, SubAgentTodoGroup } from '@/types/todo';
import type { CheckpointInfo, RevertStateInfo } from '@/types/checkpoint';
import type { PermissionRequestEvent } from '@/types/socket-event';
import { TOOL_NAMES } from '@/constants/tools';
import {
  type StreamingBlock,
  type ThinkingBlockData,
  type TextBlockData,
  type ToolBlockData,
  type CompactionBlockData,
  type RetryInfo,
} from './types';
import { dispatchSubAgentEvent } from './sub-agent-handler';

/**
 * Minimal state shape required by the SSE event handler.
 * This interface captures all ChatState properties and methods accessed by handleEvent,
 * avoiding a circular dependency with chat-store.ts.
 */
export interface ChatStateShape {
  // State data
  sessionId: string | null;
  isStreaming: boolean;
  /** Whether a file-modifying tool is currently executing */
  isFileWriting: boolean;
  cancelReason: string | null;
  streamingBlocks: StreamingBlock[];
  streamingToolOutputs: Record<string, string>;
  messages: Message[];
  pendingPermission: PermissionRequestEvent | null;
  pendingMessageData: Record<string, { usage?: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number; durationMs?: number; modelName?: string }; references?: ContextReferences }>;
  cumulativeUsage: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number } | null;
  contextTokens: number;
  todos: TodoInfo[];
  subAgentTodos: Record<string, SubAgentTodoGroup>;
  sessionVariables: Record<string, string>;
  isCompacting: boolean;
  retryInfo: RetryInfo | null;
  checkpointsByMessageId: Record<string, CheckpointInfo>;
  revertState: RevertStateInfo | null;
  fileReviewOverrides: Record<string, 'accepted' | 'rejected'>;
  currentGoal: GoalStatusEvent | null;
  queuedMessages: QueuedMessage[];

  // Actions
  appendToTextBlock: (delta: string, messageId?: string) => void;
  appendToThinkingBlock: (delta: string, messageId?: string) => void;
  finishThinkingBlock: (durationMs?: number) => void;
  startToolBlock: (id: string, toolName: string, args?: Record<string, unknown>) => void;
  appendToolArgs: (id: string, delta: string) => void;
  appendToolOutput: (id: string, output: string) => void;
  commitToolResult: (id: string, result: string | undefined, isError: boolean, exitCode?: number | null, mimeType?: string | null, truncated?: boolean, usage?: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens?: number; cacheWriteTokens?: number }, durationMs?: number) => void;
  addMessage: (message: Message) => void;
  setLastUserMessageId: (messageId: string) => void;
  commitStreamingMessage: () => void;
  removeQueuedMessage: (id: string) => void;
  setIsFileWriting: (v: boolean) => void;
  refreshGoal: (sessionId: string) => Promise<void>;
  applyVariableUpdate: (variables: Record<string, string>, deleteKeys?: string[]) => void;
}

type SetFn = (partial: Partial<ChatStateShape> | ((state: ChatStateShape) => Partial<ChatStateShape>)) => void;

/**
 * Module-level flag: true when a permission_request event was received in the
 * current SSE stream. Reset to false on stream-end events (done/error/cancelled)
 * and on stream-start events (start).
 *
 * Used to prevent the 'done' handler from clearing pendingPermission when a
 * permission_request just arrived in the same stream — the agent loop paused for
 * permission, and the card must stay visible for the user to respond.
 */
let permissionRequestInStream = false;

/**
 * Module-level: toolCallId of the permission request that the user just
 * responded to (Allow Once / Always Allow / Deny). Reset on every new stream
 * start and on stream-end events.
 *
 * Used to ensure the 'done' handler clears pendingPermission even if the
 * original stream's done event arrives after the user clicked a response button.
 */
let lastRespondedPermissionToolCallId: string | null = null;

/**
 * @internal Exposed only for chat-store's markPermissionResponded action.
 * Do NOT call from other modules — mutating this variable directly would break
 * the permission cleanup logic in handleChatEvent's 'done' branch.
 */
export function setLastRespondedPermissionToolCallId(toolCallId: string | null) {
  lastRespondedPermissionToolCallId = toolCallId;
}

/**
 * Coerce a tool argument into a string-valued record.
 *
 * Some models serialize nested tool parameters as JSON strings rather than structured
 * objects (e.g. `variables` arrives as `"{\"k\":\"v\"}"` instead of `{"k":"v"}`). Spreading
 * a raw string would decompose it into per-character index keys, so we must coerce it back
 * to an object first. Non-object results yield an empty record.
 */
function coerceToStringRecord(value: unknown): Record<string, string> {
  let parsed: unknown = value;
  if (typeof value === 'string') {
    try {
      parsed = JSON.parse(value);
    } catch {
      return {};
    }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return {};
  }
  const result: Record<string, string> = {};
  for (const [key, val] of Object.entries(parsed as Record<string, unknown>)) {
    result[key] = typeof val === 'string' ? val : JSON.stringify(val);
  }
  return result;
}

/**
 * Coerce a tool argument into a string array.
 *
 * Handles both a genuine array and a JSON-string-encoded array. A plain non-JSON string
 * is treated as a single element; anything else yields an empty array.
 */
function coerceToStringArray(value: unknown): string[] {
  let parsed: unknown = value;
  if (typeof value === 'string') {
    try {
      parsed = JSON.parse(value);
    } catch {
      return value.length > 0 ? [value] : [];
    }
  }
  if (Array.isArray(parsed)) {
    return parsed.filter((item): item is string => typeof item === 'string');
  }
  return [];
}

/**
 * Handle a single SSE event by routing it to the appropriate state update.
 */
export function handleChatEvent(
  event: SocketEvent,
  get: () => ChatStateShape,
  set: SetFn
): void {
  const state = get();

  // Clear retry indicator as soon as new content arrives (retry succeeded).
  // Placed before sub-agent dispatch so sub-agent content deltas also clear it.
  if (state.retryInfo && ['text_delta', 'thinking_delta', 'toolcall_start', 'tool_execution_start'].includes(event.type)) {
    set({ retryInfo: null });
  }

  // Check if this is a sub-agent event (has subAgentToolCallId)
  const subAgentToolCallId = (event as unknown as Record<string, unknown>).subAgentToolCallId as string | undefined;
  const subAgentName = (event as unknown as Record<string, unknown>).subAgentName as string | undefined;
  if (subAgentToolCallId) {
    // Route to sub-agent block handling
    dispatchSubAgentEvent(subAgentToolCallId, subAgentName || 'unknown', event, get, set);
    // permission_request events also need to fall through to set pendingPermission
    if (event.type !== 'permission_request') {
      return;
    }
  }

  switch (event.type) {
    case 'start':
      // Reset permission tracking for a new stream
      permissionRequestInStream = false;
      lastRespondedPermissionToolCallId = null;
      if (event.sessionId) {
        set((s) => ({ sessionId: s.sessionId || event.sessionId }));
      }
      // Clear any stale cancelReason when a new run starts
      set({ cancelReason: null, retryInfo: null });
      break;
    case 'text_delta':
      state.appendToTextBlock(event.delta, event.messageId);
      break;
    case 'thinking_delta':
      state.appendToThinkingBlock(event.delta, event.messageId);
      break;
    case 'thinking_end':
      state.finishThinkingBlock((event as ThinkingEndEvent).durationMs);
      break;
    case 'text_end': {
      const textEndEvent = event as TextEndEvent;
      if (textEndEvent.durationMs) {
        // Store text duration in the last text block
        const blocks = [...get().streamingBlocks];
        for (let i = blocks.length - 1; i >= 0; i--) {
          if (blocks[i].type === 'text') {
            blocks[i] = { ...blocks[i], durationMs: textEndEvent.durationMs } as TextBlockData;
            break;
          }
        }
        set({ streamingBlocks: blocks });
      }
      break;
    }
    case 'toolcall_start':
      state.startToolBlock(event.id, event.toolName, undefined);
      break;
    case 'toolcall_delta':
      state.appendToolArgs(event.id, event.delta);
      break;
    case 'toolcall_end':
      break;
    case 'toolcall_status': {
      const statusBlocks = [...state.streamingBlocks];
      const statusToolBlockIndex = statusBlocks.findIndex(b => b.type === 'tool' && b.toolCall.id === event.toolCallId);
      if (statusToolBlockIndex !== -1) {
        const block = statusBlocks[statusToolBlockIndex] as ToolBlockData;
        statusBlocks[statusToolBlockIndex] = {
          ...block,
          toolCall: { ...block.toolCall, status: event.status },
        };
        set({ streamingBlocks: statusBlocks });
      } else {
        // Check if tool call is already committed in messages (e.g., after permission pause)
        const committedStatusMsgIdx = state.messages.findIndex(
          m => m.role === 'assistant' && (m as Extract<Message, { role: 'assistant' }>).toolCalls?.some(tc => tc.id === event.toolCallId)
        );
        if (committedStatusMsgIdx !== -1) {
          // Tool already committed — status is implicit from result/isStreaming, no update needed
          break;
        }
        // Tool block not found — create it and set status
        // This happens during resume/retry when streamingBlocks was cleared
        // but toolcall_status events are replayed from the backend
        state.startToolBlock(event.toolCallId, event.toolName, undefined);
        const newBlocks = [...state.streamingBlocks];
        const newToolBlockIndex = newBlocks.findIndex(b => b.type === 'tool' && b.toolCall.id === event.toolCallId);
        if (newToolBlockIndex !== -1) {
          const block = newBlocks[newToolBlockIndex] as ToolBlockData;
          newBlocks[newToolBlockIndex] = {
            ...block,
            toolCall: { ...block.toolCall, status: event.status },
          };
          set({ streamingBlocks: newBlocks });
        }
      }
      break;
    }
    case 'tool_execution_start': {
      // Track file-modifying tool execution for FileChangesPanel 'generating' state
      if (event.tracksFileChanges) set({ isFileWriting: true });
      // Detect todo_write tool and parse todos from args
      if (event.toolName === TOOL_NAMES.TODO_WRITE && event.args?.todos) {
        const rawTodos = event.args.todos as Array<{ content?: string; status?: string; priority?: string }>;
        if (Array.isArray(rawTodos)) {
          set({
            todos: rawTodos.map((t, i) => ({
              id: `todo-${Date.now()}-${i}`,
              content: t.content || '',
              status: (t.status || 'pending').toLowerCase() as TodoInfo['status'],
              priority: (t.priority || 'medium').toLowerCase() as TodoInfo['priority'],
              position: i,
              createdAt: Date.now(),
            })),
          });
        }
      }
      // Detect update_variable tool and apply variable set/delete operations from args
      if (event.toolName === TOOL_NAMES.UPDATE_VARIABLE) {
        const variables = coerceToStringRecord(event.args?.variables);
        const deleteKeys = coerceToStringArray(event.args?.deleteKeys);
        if (Object.keys(variables).length > 0 || deleteKeys.length > 0) {
          state.applyVariableUpdate(variables, deleteKeys);
        }
      }
      // Check if this tool call is already committed in messages (e.g., after permission pause).
      // If so, skip creating a new streaming block — the committed card will show RUNNING via isStreaming.
      const committedStartMsgIdx = state.messages.findIndex(
        m => m.role === 'assistant' && (m as Extract<Message, { role: 'assistant' }>).toolCalls?.some(tc => tc.id === event.toolCallId)
      );
      if (committedStartMsgIdx !== -1) {
        break;
      }
      if (event.args && Object.keys(event.args).length > 0) {
        const id = event.toolCallId;
        const blocks = [...state.streamingBlocks];
        const toolBlockIndex = blocks.findIndex(b => b.type === 'tool' && b.toolCall.id === id);
        if (toolBlockIndex !== -1) {
          const block = blocks[toolBlockIndex] as ToolBlockData;
          blocks[toolBlockIndex] = {
            ...block,
            toolCall: { ...block.toolCall, args: JSON.stringify(event.args, null, 2) },
          };
          set({ streamingBlocks: blocks });
        } else {
          state.startToolBlock(id, event.toolName, event.args);
        }
      }
      break;
    }
    case 'tool_execution_update':
      state.appendToolOutput(event.toolCallId, event.output);
      break;
    case 'tool_execution_end': {
      handleToolExecutionEnd(event, state, get, set);
      break;
    }
    case 'done': {
      state.commitStreamingMessage();
      // Clear pendingPermission when:
      //   - No permission_request arrived in this stream (the stream ended
      //     normally after handling a previously-responded permission), OR
      //   - The current pending permission is the one the user just responded
      //     to (handles the case where the original stream's done event
      //     arrives after the user clicked Allow/Deny).
      // If a fresh permission_request arrived in this stream, keep the card
      // visible so the user can respond to the new request.
      // cumulativeUsage is already correctly accumulated via message_end events
      const currentPendingPermission = get().pendingPermission;
      const shouldClearPendingPermission = !permissionRequestInStream ||
        (currentPendingPermission?.toolCallId === lastRespondedPermissionToolCallId);
      const doneEvent = event as DoneEvent;
      set({
        isStreaming: false,
        isFileWriting: false,
        retryInfo: null,
        pendingPermission: shouldClearPendingPermission ? null : get().pendingPermission,
        // Show a continuation banner when the agent stopped due to max iterations
        cancelReason: doneEvent.endReason === 'max_iterations' ? 'Max Iterations Reached' : get().cancelReason,
      });
      permissionRequestInStream = false;
      lastRespondedPermissionToolCallId = null;
      break;
    }
    case 'error': {
      handleStreamError(event, state, get, set);
      break;
    }
    case 'cancelled':
      permissionRequestInStream = false;
      lastRespondedPermissionToolCallId = null;
      state.commitStreamingMessage();
      set({
        isStreaming: false,
        isFileWriting: false,
        retryInfo: null,
        cancelReason: event.reason === 'user_cancelled' ? 'Manually Cancelled' : event.reason,
        pendingPermission: null,
        // Clear queued messages — they will not be consumed after cancellation
        queuedMessages: [],
      });
      break;
    case 'retry': {
      const retryEvent = event as RetryEvent;
      // Strip trailing text/thinking blocks from the failed attempt.
      // Invariant: retry only occurs during LLM streaming; tool events arrive
      // after the stream completes, so the current turn's partial content is
      // always the trailing consecutive text/thinking blocks. The backend
      // clears its accumulators and re-streams the full response with the same
      // messageId, so keeping partial blocks would cause duplicated content.
      const blocks = [...state.streamingBlocks];
      while (blocks.length > 0) {
        const last = blocks[blocks.length - 1];
        if (last.type === 'text' || last.type === 'thinking') blocks.pop();
        else break;
      }
      set({ retryInfo: { attempt: retryEvent.attempt, maxRetries: retryEvent.maxRetries }, streamingBlocks: blocks });
      break;
    }
    case 'compaction_start': {
      // During streaming, immediately append an in-progress compaction block so the
      // indicator card renders right away (with a live elapsed timer) instead of only
      // appearing once compaction_end arrives. Manual compaction (not streaming) relies
      // on the top-bar loading state and inserts the card on compaction_end.
      if (state.isStreaming) {
        const startedAt = Date.now();
        const inProgressBlock: CompactionBlockData = {
          type: 'compaction',
          isFinished: false,
          compactedCount: 0,
          tokensSaved: 0,
          timestamp: startedAt,
          id: `compaction-${startedAt}`,
        };
        set((s) => ({
          isCompacting: true,
          streamingBlocks: [...s.streamingBlocks, inProgressBlock],
        }));
      } else {
        set({ isCompacting: true });
      }
      break;
    }
    case 'compaction_end':
      handleCompactionEnd(event as CompactionEndEvent, state, set);
      break;
    case 'message_end': {
      handleMessageEnd(event as MessageEndEvent, get, set);
      break;
    }
    case 'permission_request':
      handlePermissionRequest(event, get, set);
      break;
    case 'checkpoint': {
      handleCheckpoint(event as CheckpointEvent, get, set);
      break;
    }
    case 'revert': {
      const revEvent = event as RevertEvent;
      set({ revertState: {
        messageId: revEvent.messageId,
        additions: revEvent.additions,
        deletions: revEvent.deletions,
        filesCount: revEvent.filesCount,
        timestamp: Date.now(),
      } });
      break;
    }
    case 'goal_status': {
      const goalEvent = event as GoalStatusEvent;
      set({ currentGoal: goalEvent });
      break;
    }
    case 'user_message_ack': {
      // Backend persisted the user message and assigned a messageId.
      // Update the optimistically-added user message so it becomes editable.
      const ackEvent = event as UserMessageAckEvent;
      state.setLastUserMessageId(ackEvent.messageId);
      break;
    }
    case 'user_message_added': {
      // Commit previous turn's streaming blocks before adding the auto-continue/steering user message.
      // This ensures the assistant response is committed as a message BEFORE the injected user message,
      // so the message order matches historical loading: [user, assistant, auto-continue, next-assistant].
      state.commitStreamingMessage();
      const umEvent = event as UserMessageAddedEvent;
      // Re-read state after commitStreamingMessage (which calls set())
      const currentState = get();
      const source = umEvent.metadata?.source;

      // For steer/followUp messages, find the matching queued message to inherit attachments.
      // UserMessageAddedEvent.content only contains text — image data must be inherited from the local queue.
      let matchedQueuedMsg: QueuedMessage | undefined;
      if (source === 'steering' || source === 'follow_up') {
        const queue = currentState.queuedMessages;
        const typeMatch = source === 'steering' ? 'steer' : 'followUp';
        matchedQueuedMsg = queue.find(m => m.content === umEvent.content && m.status === 'synced')
          ?? queue.find(m => m.content === umEvent.content)
          ?? queue.find(m => m.type === typeMatch);
      }

      const hasAttachments = matchedQueuedMsg?.attachments && matchedQueuedMsg.attachments.length > 0;
      currentState.addMessage({
        role: hasAttachments ? 'user-with-attachments' : 'user',
        content: umEvent.content,
        timestamp: Date.now(),
        metadata: umEvent.metadata,
        ...(hasAttachments ? { attachments: matchedQueuedMsg!.attachments } : {}),
      } as Message);

      // If this message was consumed from the queue (steering/follow_up), remove it from queuedMessages
      if (matchedQueuedMsg) {
        currentState.removeQueuedMessage(matchedQueuedMsg.id);
      }
      break;
    }
  }
}

/**
 * Handle tool_execution_end event, including the case where the tool call
 * is already committed in messages (e.g., after permission pause).
 */
function handleToolExecutionEnd(
  event: SocketEvent & { type: 'tool_execution_end' },
  state: ChatStateShape,
  get: () => ChatStateShape,
  set: SetFn
) {
  // Clear file-modifying indicator when the tool finishes
  if (event.tracksFileChanges) set({ isFileWriting: false });
  // Check if this tool call is already committed in messages (e.g., after permission pause).
  // If so, merge the result directly into the committed message instead of creating a new streaming block.
  const committedEndMsgIdx = state.messages.findIndex(
    m => m.role === 'assistant' && (m as Extract<Message, { role: 'assistant' }>).toolCalls?.some(tc => tc.id === event.toolCallId)
  );
  if (committedEndMsgIdx !== -1) {
    // Tool call is already committed — merge result into the existing message
    const committedMsg = state.messages[committedEndMsgIdx] as Extract<Message, { role: 'assistant' }>;
    const rawResult = event.result ?? state.streamingToolOutputs[event.toolCallId] ?? '';
    let contentBlocks: ToolResultContentBlock[] | undefined;
    try {
      const parsed = JSON.parse(rawResult);
      if (Array.isArray(parsed)) contentBlocks = parsed as ToolResultContentBlock[];
    } catch { /* Not valid JSON, keep as plain text */ }
    const toolCall = committedMsg.toolCalls?.find(tc => tc.id === event.toolCallId);
    const toolEndEvent = event as ToolExecutionEndEvent;
    const toolResult: ToolResult = {
      id: event.toolCallId,
      toolName: toolCall?.toolName ?? event.toolName,
      result: rawResult,
      contentBlocks,
      isError: event.isError,
      exitCode: event.exitCode,
      mimeType: event.mimeType,
      truncated: event.truncated,
      usage: toolEndEvent.toolUsage,
      durationMs: toolEndEvent.toolUsage?.durationMs,
    };
    const existingResults = committedMsg.toolResults || [];
    const updatedMessages = [...state.messages];
    updatedMessages[committedEndMsgIdx] = {
      ...committedMsg,
      toolResults: [...existingResults, toolResult],
    };
    const remainingOutputs = { ...state.streamingToolOutputs };
    delete remainingOutputs[event.toolCallId];
    set({ messages: updatedMessages, streamingToolOutputs: remainingOutputs });
    // Accumulate toolUsage to cumulativeUsage
    if (toolEndEvent.toolUsage) {
      const tu = toolEndEvent.toolUsage;
      set((s) => {
        const prev = s.cumulativeUsage;
        return {
          cumulativeUsage: {
            inputTokens: (prev?.inputTokens ?? 0) + tu.inputTokens,
            outputTokens: (prev?.outputTokens ?? 0) + tu.outputTokens,
            totalTokens: (prev?.totalTokens ?? 0) + tu.totalTokens,
            cacheReadTokens: (prev?.cacheReadTokens ?? 0) + (tu.cacheReadTokens ?? 0),
            cacheWriteTokens: (prev?.cacheWriteTokens ?? 0) + (tu.cacheWriteTokens ?? 0),
          },
        };
      });
    }
    return;
  }
  // Ensure tool block exists before committing result
  const endBlocks = [...state.streamingBlocks];
  const endToolBlockIndex = endBlocks.findIndex(b => b.type === 'tool' && b.toolCall.id === event.toolCallId);
  if (endToolBlockIndex === -1) {
    // Tool block doesn't exist, create it first
    state.startToolBlock(event.toolCallId, event.toolName, undefined);
  }
  // Extract toolUsage before commitToolResult so we can pass it
  const toolEndEvent = event as ToolExecutionEndEvent;
  const toolUsage = toolEndEvent.toolUsage;
  state.commitToolResult(event.toolCallId, event.result, event.isError, event.exitCode, event.mimeType, event.truncated, toolUsage, toolUsage?.durationMs);
  // Handle sub-agent completion: mark as finished
  const afterCommitBlocks = [...get().streamingBlocks];
  const toolIdx = afterCommitBlocks.findIndex(b => b.type === 'tool' && b.toolCall.id === event.toolCallId);
  if (toolIdx !== -1) {
    const tb = { ...afterCommitBlocks[toolIdx] } as ToolBlockData;
    if (tb.subAgent) {
      tb.subAgent = { ...tb.subAgent, isFinished: true };
      afterCommitBlocks[toolIdx] = tb;
      set({ streamingBlocks: afterCommitBlocks });
    }
  }
  // Accumulate toolUsage to cumulativeUsage
  if (toolUsage) {
    const tu = toolUsage;
    set((s) => {
      const prev = s.cumulativeUsage;
      return {
        cumulativeUsage: {
          inputTokens: (prev?.inputTokens ?? 0) + tu.inputTokens,
          outputTokens: (prev?.outputTokens ?? 0) + tu.outputTokens,
          totalTokens: (prev?.totalTokens ?? 0) + tu.totalTokens,
          cacheReadTokens: (prev?.cacheReadTokens ?? 0) + (tu.cacheReadTokens ?? 0),
          cacheWriteTokens: (prev?.cacheWriteTokens ?? 0) + (tu.cacheWriteTokens ?? 0),
        },
      };
    });
    // Update SubAgentBlockData.accumulatedUsage for SubAgent header + TimelineBar
    const updatedBlocks = [...get().streamingBlocks];
    const saToolIdx = updatedBlocks.findIndex(b => b.type === 'tool' && b.toolCall.id === event.toolCallId);
    if (saToolIdx !== -1) {
      const saBlock = { ...updatedBlocks[saToolIdx] } as ToolBlockData;
      if (saBlock.subAgent) {
        saBlock.subAgent = { ...saBlock.subAgent, accumulatedUsage: { inputTokens: tu.inputTokens, outputTokens: tu.outputTokens, cacheReadTokens: tu.cacheReadTokens ?? 0 } };
        updatedBlocks[saToolIdx] = saBlock;
        set({ streamingBlocks: updatedBlocks });
      }
    }
  }

  // Defensive: refresh goal state from backend when goal tool completes.
  // Ensures Summary panel stays in sync even if the goal_status SSE event is missed.
  if (event.toolName === TOOL_NAMES.GOAL && state.sessionId) {
    state.refreshGoal(state.sessionId).catch(() => { /* best-effort */ });
  }
}

/**
 * Handle error and connection loss events.
 */
function handleStreamError(
  event: SocketEvent & { type: 'error' },
  state: ChatStateShape,
  _get: () => ChatStateShape,
  set: SetFn
) {
  // Reset permission tracking
  permissionRequestInStream = false;
  lastRespondedPermissionToolCallId = null;
  // Commit any partial streaming content before clearing state
  state.commitStreamingMessage();
  const isConnectionLost = event.reason === 'connection_lost';
  set({
    isStreaming: false,
    isFileWriting: false,
    retryInfo: null,
    pendingPermission: isConnectionLost ? state.pendingPermission : null,
    cancelReason: isConnectionLost ? 'Connection Lost' : null,
    // Backend abort clears queues on any terminal error; frontend must match.
    // For connection_lost the backend SSE-drop handler also aborts, clearing queues.
    queuedMessages: [],
  });
  state.addMessage({
    role: 'error',
    content: event.errorMessage || 'Unknown error',
    timestamp: Date.now(),
    messageId: event.messageId,
  });
}

/**
 * Handle compaction_end event — insert compaction indicator card, update contextTokens and accumulate compaction LLM usage.
 */
function handleCompactionEnd(
  event: CompactionEndEvent,
  state: ChatStateShape,
  set: SetFn
) {
  const now = Date.now();

  if (state.isStreaming) {
    // During streaming, an in-progress compaction block was already appended to
    // streamingBlocks on compaction_start (so the card renders immediately with a live
    // timer). Finalize it in place — preserving its position right after the compacted
    // turns — filling in the final stats. commitStreamingMessage converts it into a
    // custom message when the stream ends.
    set((s) => {
      const blocks = [...s.streamingBlocks];
      for (let i = blocks.length - 1; i >= 0; i--) {
        const block = blocks[i];
        if (block.type === 'compaction' && !block.isFinished) {
          blocks[i] = {
            ...block,
            isFinished: true,
            compactedCount: event.compactedCount,
            tokensSaved: event.tokensSaved,
            durationMs: event.durationMs ?? 0,
            currentTokens: event.currentTokens,
          };
          return { isCompacting: false, streamingBlocks: blocks };
        }
      }
      // Fallback: compaction_start was not observed (e.g. dropped event) — append a
      // finished block so the indicator still appears.
      const compactionBlock: CompactionBlockData = {
        type: 'compaction',
        isFinished: true,
        compactedCount: event.compactedCount,
        tokensSaved: event.tokensSaved,
        durationMs: event.durationMs ?? 0,
        currentTokens: event.currentTokens,
        timestamp: now,
        id: `compaction-${now}`,
      };
      return { isCompacting: false, streamingBlocks: [...s.streamingBlocks, compactionBlock] };
    });
  } else {
    // Manual compaction (not streaming): there are no streaming blocks to interleave
    // with, so insert the indicator card directly into the message list.
    set({ isCompacting: false });
    state.addMessage({
      role: 'custom',
      customType: 'compaction',
      metadata: {
        compactedCount: event.compactedCount,
        tokensSaved: event.tokensSaved,
        compactedAt: now,
        durationMs: event.durationMs ?? 0,
        currentTokens: event.currentTokens,
        isCompactionIndicator: true,
      },
      timestamp: now,
    });
  }

  // Update contextTokens to reflect post-compaction context size.
  // cumulativeUsage is NOT reset — it tracks total LLM token consumption across the session,
  // regardless of compaction. Only the compaction LLM call's own token cost is accumulated.
  if (event.currentTokens > 0) {
    const compactionUsage = event.usage;
    set((s) => {
      const prev = s.cumulativeUsage;
      return {
        contextTokens: event.currentTokens,
        cumulativeUsage: {
          inputTokens: (prev?.inputTokens ?? 0) + (compactionUsage?.inputTokens ?? 0),
          outputTokens: (prev?.outputTokens ?? 0) + (compactionUsage?.outputTokens ?? 0),
          totalTokens: (prev?.totalTokens ?? 0) + (compactionUsage?.inputTokens ?? 0) + (compactionUsage?.outputTokens ?? 0),
          cacheReadTokens: (prev?.cacheReadTokens ?? 0) + (compactionUsage?.cacheReadTokens ?? 0),
          cacheWriteTokens: (prev?.cacheWriteTokens ?? 0) + (compactionUsage?.cacheWriteTokens ?? 0),
        },
      };
    });
  }

  // Apply session variables extracted during compaction in real-time.
  // The compaction agent's update_variable tool call is not visible in the main SSE
  // stream, so variables are delivered via the compaction_end event instead.
  if (event.variables && Object.keys(event.variables).length > 0) {
    state.applyVariableUpdate(event.variables);
  }
}

/**
 * Handle message_end event — store usage data and update block token counts.
 */
function handleMessageEnd(
  event: MessageEndEvent,
  get: () => ChatStateShape,
  set: SetFn
) {
  if (event.messageId) {
    const u = event.usage;
    const refs = event.references;
    set((s) => ({
      pendingMessageData: {
        ...s.pendingMessageData,
        [event.messageId]: {
          ...s.pendingMessageData[event.messageId],
          ...(u ? {
            usage: {
              inputTokens: u.inputTokens,
              outputTokens: u.outputTokens,
              totalTokens: u.totalTokens,
              cacheReadTokens: u.cacheReadTokens ?? 0,
              cacheWriteTokens: u.cacheWriteTokens ?? 0,
              durationMs: u.durationMs,
              modelName: event.modelName,
            },
          } : {}),
          ...(refs ? { references: refs } : {}),
        },
      },
    }));
    // Also update cumulativeUsage in real-time so TokenContextBar reflects token consumption during streaming
    if (event.usage) {
      const u = event.usage;
      set((s) => {
        const prev = s.cumulativeUsage;
        return {
          cumulativeUsage: {
            inputTokens: (prev?.inputTokens ?? 0) + u.inputTokens,
            outputTokens: (prev?.outputTokens ?? 0) + u.outputTokens,
            totalTokens: (prev?.totalTokens ?? 0) + u.totalTokens,
            cacheReadTokens: (prev?.cacheReadTokens ?? 0) + (u.cacheReadTokens ?? 0),
            cacheWriteTokens: (prev?.cacheWriteTokens ?? 0) + (u.cacheWriteTokens ?? 0),
          },
          // inputTokens is cumulative (full context), so override rather than accumulate.
          // Take max to guard against gateway under-reporting (e.g. after prompt-cache
          // invalidation) which would make the token bar visually drop then jump back.
          // compaction_end legitimately overrides with a lower post-compaction value.
          contextTokens: Math.max(s.contextTokens, u.inputTokens + (u.cacheReadTokens ?? 0) + (u.cacheWriteTokens ?? 0) + u.outputTokens),
        };
      });

      // Update streaming blocks with token counts from message_end usage
      // This enables TimelineBar to show proper token-based heights instead of char-based heights
      const msgEndBlocks = [...get().streamingBlocks];
      const mid = event.messageId;
      const outputTokens = u.outputTokens;
      const hasThinking = msgEndBlocks.some(b => b.type === 'thinking' && (b as ThinkingBlockData).messageId === mid);
      const hasText = msgEndBlocks.some(b => b.type === 'text' && (b as TextBlockData).messageId === mid);

      if (outputTokens > 0 && (hasThinking || hasText)) {
        let updated = false;
        for (let i = 0; i < msgEndBlocks.length; i++) {
          const block = msgEndBlocks[i];
          if (!hasText && block.type === 'thinking' && (block as ThinkingBlockData).messageId === mid) {
            // Only thinking, no text → thinking gets all outputTokens
            msgEndBlocks[i] = { ...block, tokenCount: outputTokens } as ThinkingBlockData;
            updated = true;
          } else if (!hasThinking && block.type === 'text' && (block as TextBlockData).messageId === mid) {
            // Only text, no thinking → text gets all outputTokens
            msgEndBlocks[i] = { ...block, tokenCount: outputTokens } as TextBlockData;
            updated = true;
          } else if (hasThinking && hasText && block.type === 'text' && (block as TextBlockData).messageId === mid) {
            // Both thinking and text → thinking keeps chars, text gets outputTokens
            msgEndBlocks[i] = { ...block, tokenCount: outputTokens } as TextBlockData;
            updated = true;
          }
        }
        if (updated) {
          set({ streamingBlocks: msgEndBlocks });
        }
      }
    }
  }
}

/**
 * Handle permission_request event — store pending permission and pause streaming.
 * Also populate the tool block's args from the permission event arguments,
 * because the MessageEndEvent's ToolCallStart/End doesn't include args,
 * so the tool card would otherwise show empty parameters.
 */
function handlePermissionRequest(
  event: SocketEvent & { type: 'permission_request' },
  get: () => ChatStateShape,
  set: SetFn
) {
  // Mark that a permission_request arrived in this stream so 'done'
  // doesn't immediately clear it (the agent loop paused for user input).
  permissionRequestInStream = true;

  // Populate tool block args from permission event arguments.
  // The MessageEndEvent only emits ToolCallStart + ToolCallEnd (without args),
  // so we need to backfill args here for the tool card to display properly.
  if (event.arguments && Object.keys(event.arguments).length > 0) {
    const argsStr = JSON.stringify(event.arguments, null, 2);
    const currentState = get();

    // 1. Try updating streamingBlocks (tool block not yet committed)
    const blocks = [...currentState.streamingBlocks];
    const toolBlockIndex = blocks.findIndex(b => b.type === 'tool' && b.toolCall.id === event.toolCallId);
    if (toolBlockIndex !== -1) {
      const block = blocks[toolBlockIndex] as ToolBlockData;
      if (!block.toolCall.args) {
        blocks[toolBlockIndex] = {
          ...block,
          toolCall: { ...block.toolCall, args: argsStr },
        };
        set({ streamingBlocks: blocks });
      }
    }

    // 2. Try updating committed messages (tool block already committed via message_end)
    const messages = [...get().messages];
    const msgIdx = messages.findIndex(
      m => m.role === 'assistant' && (m as Extract<Message, { role: 'assistant' }>).toolCalls?.some(tc => tc.id === event.toolCallId)
    );
    if (msgIdx !== -1) {
      const msg = messages[msgIdx] as Extract<Message, { role: 'assistant' }>;
      const updatedToolCalls = (msg.toolCalls || []).map(tc => {
        if (tc.id === event.toolCallId && !tc.args) {
          return { ...tc, args: argsStr };
        }
        return tc;
      });
      messages[msgIdx] = { ...msg, toolCalls: updatedToolCalls };
      set({ messages });
    }
  }

  // Store the pending permission request for UI to display
  // Set isStreaming=false so PermissionBar buttons are enabled
  const permEvent = event as PermissionRequestEvent;
  set({
    isStreaming: false,
    pendingPermission: {
      type: 'permission_request',
      toolCallId: event.toolCallId,
      toolName: event.toolName,
      permission: event.permission,
      pattern: event.pattern,
      arguments: event.arguments,
      subAgentToolCallId: permEvent.subAgentToolCallId,
      subAgentName: permEvent.subAgentName,
    },
  });
}

/**
 * Handle checkpoint event — store checkpoint data and invalidate stale file review overrides.
 */
function handleCheckpoint(
  event: CheckpointEvent,
  _get: () => ChatStateShape,
  set: SetFn
) {
  const checkpoint: CheckpointInfo = {
    messageId: event.messageId,
    assistantMessageId: event.assistantMessageId,
    snapshotHash: event.snapshotHash,
    filesChanged: event.filesChanged || [],
    additions: event.additions || 0,
    deletions: event.deletions || 0,
    createdAt: Date.now(),
  };
  // Key by assistantMessageId so AssistantMessage can look up its checkpoint
  const key = event.assistantMessageId || event.messageId;
  if (!key) return;

  // Collect file paths changed in this checkpoint for review invalidation.
  // When a file is re-modified by the LLM after being accepted/rejected,
  // its stale review override must be cleared so UI resets to "applied".
  const changedPaths = new Set(
    (event.filesChanged || []).map(fc => fc.path)
  );

  set((s) => {
    // Remove stale review overrides for re-modified files
    const updatedOverrides = { ...s.fileReviewOverrides };
    let hasOverrideChanges = false;
    for (const path of changedPaths) {
      if (path in updatedOverrides) {
        delete updatedOverrides[path];
        hasOverrideChanges = true;
      }
    }

    // Defensive merge: if the new checkpoint has no file changes, preserve the
    // existing checkpoint data for this key. This prevents a later empty checkpoint
    // (e.g., from handleAgentEnd with tree-dedup) from overwriting valid data
    // previously sent by afterToolExecutionBatch.
    const existing = s.checkpointsByMessageId[key];
    const effectiveCheckpoint =
      checkpoint.filesChanged.length === 0 && existing && existing.filesChanged.length > 0
        ? { ...existing, snapshotHash: checkpoint.snapshotHash || existing.snapshotHash }
        : checkpoint;

    return {
      checkpointsByMessageId: { ...s.checkpointsByMessageId, [key]: effectiveCheckpoint },
      fileReviewOverrides: hasOverrideChanges ? updatedOverrides : s.fileReviewOverrides,
    };
  });
}
