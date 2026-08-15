import React from 'react';
import type { AssistantMessage as AssistantMessageType, ToolResult, ToolCall, Message } from '../../types/message';
import { ToolMessage } from './ToolMessage';
import { ThinkingBlock } from './ThinkingBlock';
import { ReadLsGroupedMessage } from './tools/ReadLsGroupedMessage';
import { EditedGroupedMessage } from './tools/EditedGroupedMessage';
import { SubAgentPanel, type SubAgentInnerBlock } from './tools/SubAgentPanel';
import { markdownCodeComponents } from './markdownCodeComponents';
import { useProjectStore } from '@/services/stores/project-store';
import { formatTokenCount } from '../../utils/format';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface AssistantMessageProps {
  message: AssistantMessageType;
  toolResults?: ToolResult[];
  isStreaming?: boolean;
}

/**
 * Convert sub-agent Message[] to SubAgentInnerBlock[] for SubAgentPanel rendering.
 */
function convertMessagesToBlocks(messages: Message[]): SubAgentInnerBlock[] {
  const blocks: SubAgentInnerBlock[] = [];
  let blockId = 0;

  for (const msg of messages) {
    if (msg.role === 'assistant') {
      const assistantMsg = msg as Extract<Message, { role: 'assistant' }>;
      if (assistantMsg.thinking?.trim()) {
        blocks.push({
          type: 'thinking',
          content: assistantMsg.thinking,
          isFinished: true,
          durationMs: assistantMsg.thinkingDurationMs,
          id: `sub-hist-${blockId++}`,
        });
      }
      if (assistantMsg.content?.trim()) {
        blocks.push({
          type: 'text',
          content: assistantMsg.content,
          id: `sub-hist-${blockId++}`,
        });
      }
      if (assistantMsg.toolCalls) {
        for (const tc of assistantMsg.toolCalls) {
          const result = assistantMsg.toolResults?.find(r => r.id === tc.id);
          blocks.push({
            type: 'tool',
            toolCall: {
              id: tc.id,
              toolName: tc.toolName,
              args: tc.args,
              status: result ? 'COMPLETED' : 'PENDING',
            },
            toolResult: result,
          });
        }
      }
    }
  }

  return blocks;
}

