/**
 * Generic tool message renderer component (fallback).
 * Used for displaying unknown tool types.
 */

import { useRef, useEffect, useCallback } from 'react';
import { File, AlertTriangle } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { tryFormatJson } from './parsers';
import { CodeBlock } from '../CodeBlock';

/** Threshold (px) to determine if tool output is at the bottom */
const TOOL_SCROLL_BOTTOM_THRESHOLD = 30;

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
  streamingOutput 
}: ToolMessageProps) {
  const outputRef = useRef<HTMLDivElement>(null);
  const toolAutoScrollEnabledRef = useRef(true);
  const prevScrollTopRef = useRef(0);
  const isStreaming = status === 'RUNNING' || status === 'PENDING';
  
  /** Check if tool output is at the bottom */
  const isToolOutputAtBottom = useCallback(() => {
    const el = outputRef.current;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= TOOL_SCROLL_BOTTOM_THRESHOLD;
  }, []);

  /** Scroll tool output to the bottom */
  const scrollToolOutputToBottom = useCallback(() => {
    const el = outputRef.current;
    if (!el || !toolAutoScrollEnabledRef.current) return;
    prevScrollTopRef.current = el.scrollTop;
    el.scrollTo({
      top: el.scrollHeight,
      behavior: 'auto'
    });
  }, []);

  /** Handle tool output scroll event */
  const handleToolOutputScroll = useCallback(() => {
    const el = outputRef.current;
    if (!el) return;

    const currentScrollTop = el.scrollTop;
    const prevScrollTop = prevScrollTopRef.current;
    prevScrollTopRef.current = currentScrollTop;

    if (isStreaming) {
      if (currentScrollTop < prevScrollTop - 5) {
        toolAutoScrollEnabledRef.current = false;
      } else if (isToolOutputAtBottom() && !toolAutoScrollEnabledRef.current) {
        toolAutoScrollEnabledRef.current = true;
      }
      return;
    }

    toolAutoScrollEnabledRef.current = isToolOutputAtBottom();
  }, [isStreaming, isToolOutputAtBottom]);

  // Wheel event: immediately disable auto-scroll when user scrolls up during streaming
  useEffect(() => {
    const el = outputRef.current;
    if (!el) return;
    const handleWheel = (e: WheelEvent) => {
      if (isStreaming && e.deltaY < 0) {
        toolAutoScrollEnabledRef.current = false;
      }
    };
    el.addEventListener('wheel', handleWheel, { passive: true });
    return () => el.removeEventListener('wheel', handleWheel);
  }, [isStreaming]);

  // Auto-scroll tool output to bottom during streaming
  useEffect(() => {
    if (isStreaming && toolAutoScrollEnabledRef.current) {
      requestAnimationFrame(() => {
        scrollToolOutputToBottom();
      });
    }
  }, [streamingOutput, isStreaming, scrollToolOutputToBottom]);

  // When streaming ends, re-evaluate whether to enable auto-scroll based on current position
  useEffect(() => {
    if (!isStreaming) {
      toolAutoScrollEnabledRef.current = isToolOutputAtBottom();
    }
  }, [isStreaming, isToolOutputAtBottom]);
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

  const Icon = isUnknownTool ? AlertTriangle : File;
  const iconColor = isUnknownTool ? 'text-amber-500 dark:text-amber-400' : 'text-muted-foreground';

  return (
    <div className={`border ${borderColor} rounded-lg bg-card overflow-hidden`}>
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

      {/* Arguments */}
      {toolCall.args && (
        <div className="px-3 pb-3">
          {(() => {
            const formatted = tryFormatJson(toolCall.args);
            if (formatted) {
              return (
                <div className="max-h-40 overflow-y-auto rounded-lg overflow-hidden">
                  <CodeBlock className="language-json">{formatted}</CodeBlock>
                </div>
              );
            }
            return (
              <div className="text-sm font-mono text-muted-foreground break-all p-2 bg-muted rounded">
                {toolCall.args}
              </div>
            );
          })()}
        </div>
      )}

      {/* Output area */}
      {output && (
        <>
          <div className={`border-t ${borderColor}`} />
          <div className="p-3">
            {(() => {
              const formatted = tryFormatJson(output);
              if (formatted) {
                return (
                  <div
                    ref={outputRef}
                    onScroll={handleToolOutputScroll}
                    className="max-h-[15em] overflow-y-auto rounded-lg overflow-hidden"
                  >
                    <CodeBlock className="language-json">{formatted}</CodeBlock>
                  </div>
                );
              }
              return (
                <div
                  ref={outputRef}
                  onScroll={handleToolOutputScroll}
                  className={`text-sm font-mono whitespace-pre-wrap break-all max-h-[15em] overflow-y-auto ${
                    isUnknownTool ? 'text-amber-600 dark:text-amber-400' : 'text-foreground'
                  }`}
                >
                  {output}
                </div>
              );
            })()}
          </div>
        </>
      )}
    </div>
  );
}