/**
 * Bash tool message rendering component.
 */

import { useState, useRef, useEffect, useCallback } from 'react';
import { Terminal, ChevronsDown, ChevronsUp, ChevronRight, ChevronDown } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { extractOutput, tryFormatJson } from './parsers';
import { getToolDisplayName } from './icons';
import { CodeBlock } from '../CodeBlock';

/**
 * Extract bash command from arguments.
 */
function extractBashCommand(args: string): string {
  try {
    const parsed = JSON.parse(args);
    if (parsed.command) {
      return parsed.command as string;
    }
  } catch {
    // ignore parse error
  }
  return args;
}

/** Default idle timeout (seconds) — must match BashTool's DEFAULT_TIMEOUT_SEC */
const DEFAULT_TIMEOUT_SEC = 200;

/**
 * Extract timeout seconds from arguments (LLM-provided timeout param, falls back to default).
 */
function extractTimeoutSec(args: string): number {
  try {
    const parsed = JSON.parse(args);
    if (typeof parsed.timeout === 'number' && parsed.timeout > 0) {
      return parsed.timeout;
    }
  } catch {
    // ignore parse error
  }
  return DEFAULT_TIMEOUT_SEC;
}

/**
 * Count the number of lines in text.
 */
function countLines(text: string): number {
  return text.split('\n').length;
}

/** Threshold (px) to determine if tool output is at the bottom */
const TOOL_SCROLL_BOTTOM_THRESHOLD = 30;

