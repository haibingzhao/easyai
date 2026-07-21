/**
 * McpToolCard - renders MCP tool call messages in chat.
 * MCP tool names follow the pattern "serverName__toolName".
 */

import { useState } from 'react';
import { ChevronDown, ChevronRight, Plug, AlertTriangle } from 'lucide-react';
import { CodeBlock } from '../CodeBlock';
import type { ToolMessageProps } from './types';
import { tryFormatJson } from './parsers';

export function McpToolCard({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);

  // Parse server + tool name from "serverName__toolName"
  const parts = toolCall.toolName.split('__');
  const serverName = parts.slice(0, -1).join('__').replace(/_/g, '-') || toolCall.toolName;
  const toolName = parts[parts.length - 1]?.replace(/_/g, '-') || toolCall.toolName;

  const output = streamingOutput ?? (() => {
    if (!result) return '';
    if (result.contentBlocks?.length) {
      return result.contentBlocks
        .map(b => (b.type === 'toolResult' ? b.output : b.type === 'text' ? b.text : ''))
        .join('');
    }
    return result.result ?? '';
  })();

  const isRunning = status === 'RUNNING' || status === 'PENDING';
  const isFailed = status === 'FAILED' || result?.isError;

  const statusDotColor = isRunning
    ? 'bg-blue-500 animate-pulse'
    : isFailed
      ? 'bg-destructive'
      : 'bg-green-500';

  const statusText = isRunning ? '运行中...' : isFailed ? '失败' : '完成';

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* Header */}
      <div className="p-3 flex items-center justify-between gap-2 border-b border-border">
        <div className="flex items-center gap-2 min-w-0">
          <Plug className="w-3.5 h-3.5 text-muted-foreground flex-shrink-0" />
          <span className="text-xs text-muted-foreground flex-shrink-0">{serverName}</span>
          <span className="text-muted-foreground">/</span>
          <span className="text-sm font-medium truncate">{toolName}</span>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
          <span className={`text-xs font-medium ${isFailed ? 'text-destructive' : 'text-muted-foreground'}`}>
            {statusText}
          </span>
        </div>
      </div>

      {/* Args (collapsed by default) */}
      {toolCall.args && toolCall.args !== '{}' && (
        <div className="px-3 py-2">
          {(() => {
            const formatted = tryFormatJson(toolCall.args);
            if (formatted) {
              return (
                <div className="max-h-32 overflow-y-auto rounded-lg overflow-hidden">
                  <CodeBlock className="language-json">{formatted}</CodeBlock>
                </div>
              );
            }
            return (
              <div className="text-xs font-mono text-muted-foreground bg-muted/50 rounded p-2 break-all line-clamp-2">
                {toolCall.args}
              </div>
            );
          })()}
        </div>
      )}

      {/* Output */}
      {output && (
        <div className="border-t border-border">
          <button
            onClick={() => setExpanded(e => !e)}
            className="w-full flex items-center gap-1.5 px-3 py-1.5 text-xs text-muted-foreground hover:bg-muted/30 transition-colors"
          >
            {expanded
              ? <ChevronDown className="w-3.5 h-3.5" />
              : <ChevronRight className="w-3.5 h-3.5" />}
            {isFailed && <AlertTriangle className="w-3 h-3 text-destructive" />}
            <span>{expanded ? '收起结果' : '查看结果'}</span>
          </button>
          {expanded && (
            <div className="px-3 pb-3">
              {(() => {
                const formatted = tryFormatJson(output);
                if (formatted) {
                  return (
                    <div className="max-h-80 overflow-y-auto rounded-lg overflow-hidden">
                      <CodeBlock className="language-json">{formatted}</CodeBlock>
                    </div>
                  );
                }
                return (
                  <div className={`text-xs font-mono whitespace-pre-wrap break-all max-h-60 overflow-y-auto ${
                    isFailed ? 'text-destructive' : 'text-foreground'
                  }`}>
                    {output}
                  </div>
                );
              })()}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
