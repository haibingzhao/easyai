/**
 * McpToolCard - renders MCP tool call messages in chat.
 * MCP tool names follow the pattern "serverName__toolName".
 */

import { useState, useEffect, useRef } from 'react';
import { AlertTriangle } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { getToolRowSummary } from './parsers';
import { ToolRowHeader } from './ToolRowHeader';
import { ToolSection } from './ToolSection';
import { getToolIcon } from './icons';
import { i18n } from '@/utils/i18n';

export function McpToolCard({ toolCall, result, status, streamingOutput, compact }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const userTouchedRef = useRef(false);

  // Parse server + tool name from "serverName__toolName"
  const parts = toolCall.toolName.split('__');
  const serverName = parts.slice(0, -1).join('__').replace(/_/g, '-') || toolCall.toolName;
  const toolName = parts[parts.length - 1]?.replace(/_/g, '-') || toolCall.toolName;

  const Icon = getToolIcon(toolCall.toolName);

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

  const statusText = isRunning ? i18n('Running...') : isFailed ? i18n('Failed') : i18n('Completed');

  useEffect(() => {
    if (userTouchedRef.current) return;
    setExpanded(isRunning);
  }, [isRunning]);

  const hasArgs = Boolean(toolCall.args && toolCall.args !== '{}');

  const rowHeader = (
    <ToolRowHeader
      toolName={toolCall.toolName}
      status={status ?? 'PENDING'}
      summary={getToolRowSummary(toolCall.toolName, toolCall.args)}
      expanded={expanded}
      onToggle={() => { userTouchedRef.current = true; setExpanded(e => !e); }}
    />
  );

  if (compact) {
    return (
      <div className="overflow-hidden">
        {rowHeader}
        {expanded && (hasArgs || output || isFailed) && (
          <div className="px-3 pb-2 space-y-2">
            {isFailed && (
              <div className="flex items-center gap-1.5 text-xs text-destructive">
                <AlertTriangle className="w-3 h-3" />
                <span>{i18n('Failed')}</span>
              </div>
            )}
            {hasArgs && (
              <ToolSection title={i18n('Arguments')} text={toolCall.args} maxHeightClass="max-h-32" />
            )}
            {output && (
              <ToolSection
                title={i18n('Result')}
                text={output}
                maxHeightClass="max-h-60"
                followStreaming={isRunning}
                tone={isFailed ? 'error' : 'default'}
              />
            )}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* Header */}
      <div className="p-3 flex items-center justify-between gap-2 border-b border-border">
        <div className="flex items-center gap-2 min-w-0">
          <Icon className="w-3.5 h-3.5 text-muted-foreground flex-shrink-0" />
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

      <div className="p-3 space-y-3">
        {isFailed && (
          <div className="flex items-center gap-1.5 text-xs text-destructive">
            <AlertTriangle className="w-3 h-3" />
            <span>{i18n('Failed')}</span>
          </div>
        )}
        {hasArgs && (
          <ToolSection title={i18n('Arguments')} text={toolCall.args} maxHeightClass="max-h-32" />
        )}
        {output && (
          <ToolSection
            title={i18n('Result')}
            text={output}
            maxHeightClass="max-h-80"
            followStreaming={isRunning}
            tone={isFailed ? 'error' : 'default'}
          />
        )}
      </div>
    </div>
  );
}
