/**
 * Generic tool message renderer component (fallback).
 * Used for displaying unknown tool types.
 */

import { useRef, useEffect, useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { getToolRowSummary } from './parsers';
import { ToolRowHeader } from './ToolRowHeader';
import { ToolSection } from './ToolSection';
import { getToolIcon } from './icons';
import { i18n } from '@/utils/i18n';

/**
 * Detects whether this is an "Unknown tool" error
 * by checking if the output contains the "Unknown tool" keyword
 */
function isUnknownToolError(output: string, isError: boolean): boolean {
  return isError && output.toLowerCase().includes('unknown tool');
}

export function GenericToolMessage({
  toolCall,
  result,
  status,
  streamingOutput,
  compact
}: ToolMessageProps) {
  const [isCollapsed, setIsCollapsed] = useState(true);
  const userTouchedRef = useRef(false);
  const isStreaming = status === 'RUNNING' || status === 'PENDING';

  // Get output content
  const output = streamingOutput ?? (() => {
    if (!result) return '';
    if (result.contentBlocks && result.contentBlocks.length > 0) {
      return result.contentBlocks
        .map((block) => {
          if (block.type === 'toolResult') return block.output;
          if (block.type === 'text') return block.text;
          return '';
        })
        .join('');
    }
    return result.result;
  })();

  const isUnknownTool = status === 'FAILED' && isUnknownToolError(output, result?.isError ?? false);

  const statusText = isUnknownTool
    ? 'Unknown Tool'
    : status === 'RUNNING'
      ? 'Running...'
      : status === 'PENDING'
        ? 'Pending...'
        : status === 'FAILED'
          ? 'Failed'
          : status === 'COMPLETED'
            ? 'Completed'
            : 'Running...';

  const statusColor = isUnknownTool
    ? 'text-amber-500 dark:text-amber-400'
    : status === 'RUNNING' || status === 'PENDING'
      ? 'text-muted-foreground'
      : status === 'FAILED'
        ? 'text-destructive'
        : 'text-foreground';

  const statusDotColor = isUnknownTool
    ? 'bg-amber-500'
    : status === 'RUNNING' || status === 'PENDING'
      ? 'bg-muted-foreground animate-pulse'
      : status === 'FAILED'
        ? 'bg-destructive'
        : 'bg-green-500';

  const borderColor = isUnknownTool
    ? 'border-amber-500/50'
    : 'border-border';

  const Icon = isUnknownTool ? AlertTriangle : getToolIcon(toolCall.toolName);
  const iconColor = isUnknownTool ? 'text-amber-500 dark:text-amber-400' : 'text-muted-foreground';

  useEffect(() => {
    if (userTouchedRef.current) return;
    setIsCollapsed(!isStreaming);
  }, [isStreaming]);

  const hasArgs = Boolean(toolCall.args);

  const rowHeader = (
    <ToolRowHeader
      toolName={toolCall.toolName}
      status={status ?? 'PENDING'}
      summary={getToolRowSummary(toolCall.toolName, toolCall.args)}
      expanded={!isCollapsed}
      onToggle={() => { userTouchedRef.current = true; setIsCollapsed(prev => !prev); }}
    />
  );

  if (compact && isCollapsed) return rowHeader;

  return (
    <div className={compact ? 'overflow-hidden' : `border ${borderColor} rounded-lg bg-card overflow-hidden`}>
      {compact ? rowHeader : (
      <>
      {/* Title bar */}
      <div className={`p-3 flex items-center justify-between gap-2 border-b ${borderColor}`}>
        <div className="flex items-center gap-2">
          <Icon className={`w-4 h-4 ${iconColor}`} />
          <span className="text-sm font-medium">{toolCall.toolName}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
          <span className={`text-sm font-medium ${statusColor}`}>
            {statusText}
          </span>
        </div>
      </div>
      </>
      )}

      <div className={`space-y-3 ${compact ? 'px-3 pb-3' : 'p-3'}`}>
        {hasArgs && (
          <ToolSection title={i18n('Arguments')} text={toolCall.args} maxHeightClass="max-h-40" />
        )}
        {output && (
          <ToolSection
            title={i18n('Result')}
            text={output}
            maxHeightClass="max-h-[15em]"
            followStreaming={isStreaming}
            tone={isUnknownTool ? 'error' : 'default'}
          />
        )}
      </div>
    </div>
  );
}
