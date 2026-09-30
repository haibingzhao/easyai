import { useState } from 'react';
import {
  AlertCircle,
  Check,
  ChevronDown,
  ChevronRight,
  ClipboardCopy,
  ListTodo,
  Loader2,
} from 'lucide-react';
import type { SubAgentSegment } from '@/types/message-segment';
import type { StreamingBlock } from '@/services/stores/chat/types';
import type { TodoInfo } from '@/types/todo';
import { ThinkingBlock } from '../ThinkingBlock';
import { ToolMessage } from '../ToolMessage';
import { i18n } from '@/utils/i18n';
import { formatDurationMs, formatTokenCount } from '@/utils/format';
import { useAutoExpand } from './useAutoExpand';
import { messagesToBlocks } from './messagesToBlocks';

/** Avatar dot palette, picked deterministically by agent name */
const DOT_COLORS = [
  'bg-purple-500',
  'bg-cyan-500',
  'bg-emerald-500',
  'bg-amber-500',
  'bg-rose-500',
  'bg-blue-500',
];

const todoStatusColors: Record<TodoInfo['status'], string> = {
  pending: 'text-gray-400 dark:text-gray-500',
  in_progress: 'text-yellow-500 dark:text-yellow-400',
  completed: 'text-green-500 dark:text-green-400',
  cancelled: 'text-red-500 dark:text-red-400',
};

interface TaskArgs {
  agentName: string;
  prompt?: string;
  inputData?: Record<string, unknown> | string;
}

function parseTaskArgs(args: string): TaskArgs {
  try {
    const parsed = JSON.parse(args);
    const agentName = String(parsed.agentType ?? parsed.subagentType ?? parsed.agent ?? parsed.name ?? 'unknown');
    return {
      agentName,
      prompt: typeof parsed.prompt === 'string' ? parsed.prompt : undefined,
      inputData: parsed.inputData,
    };
  } catch {
    return { agentName: 'unknown' };
  }
}

/** Latest todo snapshot written by the sub-agent (history mode) */
function historyTodos(blocks: StreamingBlock[]): TodoInfo[] | undefined {
  for (let i = blocks.length - 1; i >= 0; i--) {
    const block = blocks[i];
    if (block?.type === 'tool' && block.toolCall.toolName === 'todo_write') {
      try {
        const parsed = JSON.parse(block.toolCall.args) as { todos?: TodoInfo[] };
        if (parsed.todos?.length) return parsed.todos;
      } catch {
        // ignore malformed args
      }
    }
  }
  return undefined;
}

