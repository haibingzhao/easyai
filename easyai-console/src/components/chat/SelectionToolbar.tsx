import React, { useState, useEffect, useCallback } from 'react';
import { Copy, Check, MessageSquareQuote, Quote } from 'lucide-react';
import { useSideAskStore } from '@/services/stores/side-ask-store';
import { useCopyToast } from './tools/useCopyToast';
import { i18n } from '@/utils/i18n';

const TOOLBAR_GAP_ABOVE = 8;
const TOOLBAR_HEIGHT = 32;
const HALF_WIDTH = 90;

interface SelectionToolbarProps {
  containerRef: React.RefObject<HTMLDivElement | null>;
}

/**
 * Floating toolbar shown above a text selection inside the message list.
 * Offers copy / ask-in-side-panel / quote-to-input without touching the
 * main conversation context.
 */
export const SelectionToolbar: React.FC<SelectionToolbarProps> = ({ containerRef }) => {
  const [anchor, setAnchor] = useState<{ top: number; center: number } | null>(null);
  const [selectedText, setSelectedText] = useState('');
  const openWithQuote = useSideAskStore((s) => s.openWithQuote);
  const requestQuoteToEditor = useSideAskStore((s) => s.requestQuoteToEditor);
  const { copyToClipboard, toast } = useCopyToast();

  const hide = useCallback(() => {
    setAnchor(null);
    setSelectedText('');
  }, []);

  useEffect(() => {
    const handleMouseUp = () => {
      // Let the browser finalize the selection before measuring
      setTimeout(() => {
        const selection = window.getSelection();
        const container = containerRef.current;
        if (!selection || selection.isCollapsed || selection.rangeCount === 0 || !container) {
          hide();
          return;
        }
        const range = selection.getRangeAt(0);
        if (!container.contains(range.commonAncestorContainer)) {
          hide();
          return;
        }
        const text = selection.toString();
        if (!text.trim()) {
          hide();
          return;
        }
        const rect = range.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) {
          hide();
          return;
        }
        const center = Math.max(HALF_WIDTH, Math.min(rect.left + rect.width / 2, window.innerWidth - HALF_WIDTH));
        setAnchor({ top: Math.max(TOOLBAR_GAP_ABOVE, rect.top - TOOLBAR_HEIGHT - TOOLBAR_GAP_ABOVE), center });
        setSelectedText(text);
      }, 0);
    };

    const handleScroll = () => hide();

    document.addEventListener('mouseup', handleMouseUp);
    window.addEventListener('scroll', handleScroll, true);
    return () => {
      document.removeEventListener('mouseup', handleMouseUp);
      window.removeEventListener('scroll', handleScroll, true);
    };
  }, [containerRef, hide]);

  if (!anchor || !selectedText) return null;

  const buttonClass = 'flex items-center gap-1 px-2 py-1 rounded hover:bg-muted transition-colors text-xs';
  const keepSelection = (e: React.MouseEvent) => e.preventDefault();

  return (
    <>
      <div
        className="fixed z-50 flex items-center gap-0.5 px-1 py-0.5 bg-popover border border-border rounded-md shadow-lg"
        style={{ top: anchor.top, left: anchor.center, transform: 'translateX(-50%)', height: TOOLBAR_HEIGHT }}
        onMouseDown={keepSelection}
      >
        <button
          className={buttonClass}
          title={i18n('Copy selection')}
          onClick={() => copyToClipboard(selectedText)}
        >
          {toast ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
          <span>{i18n('Copy selection')}</span>
        </button>
        <button
          className={buttonClass}
          title={i18n('Ask in side panel')}
          onClick={() => {
            openWithQuote(selectedText);
            hide();
          }}
        >
          <MessageSquareQuote className="w-3.5 h-3.5" />
          <span>{i18n('Ask in side panel')}</span>
        </button>
        <button
          className={buttonClass}
          title={i18n('Quote to input')}
          onClick={() => {
            requestQuoteToEditor(selectedText);
            hide();
          }}
        >
          <Quote className="w-3.5 h-3.5" />
          <span>{i18n('Quote to input')}</span>
        </button>
      </div>
      {toast}
    </>
  );
};
