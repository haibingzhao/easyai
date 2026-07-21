/**
 * SubAgentPanel — 可折叠面板，展示子 Agent 执行过程
 * 支持 streaming 模式（接收 SubAgentBlockData 的内部 blocks）
 */

import { useState } from 'react';
import { ChevronDown, ChevronRight, ChevronsDown, ChevronsUp, Bot, Loader2, AlertCircle, ListTodo, ClipboardCopy, Check } from 'lucide-react';
import { ThinkingBlock } from '../ThinkingBlock';
import { ToolMessage } from '../ToolMessage';
import { ReadLsGroupedMessage } from './ReadLsGroupedMessage';
import { EditedGroupedMessage } from './EditedGroupedMessage';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { markdownCodeComponents } from '../markdownCodeComponents';
import { formatTokenCount } from '@/utils/format';
import type { TodoInfo } from '@/types/todo';
import type {
  StreamingBlock,
  ToolBlockData,
} from '@/services/stores/chat/types';

/** Sub-agent inner blocks — re-exported for backward compatibility */
export type SubAgentInnerBlock = StreamingBlock;

const todoStatusColors: Record<TodoInfo['status'], string> = {
  pending: 'text-gray-400 dark:text-gray-500',
  in_progress: 'text-yellow-500 dark:text-yellow-400',
  completed: 'text-green-500 dark:text-green-400',
  cancelled: 'text-red-500 dark:text-red-400',
};

