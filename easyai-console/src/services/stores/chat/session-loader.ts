import type { Message, ToolCall, ToolResult, SubAgentMessageGroup, ContextReferences, QueuedMessage } from '@/types/message';
import type { PermissionRequestEvent } from '@/types/socket-event';
import type { CheckpointInfo } from '@/types/checkpoint';
import type {
  MessageSnapshot,
  PendingPermissionInfo,
} from '@/services/session-service';
import { TOOL_NAMES } from '@/constants/tools';
import {
  type StreamingBlock,
  type ThinkingBlockData,
  type TextBlockData,
  type ToolBlockData,
  type CompactionBlockData,
} from './types';
import { convertSubAgentBlocksToMessages, convertSnapshot } from './message-converter';

/**
 * Minimal state shape required for commitStreamingMessage.
 */
interface CommitStateShape {
  streamingBlocks: StreamingBlock[];
  streamingToolOutputs: Record<string, string>;
  messages: Message[];
  pendingMessageData: Record<string, { usage?: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number; durationMs?: number; modelName?: string }; references?: ContextReferences }>;
}

/**
 * Commit streaming blocks to the messages list.
 * Converts the current streaming blocks into persisted Message objects,
 * handling sub-agent message groups and tool result merging.
 */
export function commitStreamingMessageImpl(state: CommitStateShape): Partial<CommitStateShape> {
  if (state.streamingBlocks.length === 0) {
    return {};
  }

  const pendingData = state.pendingMessageData;
  const blocks = [...state.streamingBlocks];

  const newMessages: Message[] = [];
  let currentText = '';
  let currentThinking = '';
  let currentToolCalls: ToolCall[] = [];
  let currentToolResults: ToolResult[] = [];
  let currentMessageId: string | undefined;
  let thinkingDurationMs = 0;
  let textDurationMs = 0;

  const flushCurrentMessage = () => {
    if (currentText || currentThinking || currentToolCalls.length > 0) {
      const msgData = currentMessageId ? pendingData[currentMessageId] : undefined;
      const pendingUsage = msgData?.usage;
      const pendingRefs = msgData?.references;
      newMessages.push({
        role: 'assistant',
        messageId: currentMessageId || undefined,
        content: currentText,
        thinking: currentThinking || undefined,
        toolCalls: currentToolCalls.length > 0 ? currentToolCalls : undefined,
        toolResults: currentToolResults.length > 0 ? currentToolResults : undefined,
        usage: pendingUsage ? {
          inputTokens: pendingUsage.inputTokens,
          outputTokens: pendingUsage.outputTokens,
          totalTokens: pendingUsage.totalTokens,
          cacheReadTokens: pendingUsage.cacheReadTokens,
          cacheWriteTokens: pendingUsage.cacheWriteTokens,
          durationMs: pendingUsage.durationMs,
          modelName: pendingUsage.modelName,
        } : undefined,
        thinkingDurationMs: thinkingDurationMs > 0 ? thinkingDurationMs : undefined,
        textDurationMs: textDurationMs > 0 ? textDurationMs : undefined,
        timestamp: Date.now(),
        references: pendingRefs,
      });
      currentText = '';
      currentThinking = '';
      currentToolCalls = [];
      currentToolResults = [];
      currentMessageId = undefined;
      thinkingDurationMs = 0;
      textDurationMs = 0;
    }
  };

  for (const block of blocks) {
    if (block.type === 'text') {
      const blockMsgId = (block as TextBlockData).messageId;
      // Only flush when messageId changes — thinking and text with the same messageId are merged into one Message
      if (currentMessageId && blockMsgId && currentMessageId !== blockMsgId) {
        flushCurrentMessage();
      }
      if (!currentMessageId) currentMessageId = blockMsgId;
      currentText += (block as TextBlockData).content;
      if ((block as TextBlockData).durationMs) {
        textDurationMs = (block as TextBlockData).durationMs!;
      }
    } else if (block.type === 'thinking') {
      const blockMsgId = (block as ThinkingBlockData).messageId;
      // Only flush when messageId changes — thinking and text with the same messageId are merged into one Message
      if (currentMessageId && blockMsgId && currentMessageId !== blockMsgId) {
        flushCurrentMessage();
      }
      if (!currentMessageId) currentMessageId = blockMsgId;
      currentThinking += (block as ThinkingBlockData).content;
      if ((block as ThinkingBlockData).durationMs) {
        thinkingDurationMs = (block as ThinkingBlockData).durationMs!;
      }
    } else if (block.type === 'tool') {
      const toolBlock = block as ToolBlockData;
      // Strip status field when creating persisted ToolCall
      currentToolCalls.push({
        id: toolBlock.toolCall.id,
        toolName: toolBlock.toolCall.toolName,
        args: toolBlock.toolCall.args,
      });
      // Collect tool result if committed
      if (toolBlock.toolResult) {
        currentToolResults.push(toolBlock.toolResult);
      }
    } else if (block.type === 'compaction') {
      // Flush any in-progress assistant message, then insert the compaction indicator
      // as a custom message so it survives the commit (reconciliation re-adds it from
      // persisted data, but this avoids a visual gap in between).
      flushCurrentMessage();
      const compBlock = block as CompactionBlockData;
      newMessages.push({
        role: 'custom',
        customType: 'compaction',
        metadata: {
          compactedCount: compBlock.compactedCount,
          tokensSaved: compBlock.tokensSaved,
          compactedAt: compBlock.timestamp,
          durationMs: compBlock.durationMs ?? 0,
          currentTokens: compBlock.currentTokens,
          isCompactionIndicator: true,
        },
        timestamp: compBlock.timestamp,
      });
    }
  }

  flushCurrentMessage();

  // Build subAgentMessages from tool blocks that have subAgent data
  const subAgentGroups: SubAgentMessageGroup[] = [];
  for (const block of blocks) {
    if (block.type === 'tool') {
      const toolBlock = block as ToolBlockData;
      if (toolBlock.subAgent) {
        let agentName = toolBlock.subAgent.agentName;
        if (!agentName || agentName === 'unknown') {
          try {
            const parsed = JSON.parse(toolBlock.toolCall.args);
            agentName = parsed.agentType || parsed.subagentType || parsed.agent || parsed.name || 'unknown';
          } catch { /* ignore */ }
        }
        subAgentGroups.push({
          toolCallId: toolBlock.toolCall.id,
          agentName,
          messages: convertSubAgentBlocksToMessages(toolBlock.subAgent.blocks as StreamingBlock[]),
        });
      }
    }
  }

  // Attach subAgentMessages to the corresponding assistant messages
  if (subAgentGroups.length > 0) {
    for (let i = 0; i < newMessages.length; i++) {
      const msg = newMessages[i];
      if (msg.role !== 'assistant') continue;
      const assistantMsg = msg as Extract<Message, { role: 'assistant' }>;
      if (!assistantMsg.toolCalls || assistantMsg.toolCalls.length === 0) continue;

      const groups: SubAgentMessageGroup[] = [];
      for (const tc of assistantMsg.toolCalls) {
        const group = subAgentGroups.find(g => g.toolCallId === tc.id);
        if (group) {
          groups.push(group);
        }
      }

      if (groups.length > 0) {
        newMessages[i] = { ...assistantMsg, subAgentMessages: groups } as Message;
      }
    }
  }

  return {
    messages: [...state.messages, ...newMessages],
    streamingBlocks: [],
    streamingToolOutputs: {},
    pendingMessageData: {},
  };
}

