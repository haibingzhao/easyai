/**
 * Calc tool message renderer component.
 * Displays Groovy script content (Shiki syntax highlighting) and execution result (or error).
 * Supports collapse/expand: collapsed by default when completed, expanded while streaming.
 */

import { useState, useEffect } from 'react';
import { Calculator, ChevronDown } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { extractOutput, getToolRowSummary } from './parsers';
import { getToolDisplayName } from './icons';
import { ToolRowHeader } from './ToolRowHeader';
import { ToolSection } from './ToolSection';
import { i18n } from '@/utils/i18n';
import {
  getShikiHighlighter,
  getCachedHighlight,
  setCachedHighlight,
  stripPreCodeTransformer,
} from '@/utils/shiki-utils';

/**
 * Extract script content from arguments
 */
function extractScript(args: string): string {
  try {
    const parsed = JSON.parse(args);
    if (parsed.script) {
      return parsed.script as string;
    }
  } catch {
    // ignore parse error
  }
  return args;
}

export function CalcToolMessage({
  toolCall,
  result,
  status,
  streamingOutput,
  compact
}: ToolMessageProps) {
  const script = extractScript(toolCall.args);
  // Once result is committed the tool is finished, regardless of status field
  const isStreaming = (status === 'RUNNING' || status === 'PENDING') && !result;
  const isError = (result?.isError ?? false) || status === 'FAILED';
  const output = extractOutput({ result, streamingOutput });

  // Expand script while streaming, collapse by default when completed
  const [expanded, setExpanded] = useState(isStreaming);

  // Auto-expand script when streaming state changes
  useEffect(() => {
    if (isStreaming) setExpanded(true);
  }, [isStreaming]);

  // Shiki highlighting
  const [highlighted, setHighlighted] = useState('');

  useEffect(() => {
    if (!script) {
      setHighlighted('');
      return;
    }

    const lang = 'groovy';
    const cached = getCachedHighlight(script, lang);
    if (cached !== undefined) {
      setHighlighted(cached);
      return;
    }

    let mounted = true;
    const highlight = async () => {
      try {
        const h = await getShikiHighlighter();
        if (!mounted) return;
        const html = h.codeToHtml(script, {
          lang,
          theme: 'github-dark',
          transformers: [stripPreCodeTransformer],
        });
        if (mounted) {
          setHighlighted(html);
          setCachedHighlight(script, lang, html);
        }
      } catch {
        // Shiki error — fall back to plain text
      }
    };
    highlight();
    return () => { mounted = false; };
  }, [script]);

  // Plain-text fallback while Shiki loads
  const plainTextHtml = script
    .split('\n')
    .map((line) => `<span class="line">${line.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') || ' '}</span>`)
    .join('\n');

  const statusText = status === 'RUNNING'
    ? 'Calculating...'
    : status === 'PENDING'
      ? 'Pending...'
      : status === 'FAILED'
        ? 'Error'
        : status === 'COMPLETED'
          ? 'Completed'
          : 'Calculating...';

  const statusColor = status === 'RUNNING' || status === 'PENDING'
    ? 'text-muted-foreground'
    : status === 'FAILED'
      ? 'text-destructive'
      : 'text-foreground';

  const statusDotColor = status === 'RUNNING' || status === 'PENDING'
    ? 'bg-muted-foreground animate-pulse'
    : status === 'FAILED'
      ? 'bg-destructive'
      : 'bg-green-500';

  const displayName = getToolDisplayName(toolCall.toolName);

  const rowHeader = (
    <ToolRowHeader
      toolName={toolCall.toolName}
      status={status ?? 'PENDING'}
      summary={getToolRowSummary(toolCall.toolName, toolCall.args)}
      expanded={expanded}
      onToggle={() => setExpanded(!expanded)}
    />
  );

  if (compact && !expanded) return rowHeader;

  return (
    <div className={compact ? 'overflow-hidden' : 'border border-border rounded-lg bg-card overflow-hidden'}>
      {/* Title bar — click to collapse/expand */}
      {compact ? rowHeader : (
      <div
        className="p-3 flex items-center justify-between gap-2 cursor-pointer hover:bg-muted/50 transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="flex items-center gap-2">
          <Calculator className="w-4 h-4 text-muted-foreground" />
          <span className="text-sm font-medium">{displayName}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
          <span className={`text-sm font-medium ${statusColor}`}>
            {statusText}
          </span>
          <ChevronDown
            className={`size-4 text-muted-foreground transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`}
          />
        </div>
      </div>
      )}

      {/* Expanded content */}
      {expanded && (
        <div className={`space-y-3 ${compact ? 'px-3 pb-3' : 'p-3 border-t border-border'}`}>
          {/* Script area (Shiki highlighted) */}
          {script && (
            <ToolSection title={i18n('Script')} text={script} maxHeightClass="max-h-[20em]">
              <div className="text-sm font-mono p-2 bg-muted rounded overflow-x-auto max-h-[20em] overflow-y-auto">
                <div
                  className="whitespace-pre-wrap break-all calc-code-highlight"
                  dangerouslySetInnerHTML={{ __html: highlighted || plainTextHtml }}
                />
              </div>
            </ToolSection>
          )}

          {/* Result area */}
          {isStreaming ? (
            <div className="px-1 py-2 text-xs text-muted-foreground animate-pulse">
              calculating...
            </div>
          ) : output ? (
            <ToolSection
              title={i18n('Result')}
              text={output}
              maxHeightClass="max-h-60"
              tone={isError ? 'error' : 'default'}
            />
          ) : null}
        </div>
      )}
    </div>
  );
}
