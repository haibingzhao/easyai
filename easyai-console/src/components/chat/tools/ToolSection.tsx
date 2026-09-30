/**
 * ToolSection — 工具详情共享区块（参数/结果）
 * 标题 + hover 复制按钮 + 可滚动全文内容（JSON 自动格式化高亮）。
 * followStreaming 模式下内容自动吸底，用户向上滚动即暂停跟随。
 */

import { useRef, useEffect, useCallback, type ReactNode } from 'react';
import { Copy } from 'lucide-react';
import { CodeBlock } from '../CodeBlock';
import { tryFormatJson } from './parsers';
import { useCopyToast } from './useCopyToast';
import { i18n } from '@/utils/i18n';

/** Threshold (px) to determine if the scroll container is at the bottom */
const SCROLL_BOTTOM_THRESHOLD = 30;

interface ToolSectionProps {
  title: string;
  /** Displayed text; JSON is auto-detected and highlighted */
  text: string;
  /** Raw content written to clipboard; defaults to text */
  copyText?: string;
  /** Max-height utility class of the scrollable body */
  maxHeightClass?: string;
  /** Keep the body pinned to the bottom as text grows (streaming output) */
  followStreaming?: boolean;
  tone?: 'default' | 'error';
  /** Custom body (e.g. pre-highlighted HTML); replaces the default text/JSON rendering */
  children?: ReactNode;
}

export function ToolSection({
  title,
  text,
  copyText,
  maxHeightClass = 'max-h-[15em]',
  followStreaming = false,
  tone = 'default',
  children,
}: ToolSectionProps) {
  const { copyToClipboard, toast } = useCopyToast();
  const scrollRef = useRef<HTMLDivElement>(null);
  const autoScrollRef = useRef(true);
  const prevScrollTopRef = useRef(0);

  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (el.scrollTop < prevScrollTopRef.current - 5) {
      autoScrollRef.current = false;
    } else if (el.scrollHeight - el.scrollTop - el.clientHeight <= SCROLL_BOTTOM_THRESHOLD) {
      autoScrollRef.current = true;
    }
    prevScrollTopRef.current = el.scrollTop;
  }, []);

  useEffect(() => {
    if (!followStreaming || !autoScrollRef.current) return;
    const el = scrollRef.current;
    if (!el) return;
    prevScrollTopRef.current = el.scrollTop;
    el.scrollTop = el.scrollHeight;
  }, [text, followStreaming]);

  const formatted = tryFormatJson(text);

  return (
    <div className="group/tool-section">
      <div className="flex items-center justify-between gap-2 pb-1">
        <span className="text-xs text-muted-foreground">{title}</span>
        <button
          type="button"
          title={i18n('Copy')}
          onClick={(e) => copyToClipboard(copyText ?? text, e)}
          className="opacity-0 group-hover/tool-section:opacity-100 transition-opacity p-0.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
        >
          <Copy className="w-3 h-3" />
        </button>
      </div>
      {children ?? (formatted ? (
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className={`${maxHeightClass} overflow-y-auto rounded-lg overflow-hidden`}
        >
          <CodeBlock className="language-json">{formatted}</CodeBlock>
        </div>
      ) : (
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className={`${maxHeightClass} overflow-y-auto text-xs font-mono whitespace-pre-wrap break-all rounded p-2 bg-muted/50 ${
            tone === 'error' ? 'text-destructive' : 'text-muted-foreground'
          }`}
        >
          {text}
        </div>
      ))}
      {toast}
    </div>
  );
}
