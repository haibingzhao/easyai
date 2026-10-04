/**
 * WebFetch tool message renderer component.
 * Compact single-line card: icon + label + URL + status indicator.
 * - Success: green checkmark
 * - Failure: red alert icon with error details on hover (native tooltip)
 * - Running: pulsing dot
 */

import { useState } from 'react';
import { Globe, CheckCircle2, AlertCircle } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { extractOutput, getToolRowSummary } from './parsers';
import { ToolTooltip } from '@/components/agent/ToolItem';
import { ToolRowHeader } from './ToolRowHeader';

/**
 * Extract URL from tool arguments
 */
function extractUrl(args: string): string {
  try {
    const parsed = JSON.parse(args);
    if (parsed.url) {
      return parsed.url as string;
    }
  } catch {
    // ignore parse error
  }
  return '';
}

export function WebFetchToolMessage({
  toolCall,
  result,
  status,
  streamingOutput,
  compact
}: ToolMessageProps) {
  const url = extractUrl(toolCall.args);
  const isStreaming = (status === 'RUNNING' || status === 'PENDING') && !result;
  const isError = (result?.isError ?? false) || status === 'FAILED';
  const errorOutput = isError ? extractOutput({ result, streamingOutput }) : '';

  const [errorOpen, setErrorOpen] = useState(false);

  if (compact) {
    const rowHeader = (
      <ToolRowHeader
        toolName={toolCall.toolName}
        status={status ?? 'PENDING'}
        summary={getToolRowSummary(toolCall.toolName, toolCall.args)}
        expanded={errorOpen}
        expandable={!!errorOutput}
        onToggle={() => setErrorOpen(prev => !prev)}
      />
    );
    if (!errorOutput) return rowHeader;
    return (
      <div className="overflow-hidden">
        {rowHeader}
        {errorOpen && (
          <div className="px-3 pb-2 text-sm font-mono whitespace-pre-wrap break-all text-destructive">
            {errorOutput}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      <div className="px-3 py-2 flex items-center gap-2">
        <Globe className="size-4 shrink-0 text-muted-foreground" />
        <span className="text-sm font-medium shrink-0">Web Fetch</span>
        <ToolTooltip name={url || toolCall.args} className="min-w-0 flex-1">
          <span className="block text-sm text-muted-foreground truncate">
            {url || toolCall.args}
          </span>
        </ToolTooltip>
        {/* Status indicator */}
        {isStreaming ? (
          <span className="size-2 shrink-0 rounded-full bg-muted-foreground animate-pulse" />
        ) : isError ? (
          <ToolTooltip description={errorOutput || 'Failed'}>
            <span className="shrink-0 cursor-help">
              <AlertCircle className="size-4 text-destructive" />
            </span>
          </ToolTooltip>
        ) : (
          <CheckCircle2 className="size-4 shrink-0 text-green-500" />
        )}
      </div>
    </div>
  );
}