/**
 * Minimal state shape required for loadSessionMessages.
 */
interface LoadSessionStateShape {
  messages: Message[];
  cancelReason: string | null;
  cumulativeUsage: { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number } | null;
  contextTokens: number;
  contextWindow: number;
  checkpointsByMessageId: Record<string, CheckpointInfo>;
  revertState: null;
  fileReviewOverrides: Record<string, 'accepted' | 'rejected'>;
  pendingPermission: PermissionRequestEvent | null;
  currentGoal: null;
  queuedMessages: QueuedMessage[];
  isStreaming: boolean;
  sessionVariables: Record<string, string>;
}

type LoadSetFn = (partial: Partial<LoadSessionStateShape> | ((state: LoadSessionStateShape) => Partial<LoadSessionStateShape>)) => void;

/**
 * Load and process session messages from backend snapshots.
 * Handles message conversion, tool result merging, sub-agent grouping,
 * interrupted session detection, and cumulative usage calculation.
 */
export function loadSessionMessagesImpl(
  messages: MessageSnapshot[],
  pendingPermission: PendingPermissionInfo | null | undefined,
  checkpoints: CheckpointInfo[] | undefined,
  set: LoadSetFn,
  endReason?: string | null,
  variables?: Record<string, string>,
  modelContextLength?: number | null
): void {
  // Separate sub-agent messages (parentToolCallId != null) from parent-level messages
  const subAgentSnapshots = messages.filter((msg) => msg.parentToolCallId != null);
  const parentSnapshots = messages.filter((msg) => msg.parentToolCallId == null);

  // Group sub-agent snapshots by parentToolCallId
  const subAgentGroups = new Map<string, MessageSnapshot[]>();
  for (const snap of subAgentSnapshots) {
    const key = snap.parentToolCallId!;
    if (!subAgentGroups.has(key)) {
      subAgentGroups.set(key, []);
    }
    subAgentGroups.get(key)!.push(snap);
  }

  // Filter out compaction summary UserMessages (metadata.isCompactionSummary=true)
  // These are internal LLM context and should not be displayed in the UI
  const filteredSnapshots = parentSnapshots.filter((msg) => {
    if (msg.role.toLowerCase() === 'user' && msg.metadata?.isCompactionSummary === 'true') {
      return false;
    }
    return true;
  });

  const convertedMessages: Message[] = filteredSnapshots.map(convertSnapshot);

  // Merge tool results into preceding assistant messages
  const mergedMessages = mergeToolResults(convertedMessages);

  // Attach sub-agent message groups to their parent assistant messages
  attachSubAgentGroups(mergedMessages, subAgentGroups);

  // Detect interrupted session
  const cancelReason = detectInterruptedSession(convertedMessages, mergedMessages, pendingPermission, endReason);

  // Calculate cumulative usage
  const cumulativeUsage = calculateCumulativeUsage(messages);

  // Compute contextTokens
  const contextTokens = computeContextTokens(filteredSnapshots);

  // Build checkpoint map
  const checkpointsByMessageId = buildCheckpointMap(checkpoints, mergedMessages);

  set(() => ({
    messages: mergedMessages,
    cancelReason,
    cumulativeUsage,
    contextTokens,
    checkpointsByMessageId,
    revertState: null,
    fileReviewOverrides: {},
    // Adopt the backend-reported context window when provided (model-specific,
    // e.g. 256K/1M); keeps the token bar percentage real instead of the 200K default.
    ...(modelContextLength != null && modelContextLength > 0 ? { contextWindow: modelContextLength } : {}),
    // currentGoal is NOT reset here — callers (loadSessionMessages) reset it
    // on session switch; loadSessionMessagesIncremental must preserve it.
    // Clear queued messages from previous session to prevent stale queue UI
    queuedMessages: [],
    // Always explicitly set pendingPermission to avoid stale state when switching sessions
    pendingPermission: pendingPermission ? {
      type: 'permission_request' as const,
      toolCallId: pendingPermission.toolCallId,
      toolName: pendingPermission.toolName,
      permission: pendingPermission.permission,
      pattern: pendingPermission.pattern,
      arguments: pendingPermission.arguments,
      subAgentToolCallId: pendingPermission.subAgentToolCallId,
      subAgentName: pendingPermission.subAgentName,
    } satisfies PermissionRequestEvent : null,
    ...(pendingPermission ? { isStreaming: false } : {}),
    // Restore persisted session variables on full load; omit on incremental to preserve live state
    ...(variables !== undefined ? { sessionVariables: variables } : {}),
  }));
}

