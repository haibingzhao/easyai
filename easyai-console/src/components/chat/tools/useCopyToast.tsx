/**
 * useCopyToast — 共享的复制提示 hook
 * 提供 copyToClipboard 方法和 Toast 渲染元素
 */

import { useState, useCallback, type ReactNode } from 'react';
import { Check } from 'lucide-react';

export function useCopyToast() {
  const [copiedText, setCopiedText] = useState<string | null>(null);

  const copyToClipboard = useCallback(async (text: string, e?: React.MouseEvent) => {
    e?.stopPropagation();
    try {
      await navigator.clipboard.writeText(text);
      setCopiedText(text);
      setTimeout(() => setCopiedText(null), 1500);
    } catch {
      // ignore
    }
  }, []);

  const toast: ReactNode = copiedText ? (
    <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-50 flex items-center gap-1.5 px-3 py-1.5 bg-foreground text-background text-xs rounded-md shadow-lg">
      <Check className="w-3.5 h-3.5" />
      Copied!
    </div>
  ) : null;

  return { copyToClipboard, toast };
}
