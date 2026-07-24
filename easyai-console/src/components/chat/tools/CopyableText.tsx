/**
 * CopyableText — 可点击复制/打开文件的文本组件
 * 支持前缀截断（ellipsis 在开头），点击复制到剪贴板或打开文件预览
 *
 * 实现原理（三层结构）：
 * 1. 外层 span — 携带 title/onClick，direction:ltr，确保 tooltip 正确渲染
 * 2. 内层 span — direction:rtl + truncate，使 ellipsis 出现在左侧（前缀截断）
 * 3. <bdi> — direction:ltr + unicode-bidi:isolate，确保文本内容以 LTR 顺序正确渲染
 *
 * 当提供 onFileOpen 时：
 * - 点击文本 → 打开文件预览
 * - hover 时显示复制图标按钮，点击复制路径到剪贴板
 *
 * 当不提供 onFileOpen 时：
 * - 点击文本 → 复制到剪贴板（原有行为）
 *
 * 注意：
 * - 不能直接在外层 span 上设置 direction:rtl，否则 WebKit 浏览器的
 *   原生 tooltip 也会以 RTL 方向渲染，导致路径中的斜杠位置错乱。
 * - 外层 span 设置 flex-1 min-w-0，确保在 flex 容器内既能随父级宽度收缩，
 *   也能在窗口变宽时自动扩展，从而只在真正空间不足时才触发前缀截断。
 */

import React from 'react';
import { Copy } from 'lucide-react';

interface CopyableTextProps {
  text: string;
  /** Actual content written to clipboard on copy click; defaults to text if not provided */
  copyText?: string;
  title?: string;
  className?: string;
  onCopy: (text: string, e: React.MouseEvent) => void;
  /** When provided, clicking the text opens the file instead of copying */
  onFileOpen?: () => void;
}

export const CopyableText: React.FC<CopyableTextProps> = ({ text, copyText, title, className = '', onCopy, onFileOpen }) => {
  const handleClick = (e: React.MouseEvent) => {
    if (onFileOpen) {
      e.stopPropagation();
      onFileOpen();
    } else {
      onCopy(copyText ?? text, e);
    }
  };

  return (
    <span
      className={`group block flex-1 min-w-0 cursor-pointer hover:text-foreground transition-colors ${className}`}
      title={title ?? (onFileOpen ? `Click to open: ${text}` : `Click to copy: ${text}`)}
      onClick={handleClick}
    >
      <span className="flex items-center gap-1">
        <span className="block truncate [direction:rtl] [text-align:left] flex-1 min-w-0">
          <bdi className="[direction:ltr]">{text}</bdi>
        </span>
        {onFileOpen && (
          <button
            type="button"
            className="opacity-0 group-hover:opacity-100 transition-opacity shrink-0 p-0.5 rounded hover:bg-muted text-muted-foreground"
            onClick={(e) => { e.stopPropagation(); onCopy(copyText ?? text, e); }}
            title="Copy path"
          >
            <Copy className="w-3 h-3" />
          </button>
        )}
      </span>
    </span>
  );
};