/**
 * Merge tool result messages into preceding assistant messages.
 */
export function mergeToolResults(convertedMessages: Message[]): Message[] {
  const mergedMessages: Message[] = [];
  for (let i = 0; i < convertedMessages.length; i++) {
    const msg = convertedMessages[i];

    if (msg.role === 'assistant' && msg.toolCalls && msg.toolCalls.length > 0) {
      // Look ahead for following tool result messages
      const toolResults: ToolResult[] = [];
      while (i + 1 < convertedMessages.length && convertedMessages[i + 1].role === 'tool') {
        const toolMsg = convertedMessages[i + 1] as Extract<Message, { role: 'tool' }>;
        if (toolMsg.toolResults) {
          toolResults.push(...toolMsg.toolResults);
        }
        i++;
      }

      // Merge tool results into assistant message
      mergedMessages.push({
        ...msg,
        toolResults: toolResults.length > 0 ? toolResults : undefined,
      } as Message);
    } else if (msg.role !== 'tool') {
      mergedMessages.push(msg);
    }
    // Skip standalone tool messages (already merged)
  }
  return mergedMessages;
}

/**
 * Attach sub-agent message groups to their parent assistant messages.
 */
function attachSubAgentGroups(
  mergedMessages: Message[],
  subAgentGroups: Map<string, MessageSnapshot[]>
): void {
  if (subAgentGroups.size === 0) return;

  for (let i = 0; i < mergedMessages.length; i++) {
    const msg = mergedMessages[i];
    if (msg.role !== 'assistant') continue;
    const assistantMsg = msg as Extract<Message, { role: 'assistant' }>;
    if (!assistantMsg.toolCalls || assistantMsg.toolCalls.length === 0) continue;

    const groups: SubAgentMessageGroup[] = [];
    for (const tc of assistantMsg.toolCalls) {
      const snapshots = subAgentGroups.get(tc.id);
      if (!snapshots || snapshots.length === 0) continue;

      // Convert sub-agent snapshots to Messages
      const subMessages: Message[] = [];
      const subConverted = snapshots.map(s => convertSnapshot(s));
      // Merge tool results into sub-agent assistant messages
      for (let j = 0; j < subConverted.length; j++) {
        const sm = subConverted[j];
        if (sm.role === 'assistant' && (sm as Extract<Message, { role: 'assistant' }>).toolCalls) {
          const subToolResults: ToolResult[] = [];
          while (j + 1 < subConverted.length && subConverted[j + 1].role === 'tool') {
            const tr = subConverted[j + 1] as Extract<Message, { role: 'tool' }>;
            if (tr.toolResults) subToolResults.push(...tr.toolResults);
            j++;
          }
          subMessages.push({ ...sm, toolResults: subToolResults.length > 0 ? subToolResults : undefined } as Message);
        } else if (sm.role !== 'tool') {
          subMessages.push(sm);
        }
      }

      // Extract agent name from tool args
      let agentName = 'unknown';
      try {
        const args = JSON.parse(tc.args);
        agentName = args.agentType || args.subagentType || args.agent || args.name || 'unknown';
      } catch { /* ignore */ }

      groups.push({
        toolCallId: tc.id,
        agentName,
        messages: subMessages,
      });
    }

    if (groups.length > 0) {
      mergedMessages[i] = { ...assistantMsg, subAgentMessages: groups } as Message;
    }
  }
}

