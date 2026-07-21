/**
 * Bash工具消息渲染组件
 */

import { useState, useRef, useEffect, useCallback } from 'react';
import { Terminal, ChevronsDown, ChevronsUp } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { extractOutput } from './parsers';
import { getToolDisplayName } from './icons';

/**
 * 从参数中提取bash命令
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

/**
 * 计算文本的行数
 */
function countLines(text: string): number {
  return text.split('\n').length;
}

/** 判定工具输出是否在底部的阈值（px） */
const TOOL_SCROLL_BOTTOM_THRESHOLD = 30;

export function BashToolMessage({
  toolCall,
  result,
  status,
  streamingOutput
}: ToolMessageProps) {
  const displayCommand = extractBashCommand(toolCall.args);
  const isStreaming = status === 'RUNNING' || status === 'PENDING';
  const output = extractOutput({ result, streamingOutput });
  const isError = (result?.isError ?? false) || status === 'FAILED';
  const outputLines = countLines(output);
  const commandLines = countLines(displayCommand);
  const [isExpanded, setIsExpanded] = useState(false);
  const [isCommandExpanded, setIsCommandExpanded] = useState(false);

  // 工具输出自动滚动相关
  const outputRef = useRef<HTMLDivElement>(null);
  const toolAutoScrollEnabledRef = useRef(true);
  const prevScrollTopRef = useRef(0);

  /** 检查工具输出是否在底部 */
  const isToolOutputAtBottom = useCallback(() => {
    const el = outputRef.current;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= TOOL_SCROLL_BOTTOM_THRESHOLD;
  }, []);

  /** 滚动工具输出到底部 */
  const scrollToolOutputToBottom = useCallback(() => {
    const el = outputRef.current;
    if (!el || !toolAutoScrollEnabledRef.current) return;
    prevScrollTopRef.current = el.scrollTop;
    el.scrollTo({
      top: el.scrollHeight,
      behavior: 'auto'
    });
  }, []);

  /** 处理工具输出滚动事件 */
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

  // wheel事件：streaming期间用户向上滚轮时立即禁用自动滚动
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

  // streaming时自动滚动工具输出到底部
  useEffect(() => {
    if (isStreaming && toolAutoScrollEnabledRef.current) {
      requestAnimationFrame(() => {
        scrollToolOutputToBottom();
      });
    }
  }, [streamingOutput, isStreaming, scrollToolOutputToBottom]);

  // streaming结束时，根据当前位置重新判断是否启用自动滚动
  useEffect(() => {
    if (!isStreaming) {
      toolAutoScrollEnabledRef.current = isToolOutputAtBottom();
    }
  }, [isStreaming, isToolOutputAtBottom]);

  // 命令展开时滚动到底部
  useEffect(() => {
    if (isCommandExpanded && toolAutoScrollEnabledRef.current) {
      requestAnimationFrame(() => {
        scrollToolOutputToBottom();
      });
    }
  }, [isCommandExpanded, scrollToolOutputToBottom]);

  const shouldShowCommandExpand = commandLines > 5;
  const shouldShowOutputExpand = outputLines > 5;

  const statusText = status === 'RUNNING'
    ? 'Running...'
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
      {/* 标题栏 */}
      <div className="p-3 flex items-center justify-between gap-2 border-b border-border">
        <div className="flex items-center gap-2">
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

      {/* 命令区域 */}
      <div className="p-3 flex items-center">
        <div className="text-sm font-mono text-muted-foreground break-all p-2 bg-muted rounded w-full overflow-hidden">
          <div
            className={`whitespace-pre-wrap ${!isCommandExpanded && commandLines > 5 ? 'max-h-[5em] overflow-y-auto' : isCommandExpanded && commandLines > 15 ? 'max-h-[15em] overflow-y-auto' : ''}`}
          >
            <span className="text-green-600">$</span> {displayCommand}
          </div>
          {!isStreaming && shouldShowCommandExpand && (
            <button
              onClick={() => setIsCommandExpanded(!isCommandExpanded)}
              className="mt-2 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
            >
              {isCommandExpanded ? (
                <>
                  <ChevronsUp className="w-3 h-3" />
                  <span>收起</span>
                </>
              ) : (
                <>
                  <ChevronsDown className="w-3 h-3" />
                  <span>更多 ({commandLines} 行)</span>
                </>
              )}
            </button>
          )}
        </div>
      </div>

      {/* 输出区域 */}
      {(output || isStreaming) && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <div
              ref={outputRef}
              onScroll={handleToolOutputScroll}
              className={`text-sm font-mono whitespace-pre-wrap break-all ${isError ? 'text-destructive' : 'text-foreground'} ${!isExpanded && outputLines > 5 ? 'max-h-[5em] overflow-y-auto' : isExpanded && outputLines > 15 ? 'max-h-[15em] overflow-y-auto' : ''}`}
            >
              {output}
            </div>
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
                    <span>收起</span>
                  </>
                ) : (
                  <>
                    <ChevronsDown className="w-3 h-3" />
                    <span>更多 ({outputLines} 行)</span>
                  </>
                )}
              </button>
            )}
          </div>
        </>
      )}
    </div>
  );
}