export const AssistantMessage: React.FC<AssistantMessageProps> = ({ message, toolResults = [], isStreaming = false }) => {
  const workDir = useProjectStore((s) => s.currentProject?.path || '');

  // Build a map from toolCallId to its result
  const resultMap = new Map<string, ToolResult>();
  for (const result of toolResults) {
    resultMap.set(result.id, result);
  }

  // Build set of sub-agent toolCallIds to skip in regular tool rendering
  const subAgentToolCallIds = new Set(
    (message.subAgentMessages ?? []).map(g => g.toolCallId)
  );

  // Group consecutive read/ls and edit tool calls (write/create is NOT aggregated, rendered standalone)
  type ToolGroup =
    | { type: 'single'; toolCall: ToolCall }
    | { type: 'readLsGroup'; toolCalls: ToolCall[] }
    | { type: 'editedGroup'; toolCalls: ToolCall[] };

  function groupToolCalls(toolCalls: ToolCall[]): ToolGroup[] {
    const result: ToolGroup[] = [];
    let currentGroup: ToolCall[] = [];
    let currentGroupType: 'readLs' | 'edited' | null = null;

    const flushGroup = () => {
      if (currentGroup.length > 0 && currentGroupType) {
        if (currentGroupType === 'readLs') {
          result.push({ type: 'readLsGroup', toolCalls: [...currentGroup] });
        } else {
          result.push({ type: 'editedGroup', toolCalls: [...currentGroup] });
        }
        currentGroup = [];
        currentGroupType = null;
      }
    };

    for (const tc of toolCalls) {
      if (tc.toolName === 'read' || tc.toolName === 'ls') {
        if (currentGroupType !== 'readLs') {
          flushGroup();
          currentGroupType = 'readLs';
        }
        currentGroup.push(tc);
      } else if (tc.toolName === 'edit') {
        if (currentGroupType !== 'edited') {
          flushGroup();
          currentGroupType = 'edited';
        }
        currentGroup.push(tc);
      } else {
        flushGroup();
        result.push({ type: 'single', toolCall: tc });
      }
    }

    flushGroup();
    return result;
  }

  const renderToolCalls = () => {
    if (!message.toolCalls || message.toolCalls.length === 0) return null;

    const groups = groupToolCalls(message.toolCalls);

    return (
      <div className="flex flex-col gap-2">
        {groups.map((group, groupIndex) => {
          if (group.type === 'single') {
            const tc = group.toolCall;
            // Skip task tools that have subAgentMessages (rendered separately as SubAgentPanel)
            if (subAgentToolCallIds.has(tc.id)) return null;
            const result = resultMap.get(tc.id);
            const status = result
              ? (result.isError ? 'FAILED' as const : 'COMPLETED' as const)
              : (isStreaming ? 'RUNNING' as const : 'PENDING' as const);
            return (
              <ToolMessage
                key={tc.id}
                toolCall={tc}
                result={result}
                status={status}
                workDir={workDir}
              />
            );
          } else if (group.type === 'readLsGroup') {
            // read/ls group
            return (
              <ReadLsGroupedMessage
                key={`read-ls-group-${groupIndex}`}
                items={group.toolCalls.map(tc => ({
                  toolCall: tc,
                  result: resultMap.get(tc.id),
                  status: resultMap.get(tc.id)
                    ? (resultMap.get(tc.id)!.isError ? 'FAILED' as const : 'COMPLETED' as const)
                    : (isStreaming ? 'RUNNING' as const : 'PENDING' as const),
                  workDir,
                }))}
                defaultExpanded={false}
              />
            );
          } else {
            // write/edit group
            return (
              <EditedGroupedMessage
                key={`edited-group-${groupIndex}`}
                items={group.toolCalls.map(tc => ({
                  toolCall: tc,
                  result: resultMap.get(tc.id),
                  status: resultMap.get(tc.id)
                    ? (resultMap.get(tc.id)!.isError ? 'FAILED' as const : 'COMPLETED' as const)
                    : (isStreaming ? 'RUNNING' as const : 'PENDING' as const),
                  workDir,
                }))}
                defaultExpanded={false}
              />
            );
          }
        })}
      </div>
    );
  };

  return (
    <div className="px-4 flex flex-col gap-3">
      {message.thinking && message.thinking.trim() && (
        <ThinkingBlock 
          content={message.thinking} 
          durationMs={message.thinkingDurationMs}
        />
      )}
      
      {message.content && message.content.trim() && (
        <div className="prose prose-sm dark:prose-invert max-w-none">
          <ReactMarkdown 
            remarkPlugins={[remarkGfm]}
            components={markdownCodeComponents}
          >
            {message.content}
          </ReactMarkdown>
        </div>
      )}
      
      {renderToolCalls()}

      {/* Sub-agent panels for history sessions */}
      {message.subAgentMessages?.map((group) => {
        const blocks = convertMessagesToBlocks(group.messages);
        // Extract prompt and inputData from the corresponding toolCall args
        let prompt: string | undefined;
        let inputData: Record<string, unknown> | string | undefined;
        const tc = message.toolCalls?.find(t => t.id === group.toolCallId);
        if (tc?.args) {
          try {
            const parsed = JSON.parse(tc.args);
            prompt = parsed.prompt;
            inputData = parsed.inputData;
          } catch { /* ignore */ }
        }
        // Extract error message and usage from the corresponding tool result
        const toolResult = resultMap.get(group.toolCallId);
        const errorMessage = (toolResult?.isError && toolResult.result) ? toolResult.result : undefined;
        // Pass usage from tool result as accumulatedUsage for SubAgent header display
        const accumulatedUsage = toolResult?.usage
          ? {
              inputTokens: toolResult.usage.inputTokens,
              outputTokens: toolResult.usage.outputTokens,
              cacheReadTokens: toolResult.usage.cacheReadTokens ?? 0,
            }
          : undefined;
        return (
          <SubAgentPanel
            key={`subagent-history-${group.toolCallId}`}
            agentName={group.agentName}
            toolCallId={group.toolCallId}
            prompt={prompt}
            inputData={inputData}
            blocks={blocks}
            isFinished={true}
            errorMessage={errorMessage}
            accumulatedUsage={accumulatedUsage}
          />
        );
      })}

      {/* Inline token bar: shown on hover, embedded at the bottom of the message */}
      {(() => {
        const usage = message.usage;
        if (!usage || usage.outputTokens <= 0) return null;

        const durationMs = usage.durationMs ?? 0;
        const cacheRead = usage.cacheReadTokens ?? 0;
        const cacheWrite = usage.cacheWriteTokens ?? 0;
        const totalCache = cacheRead + cacheWrite;

        let durationText = '';
        if (durationMs > 0) {
          const secs = Math.round(durationMs / 1000);
          if (secs < 60) {
            durationText = `${secs}s`;
          } else {
            const m = Math.floor(secs / 60);
            const s = secs % 60;
            durationText = `${m}m ${s}s`;
          }
        }


        // Format call time from message timestamp
        const callTime = new Date(message.timestamp).toLocaleTimeString();

        return (
          <div className="opacity-0 group-hover:opacity-100 transition-opacity duration-150 pt-1">
            <div className="flex items-center gap-2 text-[11px] text-muted-foreground/60 tabular-nums">
              {usage.modelName && (
                <span className="font-mono">{usage.modelName}</span>
              )}
              <span>↑ {formatTokenCount(usage.inputTokens)}</span>
              <span>↓ {formatTokenCount(usage.outputTokens)}</span>
              {totalCache > 0 && (
                <span>cache {formatTokenCount(totalCache)}</span>
              )}
              {durationText && (
                <span>· {durationText}</span>
              )}
              <span>· {callTime}</span>
            </div>
          </div>
        );
      })()}
    </div>
  );
};