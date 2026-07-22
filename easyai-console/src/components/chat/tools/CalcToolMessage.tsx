/**
 * Calc tool message renderer component.
 * Displays Groovy script content (Shiki syntax highlighting) and execution result (or error).
 * Supports collapse/expand: collapsed by default when completed, expanded while streaming.
 */

import { useState, useEffect } from 'react';
import { Calculator, AlertCircle, ChevronDown, ChevronRight, AlertTriangle } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { extractOutput } from './parsers';
import { getToolDisplayName } from './icons';
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
  streamingOutput
}: ToolMessageProps) {
  const script = extractScript(toolCall.args);
  // Once result is committed the tool is finished, regardless of status field
  const isStreaming = (status === 'RUNNING' || status === 'PENDING') && !result;
  const isError = (result?.isError ?? false) || status === 'FAILED';
  const output = extractOutput({ result, streamingOutput });

  // Expand script while streaming, collapse by default when completed
  const [expanded, setExpanded] = useState(isStreaming);
  // Result area collapsed by default (ref: MCP component: view/collapse result), auto-expand on error
  const [resultExpanded, setResultExpanded] = useState(isError);

  // Auto-expand script when streaming state changes
  useEffect(() => {
    if (isStreaming) setExpanded(true);
  }, [isStreaming]);

  // Auto-expand result area on error
  useEffect(() => {
    if (isError) setResultExpanded(true);
  }, [isError]);

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

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* Title bar — click to collapse/expand */}
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

      {/* Expanded content */}
      {expanded && (
        <>
          <div className="border-t border-border" />

          {/* Script area (Shiki highlighted) */}
          <div className="p-3">
            <div className="text-sm font-mono p-2 bg-muted rounded overflow-x-auto max-h-[20em] overflow-y-auto">
              <div
                className="whitespace-pre-wrap break-all calc-code-highlight"
                dangerouslySetInnerHTML={{ __html: highlighted || plainTextHtml }}
              />
            </div>
          </div>

          {/* Result area (ref: MCP interaction: view/collapse result) */}
          {(output || isStreaming) && (
            <div className="border-t border-border">
              {isStreaming ? (
                <div className="px-3 py-2 text-xs text-muted-foreground animate-pulse">
                  calculating...
                </div>
              ) : (
                <>
                  <button
                    type="button"
                    onClick={() => setResultExpanded(e => !e)}
                    className="w-full flex items-center gap-1.5 px-3 py-1.5 text-xs text-muted-foreground hover:bg-muted/30 transition-colors"
                  >
                    {resultExpanded
                      ? <ChevronDown className="w-3.5 h-3.5" />
                      : <ChevronRight className="w-3.5 h-3.5" />}
                    {isError && <AlertTriangle className="w-3 h-3 text-destructive" />}
                    <span>{resultExpanded ? '收起结果' : '查看结果'}</span>
                  </button>
                  {resultExpanded && (
                    <div className={`px-3 pb-3 text-sm font-mono whitespace-pre-wrap break-all max-h-60 overflow-y-auto ${
                      isError ? 'text-destructive' : 'text-foreground'
                    }`}>
                      {isError && <AlertCircle className="w-4 h-4 inline-block mr-1 mb-0.5" />}
                      {output}
                    </div>
                  )}
                </>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