function SubAgentTodoList({ todos }: { todos: TodoInfo[] }) {
  const completedCount = todos.filter(t => t.status === 'completed').length;
  return (
    <div className="rounded-md border border-purple-500/20 bg-purple-500/5 p-2.5">
      <div className="flex items-center gap-2 mb-2">
        <ListTodo className="w-3.5 h-3.5 text-purple-400" />
        <span className="text-xs font-medium text-purple-400">
          Progress ({completedCount}/{todos.length})
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

/** Check if a block is a read or ls tool block */
function isReadOrLsTool(block: SubAgentInnerBlock): boolean {
  return block.type === 'tool' && (block.toolCall.toolName === 'read' || block.toolCall.toolName === 'ls');
}

/** Check if a block is a write or edit tool block */
function isWriteOrEditTool(block: SubAgentInnerBlock): boolean {
  return block.type === 'tool' && (block.toolCall.toolName === 'write' || block.toolCall.toolName === 'edit');
}

type GroupedSubBlock =
  | { type: 'single'; block: SubAgentInnerBlock }
  | { type: 'group'; items: ToolBlockData[]; groupId: string; groupType: 'readLs' | 'edited' };

/** Group consecutive read/ls and write/edit tool blocks together */
function groupToolBlocks(blocks: SubAgentInnerBlock[]): GroupedSubBlock[] {
  const result: GroupedSubBlock[] = [];
  let currentGroup: ToolBlockData[] = [];
  let currentGroupType: 'readLs' | 'edited' | null = null;
  let groupCounter = 0;

  const flushGroup = () => {
    if (currentGroup.length > 0 && currentGroupType) {
      const prefix = currentGroupType === 'readLs' ? 'sub-read-ls' : 'sub-edited';
      result.push({ type: 'group', items: [...currentGroup], groupId: `${prefix}-${groupCounter++}`, groupType: currentGroupType });
      currentGroup = [];
      currentGroupType = null;
    }
  };

  for (const block of blocks) {
    if (isReadOrLsTool(block)) {
      if (currentGroupType !== 'readLs') {
        flushGroup();
        currentGroupType = 'readLs';
      }
      currentGroup.push(block as ToolBlockData);
    } else if (isWriteOrEditTool(block)) {
      if (currentGroupType !== 'edited') {
        flushGroup();
        currentGroupType = 'edited';
      }
      currentGroup.push(block as ToolBlockData);
    } else {
      flushGroup();
      result.push({ type: 'single', block });
    }
  }

  flushGroup();
  return result;
}

interface SubAgentPanelProps {
  agentName: string;
  toolCallId: string;
  prompt?: string;
  inputData?: Record<string, unknown> | string;
  blocks: SubAgentInnerBlock[];
  isFinished: boolean;
  streamingToolOutputs?: Record<string, string>;
  errorMessage?: string;
  /** Accumulated token usage from sub-agent's LLM calls */
  accumulatedUsage?: { inputTokens: number; outputTokens: number; cacheReadTokens: number };
  /** Latest todo snapshot from the sub-agent's todo_write calls */
  todos?: TodoInfo[];
}

export function SubAgentPanel({
  agentName,
  prompt,
  inputData,
  blocks,
  isFinished,
  streamingToolOutputs,
  errorMessage,
  accumulatedUsage,
  todos,
}: SubAgentPanelProps) {
  const [isCollapsed, setIsCollapsed] = useState(true);
  const [isContentExpanded, setIsContentExpanded] = useState(false);
  const [copiedInput, setCopiedInput] = useState(false);

  const statusText = isFinished ? (errorMessage ? 'Failed' : 'Completed') : 'Running...';
  const statusColor = isFinished
    ? (errorMessage ? 'text-destructive' : 'text-muted-foreground')
    : 'text-muted-foreground';
  const statusDotColor = isFinished
    ? (errorMessage ? 'bg-destructive' : 'bg-green-500')
    : 'bg-muted-foreground animate-pulse';

  const shouldShowContentExpand = blocks.length > 5;

  return (
    <div className="group border border-purple-500/30 rounded-lg bg-card">
        {/* Header — collapsible */}
        <button
          type="button"
          onClick={() => setIsCollapsed(!isCollapsed)}
          className="w-full flex items-center gap-2 p-2.5 hover:bg-muted/50 text-left"
        >
          {isCollapsed ? (
            <ChevronRight className="w-4 h-4 shrink-0 text-purple-400" />
          ) : (
            <ChevronDown className="w-4 h-4 shrink-0 text-purple-400" />
          )}
          <Bot className="w-4 h-4 text-purple-400" />
          <span className="text-sm font-medium text-purple-400">
            SubAgent: {agentName}
          </span>
          <div className="flex-1" />
          {!isFinished && (
            <Loader2 className="w-3.5 h-3.5 text-muted-foreground animate-spin" />
          )}
          <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
          <span className={`text-xs ${statusColor}`}>{statusText}</span>
        </button>

        {/* Collapsed summary: tool call count + token usage */}
        {isCollapsed && (() => {
          const toolCount = blocks.filter(b => b.type === 'tool').length;
          const hasUsage = accumulatedUsage && (accumulatedUsage.inputTokens > 0 || accumulatedUsage.outputTokens > 0);
          if (toolCount === 0 && !hasUsage) return null;
          const parts: string[] = [];
          if (toolCount > 0) parts.push(`共调用${toolCount}次工具`);
          if (hasUsage) {
            const { inputTokens, outputTokens, cacheReadTokens } = accumulatedUsage;
            const tokenParts: string[] = [];
            if (inputTokens > 0) tokenParts.push(`↑ ${formatTokenCount(inputTokens)}`);
            if (outputTokens > 0) tokenParts.push(`↓ ${formatTokenCount(outputTokens)}`);
            if (cacheReadTokens > 0) tokenParts.push(`cache ${formatTokenCount(cacheReadTokens)}`);
            if (tokenParts.length > 0) parts.push(tokenParts.join(' '));
          }
          return (
            <div className="px-3 pt-1 pb-2.5">
              <span className="text-xs text-muted-foreground">{parts.join(' · ')}</span>
            </div>
          );
        })()}
        {/* Collapsed error indicator */}
        {isCollapsed && errorMessage && (
          <div className="px-3 pb-2 flex items-center gap-1.5">
            <AlertCircle className="w-3 h-3 text-destructive shrink-0" />
            <p className="text-xs text-destructive truncate">{errorMessage}</p>
          </div>
        )}
        {/* Collapsed inputData indicator */}
        {isCollapsed && inputData && (
          <div className="px-3 pb-2 flex items-center gap-1.5 text-xs text-muted-foreground/60">
            <button
              onClick={(e) => {
                e.stopPropagation();
                const text = typeof inputData === 'string' ? inputData : JSON.stringify(inputData, null, 2);
                navigator.clipboard.writeText(text);
                setCopiedInput(true);
                setTimeout(() => setCopiedInput(false), 2000);
              }}
              className="hover:text-muted-foreground transition-colors"
              title="Copy input data"
            >
              {copiedInput ? <Check className="w-3.5 h-3.5 text-green-500" /> : <ClipboardCopy className="w-3.5 h-3.5" />}
            </button>
            <span>Input Data</span>
          </div>
        )}

        {/* Expanded content */}
        {!isCollapsed && (
          <div className="px-3 py-2 bg-muted/30 subagent-content">
            <div
              className={`space-y-2 ${!isContentExpanded && shouldShowContentExpand ? 'max-h-[20em] overflow-y-auto' : isContentExpanded ? 'max-h-[50em] overflow-y-auto' : ''}`}
            >
            {/* Agent prompt */}
            {prompt && (
              <div className="prose prose-sm dark:prose-invert max-w-none text-sm text-muted-foreground">
                <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>{prompt}</ReactMarkdown>
              </div>
            )}

            {/* Input data indicator */}
            {inputData && (
              <div className="flex items-center gap-1.5 text-xs text-muted-foreground/60">
                <button
                  onClick={() => {
                    const text = typeof inputData === 'string' ? inputData : JSON.stringify(inputData, null, 2);
                    navigator.clipboard.writeText(text);
                    setCopiedInput(true);
                    setTimeout(() => setCopiedInput(false), 2000);
                  }}
                  className="hover:text-muted-foreground transition-colors"
                  title="Copy input data"
                >
                  {copiedInput ? <Check className="w-3.5 h-3.5 text-green-500" /> : <ClipboardCopy className="w-3.5 h-3.5" />}
                </button>
                <span>Input Data</span>
              </div>
            )}

            {/* Sub-agent todo progress */}
            {todos && todos.length > 0 && <SubAgentTodoList todos={todos} />}

            {groupToolBlocks(blocks).map((grouped) => {
              if (grouped.type === 'single') {
                const block = grouped.block;
                switch (block.type) {
                  case 'thinking':
                    return block.content.trim() ? (
                      <ThinkingBlock
                        key={block.id}
                        content={block.content}
                        isStreaming={!block.isFinished}
                        isFinished={block.isFinished}
                        durationMs={block.durationMs}
                      />
                    ) : null;

                  case 'text': {
                    if (!block.content.trim()) return null;
                    return (
                      <div key={block.id} className="prose prose-sm dark:prose-invert max-w-none text-sm">
                        <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                          {block.content}
                        </ReactMarkdown>
                      </div>
                    );
                  }

                  case 'tool': {
                    const output = streamingToolOutputs?.[block.toolCall.id];
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
                        streamingOutput={output}
                      />
                    );
                  }

                  default:
                    return null;
                }
              } else {
                // Grouped blocks (read/ls or write/edit)
                const items = grouped.items.map(item => ({
                  toolCall: {
                    id: item.toolCall.id,
                    toolName: item.toolCall.toolName,
                    args: item.toolCall.args,
                  },
                  result: item.toolResult,
                  status: item.toolCall.status,
                  streamingOutput: streamingToolOutputs?.[item.toolCall.id],
                }));
                if (grouped.groupType === 'readLs') {
                  return (
                    <ReadLsGroupedMessage
                      key={grouped.groupId}
                      items={items}
                      defaultExpanded={false}
                    />
                  );
                } else {
                  return (
                    <EditedGroupedMessage
                      key={grouped.groupId}
                      items={items}
                      defaultExpanded={false}
                    />
                  );
                }
              }
            })}
            {/* Error message */}
            </div>

            {shouldShowContentExpand && (
              <button
                onClick={() => setIsContentExpanded(!isContentExpanded)}
                className="mt-2 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
              >
                {isContentExpanded ? (
                  <>
                    <ChevronsUp className="w-3 h-3" />
                    <span>收起</span>
                  </>
                ) : (
                  <>
                    <ChevronsDown className="w-3 h-3" />
                    <span>更多 ({blocks.length} 项)</span>
                  </>
                )}
              </button>
            )}
            {errorMessage && (
              <div className="flex items-start gap-2 p-2 rounded-md bg-destructive/10 border border-destructive/20">
                <AlertCircle className="w-4 h-4 text-destructive shrink-0 mt-0.5" />
                <div className="text-sm text-destructive whitespace-pre-wrap break-all">
                  {errorMessage}
                </div>
              </div>
            )}
          </div>
        )}
    </div>
  );
}