function TodoList({ todos }: { todos: TodoInfo[] }) {
  const completedCount = todos.filter((t) => t.status === 'completed').length;
  return (
    <div className="rounded-md border border-purple-500/20 bg-purple-500/5 p-2.5">
      <div className="flex items-center gap-2 mb-2">
        <ListTodo className="w-3.5 h-3.5 text-purple-400" />
        <span className="text-xs font-medium text-purple-400">
          {i18n('Progress')} ({completedCount}/{todos.length})
        </span>
      </div>
      <div className="space-y-1">
        {todos.map((todo, index) => (
          <div key={todo.id || index} className="flex items-start gap-2 text-xs">
            <span className={`mt-0.5 ${todoStatusColors[todo.status]}`}>
              {todo.status === 'completed' ? '✓' : todo.status === 'in_progress' ? '◐' : todo.status === 'cancelled' ? '✕' : '○'}
            </span>
            <span className={`flex-1 ${todo.status === 'completed' ? 'line-through text-muted-foreground' : 'text-foreground'}`}>
              {todo.content}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

interface SubAgentRowProps {
  segment: SubAgentSegment;
  isLive: boolean;
  streamingToolOutputs?: Record<string, string>;
  workDir?: string;
}

/**
 * Top-level row for a sub-agent (`task`) call: avatar dot + agent name + prompt
 * summary + status. Expanding shows the agent transcript as flat rows.
 */
export function SubAgentRow({ segment, isLive, streamingToolOutputs, workDir }: SubAgentRowProps) {
  const args = parseTaskArgs(segment.toolCall.args);
  const agentName = segment.agentName || args.agentName;
  const live = segment.subAgent;
  const blocks = live ? live.blocks : (segment.group ? messagesToBlocks(segment.group.messages) : []);
  const isFinished = live ? live.isFinished : segment.status !== 'RUNNING' && segment.status !== 'PENDING';
  const isError = segment.toolResult?.isError ?? segment.status === 'FAILED';
  const errorMessage = isError ? segment.toolResult?.result : undefined;
  const resultUsage = segment.toolResult?.usage;
  const usage = live?.accumulatedUsage
    ?? (resultUsage
      ? {
        inputTokens: resultUsage.inputTokens,
        outputTokens: resultUsage.outputTokens,
        cacheReadTokens: resultUsage.cacheReadTokens ?? 0,
      }
      : undefined);
  const todos = live?.todos ?? historyTodos(blocks);
  const durationMs = segment.toolResult?.durationMs ?? resultUsage?.durationMs;

  const [expanded, toggle] = useAutoExpand(isLive && !isFinished);
  const [copied, setCopied] = useState(false);

  const toolCount = blocks.filter((b) => b.type === 'tool').length;

  const statusText = !isFinished
    ? i18n('Sub-agent running...')
    : isError
      ? i18n('Sub-agent failed')
      : durationMs && durationMs > 0
        ? i18n('Sub-agent completed in {duration}').replace('{duration}', formatDurationMs(durationMs))
        : i18n('Completed');
  const dotColor = isError ? 'bg-destructive' : !isFinished ? 'bg-muted-foreground animate-pulse' : 'bg-green-500';
  const avatarColor = DOT_COLORS[Math.abs(hashName(agentName)) % DOT_COLORS.length];
  const promptPreview = args.prompt?.trim() ?? '';

  return (
    <div>
      <button
        type="button"
        onClick={toggle}
        className="w-full flex items-center gap-2 py-0.5 text-left text-sm hover:bg-muted/30 rounded transition-colors"
      >
        {expanded
          ? <ChevronDown className="w-3.5 h-3.5 shrink-0 text-muted-foreground" />
          : <ChevronRight className="w-3.5 h-3.5 shrink-0 text-muted-foreground" />}
        <span className={`w-2 h-2 rounded-full shrink-0 ${avatarColor}`} />
        <span className="text-sm font-medium text-purple-400 shrink-0">{agentName}</span>
        {!isFinished && <Loader2 className="w-3.5 h-3.5 shrink-0 animate-spin text-muted-foreground" />}
        <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${dotColor}`} />
        <span className={`text-xs shrink-0 ${isError ? 'text-destructive' : 'text-muted-foreground'}`}>{statusText}</span>
        {!expanded && toolCount > 0 && (
          <span className="text-xs text-muted-foreground shrink-0">
            · {i18n('Called {count} tools').replace('{count}', String(toolCount))}
          </span>
        )}
        {!expanded && promptPreview && (
          <span className="text-xs text-muted-foreground font-mono truncate">{promptPreview}</span>
        )}
      </button>

      {!expanded && errorMessage && (
        <div className="flex items-center gap-1.5 pl-6 pb-1">
          <AlertCircle className="w-3 h-3 text-destructive shrink-0" />
          <p className="text-xs text-destructive truncate">{errorMessage}</p>
        </div>
      )}

      {expanded && (
        <div className="ml-2 pl-3 border-l border-border space-y-1.5 mt-1">
          {promptPreview && (
            <p className="text-sm text-muted-foreground whitespace-pre-wrap [overflow-wrap:anywhere]">{promptPreview}</p>
          )}

          {!!args.inputData && (
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground/60">
              <button
                type="button"
                onClick={() => {
                  const text = typeof args.inputData === 'string'
                    ? args.inputData
                    : JSON.stringify(args.inputData, null, 2);
                  navigator.clipboard.writeText(text);
                  setCopied(true);
                  setTimeout(() => setCopied(false), 2000);
                }}
                className="hover:text-muted-foreground transition-colors"
                title={i18n('Copy input data')}
              >
                {copied
                  ? <Check className="w-3.5 h-3.5 text-green-500" />
                  : <ClipboardCopy className="w-3.5 h-3.5" />}
              </button>
              <span>Input Data</span>
            </div>
          )}

          {todos && todos.length > 0 && <TodoList todos={todos} />}

          {blocks.map((block) => {
            if (block.type === 'thinking') {
              if (!block.content.trim()) return null;
              return (
                <ThinkingBlock
                  key={block.id}
                  content={block.content}
                  isStreaming={isLive}
                  isFinished={block.isFinished}
                  durationMs={block.durationMs}
                />
              );
            }
            if (block.type === 'text') {
              if (!block.content.trim()) return null;
              return (
                <p
                  key={block.id}
                  className="text-sm text-foreground whitespace-pre-wrap [overflow-wrap:anywhere]"
                >
                  {block.content}
                </p>
              );
            }
            if (block.type === 'compaction') return null;
            return (
              <ToolMessage
                key={block.toolCall.id}
                toolCall={{
                  id: block.toolCall.id,
                  toolName: block.toolCall.toolName,
                  args: block.toolCall.args,
                }}
                result={block.toolResult}
                status={block.toolCall.status}
                streamingOutput={streamingToolOutputs?.[block.toolCall.id]}
                workDir={workDir}
                compact
              />
            );
          })}

          {usage && (usage.inputTokens > 0 || usage.outputTokens > 0) && (
            <div className="text-xs text-muted-foreground">
              {usage.inputTokens > 0 && `↑ ${formatTokenCount(usage.inputTokens)}`}
              {usage.outputTokens > 0 && ` ↓ ${formatTokenCount(usage.outputTokens)}`}
              {usage.cacheReadTokens > 0 && ` cache ${formatTokenCount(usage.cacheReadTokens)}`}
            </div>
          )}

          {errorMessage && (
            <div className="flex items-start gap-2 p-2 rounded-md bg-destructive/10 border border-destructive/20">
              <AlertCircle className="w-4 h-4 text-destructive shrink-0 mt-0.5" />
              <div className="text-sm text-destructive whitespace-pre-wrap break-all">{errorMessage}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function hashName(name: string): number {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) | 0;
  }
  return hash;
}