/**
 * Detect if the session was interrupted (aligned with backend ChatSession.resume()).
 */
function detectInterruptedSession(
  convertedMessages: Message[],
  mergedMessages: Message[],
  pendingPermission: PendingPermissionInfo | null | undefined,
  endReason?: string | null
): string | null {
  const originalLastMsg = convertedMessages[convertedMessages.length - 1];
  const mergedLastMsg = mergedMessages[mergedMessages.length - 1];
  let cancelReason: string | null = null;

  // Priority: persisted endReason from DB (survives page refresh / historical session load)
  if (endReason === 'max_iterations') {
    cancelReason = 'Max Iterations Reached';
  }

  // Scenario 1: Abort during LLM stream — last assistant has stopReason=ABORTED.
  if (!cancelReason && mergedLastMsg && mergedLastMsg.role === 'assistant') {
    const assistantMsg = mergedLastMsg as Extract<Message, { role: 'assistant' }>;
    if (assistantMsg.stopReason === 'ABORTED') {
      cancelReason = 'Session Interrupted';
    }
  }

  // Scenario 2: Last message in DB is a tool result message.
  if (!cancelReason && originalLastMsg?.role === 'tool' && mergedLastMsg?.role !== 'tool' && !pendingPermission) {
    cancelReason = 'Session Interrupted';
  }

  // Scenario 3: Last message is assistant with toolCalls but missing/incomplete toolResults.
  if (!cancelReason && mergedLastMsg && mergedLastMsg.role === 'assistant') {
    const assistantMsg = mergedLastMsg as Extract<Message, { role: 'assistant' }>;
    if (assistantMsg.toolCalls && assistantMsg.toolCalls.length > 0) {
      const hasIncompleteResults = !assistantMsg.toolResults ||
        assistantMsg.toolResults.length < assistantMsg.toolCalls.length;
      // Check if all incomplete tool calls are ask_question (intentional wait)
      const allAreAskQuestion = hasIncompleteResults &&
        assistantMsg.toolCalls.every(tc =>
          tc.toolName === TOOL_NAMES.ASK_QUESTION ||
          (assistantMsg.toolResults || []).some(tr => tr.id === tc.id)
        );
      // Only set cancelReason if there are incomplete non-ask_question tools
      // and no pending permission request (permission wait is not an interruption)
      if (hasIncompleteResults && !allAreAskQuestion && !pendingPermission) {
        cancelReason = 'Session Interrupted';
      }
    }
  }

  return cancelReason;
}

/**
 * Calculate cumulative token usage from ALL messages' usage data.
 */
