import React from 'react';
import { Copy, MessageSquareQuote, Quote } from 'lucide-react';
import { useSideAskStore } from '@/services/stores/side-ask-store';
import { useCopyToast } from '@/components/chat/tools/useCopyToast';
import { i18n } from '@/utils/i18n';
import { MobileSheet } from './MobileSheet';
import { useMobileUiStore } from '../stores/mobile-ui-store';

/**
 * Long-press menu for a message.
 *
 * The desktop equivalent (`SelectionToolbar`) is anchored to a text selection and appears on
 * `mouseup`, which a touchscreen never emits. Long-pressing the whole bubble is the phone
 * version of "I mean this text", and it gives the same three actions.
 */
export const MessageActionSheet: React.FC = () => {
  const target = useMobileUiStore((s) => s.actionSheetTarget);
  const setTarget = useMobileUiStore((s) => s.setActionSheetTarget);
  const openWithQuote = useSideAskStore((s) => s.openWithQuote);
  const requestQuoteToEditor = useSideAskStore((s) => s.requestQuoteToEditor);
  const { copyToClipboard, toast } = useCopyToast();

  const close = () => setTarget(null);
  const text = target?.text ?? '';

  return (
    <>
      <MobileSheet open={!!target && text.length > 0} onClose={close} title="Message">
        <button
          className="mobile-row w-full text-left border-b border-border"
          onClick={(e) => {
            void copyToClipboard(text, e);
            close();
          }}
        >
          <Copy className="w-5 h-5 shrink-0 text-muted-foreground" />
          <span className="text-sm">{i18n('Copy')}</span>
        </button>
        <button
          className="mobile-row w-full text-left border-b border-border"
          onClick={() => {
            requestQuoteToEditor(text);
            close();
          }}
        >
          <Quote className="w-5 h-5 shrink-0 text-muted-foreground" />
          <span className="text-sm">{i18n('Quote to input')}</span>
        </button>
        <button
          className="mobile-row w-full text-left border-b border-border"
          onClick={() => {
            openWithQuote(text);
            close();
          }}
        >
          <MessageSquareQuote className="w-5 h-5 shrink-0 text-muted-foreground" />
          <span className="text-sm">{i18n('Ask about this')}</span>
        </button>
        <p className="mt-2 text-[11px] leading-snug text-muted-foreground line-clamp-3">{text}</p>
      </MobileSheet>
      {toast}
    </>
  );
};
