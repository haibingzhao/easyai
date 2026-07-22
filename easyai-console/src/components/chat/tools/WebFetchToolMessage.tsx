/**
 * WebFetch tool message renderer component.
 * Compact single-line card: icon + label + URL + status indicator.
 * - Success: green checkmark
 * - Failure: red alert icon with error details on hover (native tooltip)
 * - Running: pulsing dot
 */

import { Globe, CheckCircle2, AlertCircle } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { extractOutput } from './parsers';

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
  streamingOutput
}: ToolMessageProps) {
  const url = extractUrl(toolCall.args);
  const isStreaming = (status === 'RUNNING' || status === 'PENDING') && !result;
  const isError = (result?.isError ?? false) || status === 'FAILED';
  const errorOutput = isError ? extractOutput({ result, streamingOutput }) : '';

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      <div className="px-3 py-2 flex items-center gap-2">
        <Globe className="size-4 shrink-0 text-muted-foreground" />
        <span className="text-sm font-medium shrink-0">Web Fetch</span>
        <span
          className="text-sm text-muted-foreground truncate min-w-0 flex-1"
          title={url || undefined}
        >
          {url || toolCall.args}
        </span>
        {/* Status indicator */}
        {isStreaming ? (
          <span className="size-2 shrink-0 rounded-full bg-muted-foreground animate-pulse" />
        ) : isError ? (
          <span className="shrink-0 cursor-help" title={errorOutput || 'Failed'}>
            <AlertCircle className="size-4 text-destructive" />
          </span>
        ) : (
          <CheckCircle2 className="size-4 shrink-0 text-green-500" />
        )}
      </div>
    </div>
  );
}