export function BashToolMessage({
  toolCall,
  result,
  status,
  streamingOutput
}: ToolMessageProps) {
  const displayCommand = extractBashCommand(toolCall.args);
  const timeoutSec = extractTimeoutSec(toolCall.args);
  const isStreaming = status === 'RUNNING' || status === 'PENDING';
  const output = extractOutput({ result, streamingOutput });
  const isError = (result?.isError ?? false) || status === 'FAILED';
  const outputLines = countLines(output);
  const commandLines = countLines(displayCommand);
  const [isExpanded, setIsExpanded] = useState(false);
  const [isCommandExpanded, setIsCommandExpanded] = useState(false);
  const [isOutputOverflowing, setIsOutputOverflowing] = useState(false);
  // Collapse entire content area after streaming ends for compact display
  const [contentCollapsed, setContentCollapsed] = useState(true);

  // Execution timer (ref: ThinkingBlock): update elapsed seconds every second during streaming
  const [elapsedSec, setElapsedSec] = useState(0);
  const startTimeRef = useRef<number | null>(null);

  useEffect(() => {
    if (isStreaming && startTimeRef.current === null) {
      startTimeRef.current = Date.now();
    }
    // Auto-expand content while streaming, auto-collapse when streaming ends
    if (isStreaming) {
      setContentCollapsed(false);
    }
  }, [isStreaming]);

  useEffect(() => {
    if (!isStreaming) return;
    const interval = setInterval(() => {
      if (startTimeRef.current !== null) {
        setElapsedSec(Math.floor((Date.now() - startTimeRef.current) / 1000));
      }
    }, 1000);
    return () => clearInterval(interval);
  }, [isStreaming]);

  // Tool output auto-scroll
  const outputRef = useRef<HTMLDivElement>(null);
  const toolAutoScrollEnabledRef = useRef(true);
  const prevScrollTopRef = useRef(0);

  /** Check if tool output is at the bottom */
  const isToolOutputAtBottom = useCallback(() => {
    const el = outputRef.current;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= TOOL_SCROLL_BOTTOM_THRESHOLD;
  }, []);

  /** Scroll tool output to bottom */
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

  // Scroll to bottom when command is expanded
  useEffect(() => {
    if (isCommandExpanded && toolAutoScrollEnabledRef.current) {
      requestAnimationFrame(() => {
        scrollToolOutputToBottom();
      });
    }
  }, [isCommandExpanded, scrollToolOutputToBottom]);

  // Detect actual overflow: only show "More" button when content is genuinely clipped
  useEffect(() => {
    if (isStreaming) return;
    const el = outputRef.current;
    if (!el) {
      setIsOutputOverflowing(false);
      return;
    }
    // Measure after layout settles
    requestAnimationFrame(() => {
      setIsOutputOverflowing(el.scrollHeight > el.clientHeight + 2);
    });
  }, [output, isStreaming, isExpanded]);

  const shouldShowCommandExpand = commandLines > 5;
  const shouldShowOutputExpand = isOutputOverflowing || isExpanded;

  const statusText = status === 'RUNNING'
    ? `Running... ${elapsedSec}s / ${timeoutSec}s`
    : status === 'PENDING'
      ? 'Pending...'
      : status === 'FAILED'
        ? 'Failed'
        : status === 'COMPLETED'
          ? 'Completed'
          : 'Running...';

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
      {/* Title bar — click to collapse/expand content */}
      <div
        className="p-3 flex items-center justify-between gap-2 border-b border-border cursor-pointer hover:bg-muted/30 transition-colors"
        onClick={() => setContentCollapsed(prev => !prev)}
      >
        <div className="flex items-center gap-2">
          {contentCollapsed
            ? <ChevronRight className="w-3.5 h-3.5 text-muted-foreground transition-transform" />
            : <ChevronDown className="w-3.5 h-3.5 text-muted-foreground transition-transform" />}
          <Terminal className="w-4 h-4 text-muted-foreground" />
          <span className="text-sm font-medium">{displayName}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
          <span className={`text-sm font-medium ${statusColor}`}>
            {statusText}
          </span>
        </div>
      </div>

      {/* Content area — hidden when collapsed */}
      {!contentCollapsed && (<>
      {/* Command area */}
      <div className="p-3 flex items-center">
        {(() => {
          const formattedCommand = tryFormatJson(displayCommand);
          if (formattedCommand) {
            return (
              <div className={`w-full overflow-hidden rounded-lg ${!isCommandExpanded && commandLines > 5 ? 'max-h-[5em] overflow-y-auto' : isCommandExpanded && commandLines > 15 ? 'max-h-[15em] overflow-y-auto' : ''}`}>
                <CodeBlock className="language-json">{formattedCommand}</CodeBlock>
              </div>
            );
          }
          return (
            <div className="text-sm font-mono text-muted-foreground break-all p-2 bg-muted rounded w-full overflow-hidden">
              <div
                className={`whitespace-pre-wrap ${!isCommandExpanded && commandLines > 5 ? 'max-h-[5em] overflow-y-auto' : isCommandExpanded && commandLines > 15 ? 'max-h-[15em] overflow-y-auto' : ''}`}
              >
                <span className="text-green-600">$</span> {displayCommand}
              </div>
            </div>
          );
        })()}
        {!isStreaming && shouldShowCommandExpand && (
          <button
            onClick={() => setIsCommandExpanded(!isCommandExpanded)}
            className="mt-2 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            {isCommandExpanded ? (
              <>
                <ChevronsUp className="w-3 h-3" />
                <span>Collapse</span>
              </>
            ) : (
              <>
                <ChevronsDown className="w-3 h-3" />
                <span>More ({commandLines} lines)</span>
              </>
            )}
          </button>
        )}
      </div>

      {/* Output area */}
      {(output || isStreaming || isError) && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            {(() => {
              const formattedOutput = !isStreaming ? tryFormatJson(output) : null;
              if (formattedOutput) {
                return (
                  <div
                    ref={outputRef}
                    onScroll={handleToolOutputScroll}
                    className={`${!isExpanded ? 'max-h-[10em]' : 'max-h-[30em]'} overflow-y-auto rounded-lg overflow-hidden`}
                  >
                    <CodeBlock className="language-json">{formattedOutput}</CodeBlock>
                  </div>
                );
              }
              return (
                <div
                  ref={outputRef}
                  onScroll={handleToolOutputScroll}
                  className={`text-sm font-mono whitespace-pre-wrap break-all ${isError ? 'text-destructive' : 'text-foreground'} ${!isExpanded ? 'max-h-[10em] overflow-y-auto' : 'max-h-[30em] overflow-y-auto'}`}
                >
                  {output || (isError ? `Command failed with exit code ${result?.exitCode ?? 1}` : '')}
                </div>
              );
            })()}
            {isStreaming && (
              <div className="mt-2 text-xs text-muted-foreground animate-pulse">
                running...
              </div>
            )}
            {!isStreaming && shouldShowOutputExpand && (
              <button
                onClick={() => setIsExpanded(!isExpanded)}
                className="mt-2 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
              >
                {isExpanded ? (
                  <>
                    <ChevronsUp className="w-3 h-3" />
                    <span>Collapse</span>
                  </>
                ) : (
                  <>
                    <ChevronsDown className="w-3 h-3" />
                    <span>More ({outputLines > 5 ? `${outputLines} lines` : `${output.length} chars`})</span>
                  </>
                )}
              </button>
            )}
          </div>
        </>
      )}
      </>
      )}
    </div>
  );
}