import type {
  ChatStreamEvent,
  TextDeltaEvent,
  TextEndEvent,
  ThinkingDeltaEvent,
  ThinkingEndEvent,
  ToolCallStartEvent,
  ToolCallDeltaEvent,
  ToolCallStatusEvent,
  ToolExecutionStartEvent,
  ToolExecutionUpdateEvent,
  ToolExecutionEndEvent,
  MessageEndEvent,
  SessionContextEvent,
  BackgroundTaskEvent,
} from '@/types/socket-event';
import type { ToolResult, ToolResultContentBlock } from '@/types/message';
import type {
  LivePreview,
  StreamingBlock,
  TextBlockData,
  ThinkingBlockData,
  ToolBlockData,
} from './types';
import { useChatStore } from '../chat-store';

/**
 * Reduce a watch-tap event into the isolated `livePreview` of the single in-flight round.
 *
 * This path is deliberately separate from `handleChatEvent`: during recovery the DB poll owns
 * committed rounds (and clears `streamingBlocks` on every load), so the tap must not write the
 * round transcript. Round-independent store fields (background tasks, context window) are the one
 * exception: `handleChatEvent` writes the same values from the same events, so mirroring them here
 * cannot disagree with the poll — and a task announced while recovering has no other source until
 * the next reload. The preview is rendered only while its `messageId` is absent from `messages`
 * (see `MessageList`), which makes a committed round and its live preview mutually exclusive — the
 * same turn can never render twice.
 *
 * Sub-agent events (carrying `subAgentToolCallId`) are intentionally ignored here; the poll
 * surfaces their committed transcript. Kept minimal on purpose (方案 1).
 */
export function handleWatchEvent(event: ChatStreamEvent): void {
  // Only meaningful while a recovery owns the session; a stray tap event afterwards must not
  // resurrect a preview that would render with no running session to supersede it.
  if (useChatStore.getState().runningSessionId === null) return;
  if ((event as { subAgentToolCallId?: string }).subAgentToolCallId) return;

  switch (event.type) {
    case 'text_delta':
      appendText((event as TextDeltaEvent).delta, (event as TextDeltaEvent).messageId);
      break;
    case 'thinking_delta':
      appendThinking((event as ThinkingDeltaEvent).delta, (event as ThinkingDeltaEvent).messageId);
      break;
    case 'thinking_end':
      finishThinking((event as ThinkingEndEvent).durationMs);
      break;
    case 'text_end':
      finishText((event as TextEndEvent).durationMs);
      break;
    case 'toolcall_start': {
      const e = event as ToolCallStartEvent;
      startTool(e.id, e.toolName, undefined);
      break;
    }
    case 'toolcall_delta': {
      const e = event as ToolCallDeltaEvent;
      appendToolArgs(e.id, e.delta);
      break;
    }
    case 'toolcall_status': {
      const e = event as ToolCallStatusEvent;
      setToolStatus(e.toolCallId, e.status);
      break;
    }
    case 'tool_execution_start': {
      const e = event as ToolExecutionStartEvent;
      startTool(e.toolCallId, e.toolName, e.args);
      break;
    }
    case 'tool_execution_update': {
      const e = event as ToolExecutionUpdateEvent;
      appendToolOutput(e.toolCallId, e.output);
      break;
    }
    case 'tool_execution_end':
      commitTool(event as ToolExecutionEndEvent);
      break;
    case 'message_end': {
      // The round is now committed in DB. When the preview carries its messageId, the render-side
      // dedup hides the preview the moment the poll delivers that copy — so keep it (its tool
      // results are ahead of the poll). An unkeyed preview cannot be deduped, so drop it to avoid
      // a duplicate that never gets superseded.
      const e = event as MessageEndEvent;
      const preview = useChatStore.getState().livePreview;
      if (preview && preview.messageId === null && e.messageId) {
        useChatStore.getState().clearLivePreview();
      }
      break;
    }
    case 'background_task': {
      const e = event as BackgroundTaskEvent;
      useChatStore.setState((s) => ({ backgroundTasks: { ...s.backgroundTasks, [e.taskId]: e } }));
      break;
    }
    case 'session_context': {
      const e = event as SessionContextEvent;
      if (e.modelContextLength > 0) useChatStore.setState({ contextWindow: e.modelContextLength });
      break;
    }
    default:
      break;
  }
}

/**
 * Resolve the preview to write for an incoming round: start a fresh one when the round changes
 * (a new messageId means the previous round committed and now belongs to the poll) or when none
 * exists yet; otherwise keep accumulating the current round.
 */
function beginPreview(messageId?: string): LivePreview {
  const current = useChatStore.getState().livePreview;
  if (!current) return { messageId: messageId ?? null, blocks: [], toolOutputs: {} };
  if (messageId && current.messageId !== messageId) return { messageId, blocks: [], toolOutputs: {} };
  return current;
}

function appendText(delta: string, messageId?: string): void {
  const preview = beginPreview(messageId);
  const blocks = [...preview.blocks];
  const last = blocks[blocks.length - 1];
  if (last && last.type === 'text' && (!messageId || (last as TextBlockData).messageId === messageId)) {
    blocks[blocks.length - 1] = { ...last, content: last.content + delta };
  } else {
    blocks.push({
      type: 'text',
      content: delta,
      id: `pv-text-${Date.now()}-${Math.random().toString(36).slice(2)}`,
      messageId,
    });
  }
  useChatStore.setState({ livePreview: { ...preview, blocks } });
}

