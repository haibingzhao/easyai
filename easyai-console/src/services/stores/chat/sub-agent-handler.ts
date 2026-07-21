import type { SocketEvent, MessageEndEvent, PermissionRequestEvent } from '@/types/socket-event';
import type { TodoStatus, TodoPriority, SubAgentTodoGroup } from '@/types/todo';
import { TOOL_NAMES } from '@/constants/tools';
import {
  type StreamingBlock,
  type ThinkingBlockData,
  type TextBlockData,
  type ToolBlockData,
} from './types';

/**
 * Minimal state shape required by the sub-agent event handler.
 * Avoids circular dependency with ChatState.
 */
interface SubAgentStateShape {
  streamingBlocks: StreamingBlock[];
  streamingToolOutputs: Record<string, string>;
  subAgentTodos: Record<string, SubAgentTodoGroup>;
}

type SetFn<S> = (partial: Partial<S> | ((state: S) => Partial<S>)) => void;

/**
 * Dispatch a sub-agent event into the parent ToolBlockData.
 * Sub-agent's thinking/text/tool blocks are stored directly on the parent tool block's `subAgent` field,
 * so the sub-agent panel renders inside the same tool card (not as a separate card).
 */
export function dispatchSubAgentEvent(
  parentToolCallId: string,
  agentName: string,
  event: SocketEvent,
  get: () => SubAgentStateShape,
  set: SetFn<SubAgentStateShape>
) {
  const state = get();
  const blocks = [...state.streamingBlocks];

  // Find the parent tool block (the subagent tool call)
  const parentToolIndex = blocks.findIndex(b => b.type === 'tool' && b.toolCall.id === parentToolCallId);
  if (parentToolIndex === -1) return; // Parent tool block not found, ignore

  const parentToolBlock = { ...blocks[parentToolIndex] } as ToolBlockData;

  // Initialize subAgent on the parent tool block if not present
  if (!parentToolBlock.subAgent) {
    parentToolBlock.subAgent = {
      agentName,
      blocks: [],
      isFinished: false,
      accumulatedUsage: { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0 },
    };
  } else {
    // Shallow-copy subAgent to trigger React re-render
    parentToolBlock.subAgent = { ...parentToolBlock.subAgent, blocks: [...parentToolBlock.subAgent.blocks] };
  }

  const subBlocks = parentToolBlock.subAgent.blocks;

  // Snapshot sub-agent todo_write args so the SubAgentPanel can show progress.
  if (event.type === 'tool_execution_start' && event.toolName === TOOL_NAMES.TODO_WRITE && event.args?.todos) {
    const rawTodos = event.args.todos as Array<{ content?: string; status?: string; priority?: string }>;
    if (Array.isArray(rawTodos)) {
      const parsedTodos = rawTodos.map((t, i) => ({
        id: `todo-${Date.now()}-${i}`,
        content: t.content || '',
        status: normalizeStatus(t.status),
        priority: normalizePriority(t.priority),
        position: i,
        createdAt: Date.now(),
      }));
      parentToolBlock.subAgent.todos = parsedTodos;
      // Sync into chatStore.subAgentTodos for the right-side Progress panel
      set((s) => ({
        subAgentTodos: { ...s.subAgentTodos, [agentName]: { todos: parsedTodos, toolCallId: parentToolCallId } },
      }));
    }
  }

  // Route event to sub-agent's own blocks
  switch (event.type) {
    case 'thinking_delta':
      appendToSubAgentBlock(subBlocks, { type: 'thinking', content: event.delta, id: `sa-thinking-${Date.now()}`, messageId: event.messageId });
      break;
    case 'thinking_end':
      finishSubAgentThinkingBlock(subBlocks, event.durationMs);
      break;
    case 'text_delta':
      appendToSubAgentBlock(subBlocks, { type: 'text', content: event.delta, id: `sa-text-${Date.now()}`, messageId: event.messageId });
      break;
    case 'text_end':
      if (event.durationMs) {
        for (let i = subBlocks.length - 1; i >= 0; i--) {
          if (subBlocks[i].type === 'text') {
            subBlocks[i] = { ...subBlocks[i], durationMs: event.durationMs } as TextBlockData;
            break;
          }
        }
      }
      break;
    case 'tool_execution_start': {
      // Look for existing PENDING block (from permission_request) and update it to RUNNING
      const existingIdx = subBlocks.findIndex(
        b => b.type === 'tool' && b.toolCall.id === event.toolCallId
      );
      if (existingIdx !== -1) {
        const existing = subBlocks[existingIdx] as ToolBlockData;
        subBlocks[existingIdx] = {
          ...existing,
          toolCall: {
            ...existing.toolCall,
            args: event.args ? JSON.stringify(event.args, null, 2) : existing.toolCall.args,
            status: 'RUNNING',
          },
        };
      } else {
        subBlocks.push({
          type: 'tool',
          toolCall: { id: event.toolCallId, toolName: event.toolName, args: event.args ? JSON.stringify(event.args, null, 2) : '', status: 'RUNNING' },
        });
      }
      break;
    }
    case 'toolcall_status': {
      const statusIdx = subBlocks.findIndex(b => b.type === 'tool' && b.toolCall.id === event.toolCallId);
      if (statusIdx !== -1) {
        const tb = subBlocks[statusIdx] as ToolBlockData;
        subBlocks[statusIdx] = { ...tb, toolCall: { ...tb.toolCall, status: event.status } };
      }
      break;
    }
    case 'tool_execution_update':
      // Sub-agent tool output — store in streamingToolOutputs
      set((s) => ({
        streamingToolOutputs: { ...s.streamingToolOutputs, [event.toolCallId]: (s.streamingToolOutputs[event.toolCallId] || '') + event.output }
      }));
      break;
    case 'tool_execution_end': {
      // Ensure tool block exists in sub-agent blocks
      const tIdx = subBlocks.findIndex(b => b.type === 'tool' && b.toolCall.id === event.toolCallId);
      if (tIdx === -1) {
        subBlocks.push({
          type: 'tool',
          toolCall: { id: event.toolCallId, toolName: event.toolName, args: '', status: 'COMPLETED' },
        });
      }
      const toolIdx = subBlocks.findIndex(b => b.type === 'tool' && b.toolCall.id === event.toolCallId);
      if (toolIdx !== -1) {
        const tb = subBlocks[toolIdx] as ToolBlockData;
        subBlocks[toolIdx] = {
          ...tb,
          toolResult: {
            id: event.toolCallId,
            toolName: event.toolName,
            result: event.result || '',
            isError: event.isError,
            exitCode: event.exitCode,
            mimeType: event.mimeType,
            truncated: event.truncated,
          },
        };
      }
      break;
    }
    case 'message_end': {
      // Don't mark as finished here — sub-agents can have multiple turns (multiple message_end events).
      // Completion is signaled by the parent's tool_execution_end event (handled in handleEvent).
      // Usage accumulation to cumulativeUsage is handled by tool_execution_end's toolUsage field,
      // NOT here — to avoid double-counting (parent message's usage in DB already includes subagent usage).
      // However, we DO update accumulatedUsage here for real-time SubAgent header display.
      const msgEndEvent = event as MessageEndEvent;
      if (msgEndEvent.usage) {
        const prev = parentToolBlock.subAgent.accumulatedUsage;
        parentToolBlock.subAgent.accumulatedUsage = {
          inputTokens: prev.inputTokens + msgEndEvent.usage.inputTokens,
          outputTokens: prev.outputTokens + msgEndEvent.usage.outputTokens,
          cacheReadTokens: prev.cacheReadTokens + (msgEndEvent.usage.cacheReadTokens ?? 0),
        };
      }
      break;
    }
    case 'permission_request': {
      // Sub-agent is waiting for permission — show pending status in the sub-agent panel
      const permEvent = event as PermissionRequestEvent;
      subBlocks.push({
        type: 'tool',
        toolCall: {
          id: permEvent.toolCallId,
          toolName: permEvent.toolName,
          args: permEvent.arguments ? JSON.stringify(permEvent.arguments, null, 2) : '',
          status: 'PENDING' as const,
        },
      });
      break;
    }
    default:
      break;
  }

  parentToolBlock.subAgent.blocks = subBlocks;
  blocks[parentToolIndex] = parentToolBlock;
  set({ streamingBlocks: blocks });
}