function calculateCumulativeUsage(
  messages: MessageSnapshot[]
): { inputTokens: number; outputTokens: number; totalTokens: number; cacheReadTokens: number; cacheWriteTokens: number } | null {
  let totalInput = 0;
  let totalOutput = 0;
  let totalAll = 0;
  let totalCacheRead = 0;
  let totalCacheWrite = 0;

  // Detect if this session uses new-format tool usage (ToolResultEntry.usage populated).
  const hasNewFormatToolUsage = messages.some(m =>
    m.role.toLowerCase() === 'tool' &&
    m.content?.some((b: { type: string; usage?: unknown }) => b.type === 'toolResult' && b.usage)
  );

  for (const snapshot of messages) {
    // Skip sub-agent messages only for new-format data
    if (hasNewFormatToolUsage && snapshot.parentToolCallId != null) continue;
    if (snapshot.role.toLowerCase() === 'assistant' && snapshot.usage) {
      totalInput += snapshot.usage.inputTokens;
      totalOutput += snapshot.usage.outputTokens;
      totalAll += snapshot.usage.totalTokens;
      totalCacheRead += snapshot.usage.cacheReadTokens ?? 0;
      totalCacheWrite += snapshot.usage.cacheWriteTokens ?? 0;
    }
    // Count tool usage from tool messages
    if (snapshot.role.toLowerCase() === 'tool' && snapshot.content) {
      for (const block of snapshot.content) {
        if (block.type === 'toolResult' && block.usage) {
          const u = block.usage;
          totalInput += u.inputTokens;
          totalOutput += u.outputTokens;
          totalAll += u.inputTokens + u.outputTokens;
          totalCacheRead += u.cacheReadTokens ?? 0;
          totalCacheWrite += u.cacheWriteTokens ?? 0;
        }
      }
    }
    // Also count compaction summary UserMessages
    if (snapshot.role.toLowerCase() === 'user' && snapshot.metadata?.isCompactionSummary === 'true' && snapshot.usage) {
      totalInput += snapshot.usage.inputTokens;
      totalOutput += snapshot.usage.outputTokens;
      totalAll += snapshot.usage.totalTokens;
      totalCacheRead += snapshot.usage.cacheReadTokens ?? 0;
      totalCacheWrite += snapshot.usage.cacheWriteTokens ?? 0;
    }
  }

  if (totalAll > 0) {
    return { inputTokens: totalInput, outputTokens: totalOutput, totalTokens: totalAll, cacheReadTokens: totalCacheRead, cacheWriteTokens: totalCacheWrite };
  }
  return null;
}

/**
 * Compute contextTokens from the latest source of truth.
 */
function computeContextTokens(filteredSnapshots: MessageSnapshot[]): number {
  const lastAssistant = [...filteredSnapshots]
    .reverse()
    .find(s => s.role.toLowerCase() === 'assistant' && s.usage && !s.compactedAt);
  const lastCompactionIndicator = [...filteredSnapshots]
    .reverse()
    .find(s => s.role.toLowerCase() === 'custom' && s.metadata?.isCompactionIndicator === 'true');

  if (lastAssistant && lastCompactionIndicator && lastCompactionIndicator.timestamp > lastAssistant.timestamp) {
    return Number(lastCompactionIndicator.metadata?.currentTokens) || 0;
  } else if (lastAssistant) {
    return lastAssistant.usage!.inputTokens + (lastAssistant.usage!.cacheReadTokens ?? 0) + (lastAssistant.usage!.cacheWriteTokens ?? 0) + lastAssistant.usage!.outputTokens;
  } else if (lastCompactionIndicator) {
    return Number(lastCompactionIndicator.metadata?.currentTokens) || 0;
  }
  return 0;
}

/**
 * Build checkpoint map from provided checkpoints.
 */
function buildCheckpointMap(
  checkpoints: CheckpointInfo[] | undefined,
  mergedMessages: Message[]
): Record<string, CheckpointInfo> {
  const checkpointsByMessageId: Record<string, CheckpointInfo> = {};
  if (checkpoints && checkpoints.length > 0) {
    for (const cp of checkpoints) {
      const key = cp.assistantMessageId || cp.messageId;
      if (key) checkpointsByMessageId[key] = cp;
    }
    console.debug('[loadSessionMessages] Checkpoint map built:', {
      checkpointCount: checkpoints.length,
      keys: Object.keys(checkpointsByMessageId),
      assistantMessageIds: checkpoints.map(cp => cp.assistantMessageId),
      messageIds: checkpoints.map(cp => cp.messageId),
    });
    const assistantMsgIds = mergedMessages
      .filter(m => m.role === 'assistant')
      .map(m => (m as { messageId?: string }).messageId);
    console.debug('[loadSessionMessages] Assistant message IDs in loaded messages:', assistantMsgIds);
  }
  return checkpointsByMessageId;
}