function appendThinking(delta: string, messageId?: string): void {
  const preview = beginPreview(messageId);
  const blocks = [...preview.blocks];
  const last = blocks[blocks.length - 1];
  if (
    last && last.type === 'thinking' && !(last as ThinkingBlockData).isFinished &&
    (!messageId || (last as ThinkingBlockData).messageId === messageId)
  ) {
    const prev = last as ThinkingBlockData;
    blocks[blocks.length - 1] = { type: 'thinking', content: prev.content + delta, id: prev.id, messageId: prev.messageId || messageId };
  } else {
    blocks.push({ type: 'thinking', content: delta, id: `pv-thinking-${Date.now()}`, messageId });
  }
  useChatStore.setState({ livePreview: { ...preview, blocks } });
}

function finishThinking(durationMs?: number): void {
  mapLastBlock('thinking', (block) => ({ ...block, isFinished: true, durationMs } as ThinkingBlockData));
}

function finishText(durationMs?: number): void {
  if (!durationMs) return;
  mapLastBlock('text', (block) => ({ ...block, durationMs } as TextBlockData));
}

function mapLastBlock(
  type: 'thinking' | 'text',
  fn: (block: StreamingBlock) => StreamingBlock
): void {
  const preview = useChatStore.getState().livePreview;
  if (!preview) return;
  const blocks = [...preview.blocks];
  for (let i = blocks.length - 1; i >= 0; i--) {
    if (blocks[i].type === type && !(blocks[i] as ThinkingBlockData).isFinished) {
      blocks[i] = fn(blocks[i]);
      break;
    }
  }
  useChatStore.setState({ livePreview: { ...preview, blocks } });
}

function startTool(id: string, toolName: string, args?: Record<string, unknown>): void {
  const preview = beginPreviewForTool();
  const blocks = [...preview.blocks];
  const argsStr = args ? JSON.stringify(args, null, 2) : '';
  const existingIndex = blocks.findIndex((b) => b.type === 'tool' && b.toolCall.id === id);
  if (existingIndex !== -1) {
    const existing = blocks[existingIndex] as ToolBlockData;
    blocks[existingIndex] = {
      ...existing,
      toolCall: { ...existing.toolCall, toolName: toolName || existing.toolCall.toolName, args: argsStr || existing.toolCall.args },
    };
  } else {
    blocks.push({ type: 'tool', toolCall: { id, toolName, args: argsStr, status: 'PENDING' } });
  }
  useChatStore.setState({ livePreview: { ...preview, blocks } });
}

/** Tools belong to the round already being previewed; do not reset on an absent messageId. */
function beginPreviewForTool(): LivePreview {
  const current = useChatStore.getState().livePreview;
  return current ?? { messageId: null, blocks: [], toolOutputs: {} };
}

function appendToolArgs(id: string, delta: string): void {
  const preview = useChatStore.getState().livePreview;
  if (!preview) return;
  const blocks = [...preview.blocks];
  const idx = blocks.findIndex((b) => b.type === 'tool' && b.toolCall.id === id);
  if (idx === -1) return;
  const block = blocks[idx] as ToolBlockData;
  blocks[idx] = { ...block, toolCall: { ...block.toolCall, args: block.toolCall.args + delta } };
  useChatStore.setState({ livePreview: { ...preview, blocks } });
}

function setToolStatus(id: string, status: ToolBlockData['toolCall']['status']): void {
  const preview = useChatStore.getState().livePreview;
  if (!preview) return;
  const blocks = [...preview.blocks];
  const idx = blocks.findIndex((b) => b.type === 'tool' && b.toolCall.id === id);
  if (idx === -1) return;
  const block = blocks[idx] as ToolBlockData;
  blocks[idx] = { ...block, toolCall: { ...block.toolCall, status } };
  useChatStore.setState({ livePreview: { ...preview, blocks } });
}

function appendToolOutput(id: string, output: string): void {
  const preview = useChatStore.getState().livePreview;
  if (!preview) return;
  useChatStore.setState({
    livePreview: { ...preview, toolOutputs: { ...preview.toolOutputs, [id]: (preview.toolOutputs[id] ?? '') + output } },
  });
}

function commitTool(event: ToolExecutionEndEvent): void {
  const preview = useChatStore.getState().livePreview;
  if (!preview) return;
  const rawResult = event.result ?? preview.toolOutputs[event.toolCallId] ?? '';
  let contentBlocks: ToolResultContentBlock[] | undefined;
  try {
    const parsed = JSON.parse(rawResult);
    if (Array.isArray(parsed)) contentBlocks = parsed as ToolResultContentBlock[];
  } catch {
    /* Not valid JSON — keep as plain text */
  }
  const blocks = [...preview.blocks];
  const idx = blocks.findIndex((b) => b.type === 'tool' && b.toolCall.id === event.toolCallId);
  const toolName = idx !== -1 ? (blocks[idx] as ToolBlockData).toolCall.toolName : event.toolName;
  const toolResult: ToolResult = {
    id: event.toolCallId,
    toolName,
    result: rawResult,
    contentBlocks,
    details: event.details,
    isError: event.isError,
    exitCode: event.exitCode,
    mimeType: event.mimeType,
    truncated: event.truncated,
    usage: event.toolUsage,
    durationMs: event.toolUsage?.durationMs,
  };
  if (idx !== -1) {
    const block = blocks[idx] as ToolBlockData;
    blocks[idx] = { ...block, toolResult, toolCall: { ...block.toolCall, status: event.isError ? 'FAILED' : 'COMPLETED' } };
  } else {
    blocks.push({
      type: 'tool',
      toolCall: { id: event.toolCallId, toolName: event.toolName, args: '', status: event.isError ? 'FAILED' : 'COMPLETED' },
      toolResult,
    });
  }
  const toolOutputs = { ...preview.toolOutputs };
  delete toolOutputs[event.toolCallId];
  useChatStore.setState({ livePreview: { ...preview, blocks, toolOutputs } });
}
