/**
 * SubAgentToolMessage — 子 Agent 工具渲染组件
 * 在 ToolMessageRouter 中注册，当 toolName === 'task' 时使用
 * 
 * Streaming 模式：渲染 SubAgentPanel（展示子 agent 的思考/文本/工具执行过程）
 * 历史消息模式：渲染简单的折叠摘要
 */

import { Bot, ClipboardCopy, Check } from 'lucide-react';
import { useState } from 'react';
import type { ToolMessageProps } from './types';
import { CollapsibleSection } from './CollapsibleSection';
import { SubAgentPanel } from './SubAgentPanel';
import { formatTokenCount } from '@/utils/format';

/**
 * 从 task 工具参数中提取 agent name
 */
function extractAgentName(args: string): string {
  try {
    const parsed = JSON.parse(args);
    return parsed.agentType || parsed.subagentType || parsed.agent || parsed.name || 'unknown';
  } catch {
    return 'unknown';
  }
}

export function SubAgentToolMessage({ toolCall, result, status, subAgent }: ToolMessageProps) {
  const agentName = subAgent?.agentName || extractAgentName(toolCall.args);
  const isFinished = subAgent ? subAgent.isFinished : (status === 'COMPLETED' || status === 'FAILED');
  const isError = result?.isError ?? status === 'FAILED';

  // Extract error message from result
  const errorMessage = (result?.isError && result.result) ? result.result : undefined;

  // Extract inputData from args for both modes
  let inputData: Record<string, unknown> | string | undefined;
  try {
    const parsed = JSON.parse(toolCall.args);
    inputData = parsed.inputData;
  } catch { /* ignore */ }

  // Status display (matches BashToolMessage pattern)
  const statusText = !isFinished ? 'Running...' : isError ? 'Failed' : 'Completed';
  const statusColor = !isFinished
    ? 'text-muted-foreground'
    : isError ? 'text-destructive' : 'text-foreground';
  const statusDotColor = !isFinished
    ? 'bg-muted-foreground animate-pulse'
    : isError ? 'bg-destructive' : 'bg-green-500';

  // Streaming mode: render SubAgentPanel with live blocks
  if (subAgent) {
    return (
      <SubAgentPanel
        agentName={subAgent.agentName}
        toolCallId={subAgent.toolCallId}
        prompt={subAgent.prompt}
        inputData={inputData}
        blocks={subAgent.blocks}
        isFinished={subAgent.isFinished}
        streamingToolOutputs={subAgent.streamingToolOutputs}
        errorMessage={subAgent.errorMessage || errorMessage}
        accumulatedUsage={subAgent.accumulatedUsage}
        todos={subAgent.todos}
      />
    );
  }

  // Historical message mode: render collapsed summary

  // Extract result summary
  const resultSummary = (() => {
    if (!result) return null;
    const text = result.result || '';
    if (!text.trim()) return null;
    const truncated = text.trim().slice(0, 120);
    return truncated + (text.trim().length > 120 ? '...' : '');
  })();

  return (
    <CollapsibleSection
      defaultCollapsed={true}
      title={
        <div className="flex items-center gap-2 w-full">
          <Bot className="w-4 h-4 shrink-0 text-purple-400" />
          <span className="text-purple-400 truncate">SubAgent: {agentName}</span>
          <div className="flex-1" />
          <span className={`w-2 h-2 shrink-0 rounded-full ${statusDotColor}`} />
          <span className={`text-xs shrink-0 ${statusColor}`}>{statusText}</span>
        </div>
      }
    >
      <HistoricalContent
        toolCallArgs={toolCall.args}
        resultSummary={resultSummary}
        isError={!!isError}
        resultUsage={result?.usage}
      />
    </CollapsibleSection>
  );
}

/** Historical mode expanded content with inputData support */
function HistoricalContent({
  toolCallArgs,
  resultSummary,
  isError,
  resultUsage,
}: {
  toolCallArgs: string;
  resultSummary: string | null;
  isError: boolean;
  resultUsage?: { inputTokens: number; outputTokens: number; cacheReadTokens?: number };
}) {
  const [copied, setCopied] = useState(false);

  // Parse prompt and inputData from args
  let prompt: string | undefined;
  let inputData: unknown;
  try {
    const parsed = JSON.parse(toolCallArgs);
    prompt = parsed.prompt;
    inputData = parsed.inputData;
  } catch { /* ignore */ }

  const handleCopyInputData = async () => {
    if (!inputData) return;
    const text = typeof inputData === 'string' ? inputData : JSON.stringify(inputData, null, 2);
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="p-3 space-y-2">
      {/* Token usage from historical result */}
      {resultUsage && (resultUsage.inputTokens > 0 || resultUsage.outputTokens > 0) && (() => {
        const { inputTokens, outputTokens, cacheReadTokens } = resultUsage;
        const parts: string[] = [];
        if (inputTokens > 0) parts.push(`↑ ${formatTokenCount(inputTokens)}`);
        if (outputTokens > 0) parts.push(`↓ ${formatTokenCount(outputTokens)}`);
        if ((cacheReadTokens ?? 0) > 0) parts.push(`cache ${formatTokenCount(cacheReadTokens!)}`);
        return (
          <div className="text-xs text-muted-foreground">{parts.join(' ')}</div>
        );
      })()}
      {/* Agent prompt */}
      {prompt ? (
        <p className="text-sm text-muted-foreground">{prompt}</p>
      ) : (
        <p className="text-sm text-muted-foreground">{toolCallArgs}</p>
      )}
      {/* Input data indicator */}
      {!!inputData && (
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground/60">
          <button
            onClick={handleCopyInputData}
            className="hover:text-muted-foreground transition-colors"
            title="Copy input data"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-green-500" /> : <ClipboardCopy className="w-3.5 h-3.5" />}
          </button>
          <span>Input Data</span>
        </div>
      )}
      {/* Result summary */}
      {resultSummary && (
        <div className={isError
          ? "text-sm text-destructive pt-2 mt-2 border-t border-destructive/20 bg-destructive/5 p-2 rounded"
          : "text-sm text-muted-foreground"}>
          {resultSummary}
        </div>
      )}
    </div>
  );
}