function appendToSubAgentBlock(blocks: StreamingBlock[], newBlock: ThinkingBlockData | TextBlockData) {
  const last = blocks[blocks.length - 1];
  if (last && last.type === newBlock.type &&
      (!newBlock.messageId || (last as ThinkingBlockData | TextBlockData).messageId === newBlock.messageId)) {
    blocks[blocks.length - 1] = { ...last, content: last.content + newBlock.content } as typeof last;
  } else {
    blocks.push(newBlock);
  }
}

function finishSubAgentThinkingBlock(blocks: StreamingBlock[], durationMs?: number) {
  for (let i = blocks.length - 1; i >= 0; i--) {
    if (blocks[i].type === 'thinking' && !(blocks[i] as ThinkingBlockData).isFinished) {
      blocks[i] = { ...blocks[i], isFinished: true, durationMs } as ThinkingBlockData;
      break;
    }
  }
}

const VALID_STATUSES: ReadonlySet<TodoStatus> = new Set(['pending', 'in_progress', 'completed', 'cancelled']);
const VALID_PRIORITIES: ReadonlySet<TodoPriority> = new Set(['high', 'medium', 'low']);

function normalizeStatus(raw: string | undefined): TodoStatus {
  const val = (raw || 'pending').toLowerCase();
  return (VALID_STATUSES.has(val as TodoStatus) ? val : 'pending') as TodoStatus;
}

function normalizePriority(raw: string | undefined): TodoPriority {
  const val = (raw || 'medium').toLowerCase();
  return (VALID_PRIORITIES.has(val as TodoPriority) ? val : 'medium') as TodoPriority;
}
