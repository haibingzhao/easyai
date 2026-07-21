import React, { useState, useEffect } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { markdownCodeComponents } from './markdownCodeComponents';
import { i18n } from '../../utils/i18n';

/**
 * Normalize literal `\n` (two-char escape) to actual newlines.
 * Some LLM providers emit thinking content with escaped newlines instead of real ones,
 * causing ReactMarkdown to render everything as a single `<p>`.
 */
function normalizeNewlines(text: string): string {
  // Replace literal \n (backslash + n) with real newline,
  // but only when NOT preceded by another backslash (avoid breaking \\n).
  return text.replace(/(?<!\\)\\n/g, '\n');
}

/** Brain icon from kilo-code */
const BrainIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="square" className={className}>
    <path d="M13.332 8.7487C11.4911 8.7487 9.9987 7.25631 9.9987 5.41536M6.66536 11.2487C8.50631 11.2487 9.9987 12.7411 9.9987 14.582M9.9987 2.78209L9.9987 17.0658M16.004 15.0475C17.1255 14.5876 17.9154 13.4849 17.9154 12.1978C17.9154 11.3363 17.5615 10.5575 16.9913 9.9987C17.5615 9.43991 17.9154 8.66108 17.9154 7.79962C17.9154 6.21199 16.7136 4.90504 15.1702 4.73878C14.7858 3.21216 13.4039 2.08203 11.758 2.08203C11.1171 2.08203 10.5162 2.25337 9.9987 2.55275C9.48117 2.25337 8.88032 2.08203 8.23944 2.08203C6.59353 2.08203 5.21157 3.21216 4.82722 4.73878C3.28377 4.90504 2.08203 6.21199 2.08203 7.79962C2.08203 8.66108 2.43585 9.43991 3.00609 9.9987C2.43585 10.5575 2.08203 11.3363 2.08203 12.1978C2.08203 13.4849 2.87191 14.5876 3.99339 15.0475C4.46688 16.7033 5.9917 17.9154 7.79962 17.9154C8.61335 17.9154 9.36972 17.6698 9.9987 17.2488C10.6277 17.6698 11.384 17.9154 12.1978 17.9154C14.0057 17.9154 15.5305 16.7033 16.004 15.0475Z" stroke="currentColor"/>
  </svg>
);

interface ThinkingBlockProps {
  content: string;
  isStreaming?: boolean;
  isFinished?: boolean;
  /** Duration in milliseconds (for historical messages) */
  durationMs?: number;
}

export const ThinkingBlock: React.FC<ThinkingBlockProps> = ({ content, isStreaming, isFinished, durationMs }) => {
  // 历史消息（isStreaming=false/undefined）默认折叠
  // Streaming消息（isStreaming=true）默认展开
  const [expanded, setExpanded] = useState(isStreaming === true);
  const [elapsed, setElapsed] = useState(0);
  const startTimeRef = React.useRef<number | null>(null);

  // 开始计时
  useEffect(() => {
    if (isStreaming && !isFinished && startTimeRef.current === null) {
      startTimeRef.current = Date.now();
    }
  }, [isStreaming, isFinished]);

  // 实时更新耗时
  useEffect(() => {
    if (!isStreaming) return;
    if (isFinished) {
      // 结束时计算最终耗时
      if (startTimeRef.current !== null) {
        setElapsed(Math.floor((Date.now() - startTimeRef.current) / 1000));
      }
      return;
    }
    const interval = setInterval(() => {
      if (startTimeRef.current !== null) {
        setElapsed(Math.floor((Date.now() - startTimeRef.current) / 1000));
      }
    }, 1000);
    return () => clearInterval(interval);
  }, [isStreaming, isFinished]);

  useEffect(() => {
    if (isFinished) {
      setExpanded(false);
    }
  }, [isFinished]);

  const isCurrentlyStreaming = isStreaming && !isFinished;

  const formatDuration = (seconds: number): string => {
    if (seconds < 60) return `${seconds}s`;
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}m ${s}s`;
  };

  const showDuration = elapsed > 0 || (durationMs !== undefined && durationMs > 0);
  const durationText = showDuration ? formatDuration(durationMs !== undefined ? Math.floor(durationMs / 1000) : elapsed) : '';

  return (
    <div className="border-l-2 border-border bg-muted/50 rounded-l-md">
      <button
        onClick={() => setExpanded(!expanded)}
        className="group flex items-center gap-2 w-full px-3 py-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
      >
        <BrainIcon className="w-4 h-4 flex-shrink-0" />
        <span>{i18n('Thinking')}</span>
        {showDuration && (
          <span className="text-muted-foreground/60">· {durationText}</span>
        )}
        {isCurrentlyStreaming && (
          <div className="w-2 h-2 bg-muted-foreground animate-pulse rounded-full"></div>
        )}
        <span className="ml-auto">
          {expanded ? (
            <ChevronDown className="w-4 h-4" />
          ) : (
            <ChevronRight className="w-4 h-4 opacity-0 group-hover:opacity-100 transition-opacity" />
          )}
        </span>
      </button>
      
      {expanded && (
        <div className="px-3 pb-3 text-sm text-muted-foreground prose prose-sm dark:prose-invert max-w-none">
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
            {normalizeNewlines(content)}
          </ReactMarkdown>
        </div>
      )}
    </div>
  );
};
