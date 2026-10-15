import React, { useCallback, useMemo, useRef, useState } from 'react';
import { X } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { extractQuestionMarks } from '@/components/chat/QuestionNavigator';
import { i18n } from '@/utils/i18n';

interface MobileQuestionRailProps {
  containerRef: React.RefObject<HTMLDivElement | null>;
}

/** A leftward swipe of this many px from the right edge opens the panel. */
const SWIPE_DX = -40;
const SWIPE_DY_SLOP = 60;
const TAP_SLOP = 10;

/**
 * Question jump rail.
 *
 * Desktop shows a dash per user turn with a rich hover tooltip (`QuestionNavigator`), which
 * `ChatPanel` suppresses under the mobile breakpoint. The dashes are far too small to tap here, so
 * they are a pure position indicator; the touch interaction is a leftward swipe from the right
 * edge (or a tap on that edge strip) that slides in a panel of full-size question rows, each
 * carrying the question and its answer preview — the hover tooltip's content at tappable size.
 */
export const MobileQuestionRail: React.FC<MobileQuestionRailProps> = ({ containerRef }) => {
  const messages = useChatStore((state) => state.messages);
  const questions = useMemo(() => extractQuestionMarks(messages), [messages]);
  const [open, setOpen] = useState(false);
  const gestureRef = useRef<{ x: number; y: number; opened: boolean } | null>(null);

  const jump = useCallback(
    (index: number) => {
      const container = containerRef.current;
      if (!container) return;
      const target = container.querySelector<HTMLElement>(`[data-msg-index="${index}"]`);
      if (!target) return;
      container.scrollTo({ top: target.offsetTop - 8, behavior: 'smooth' });
    },
    [containerRef]
  );

  const onEdgeDown = (event: React.PointerEvent) => {
    gestureRef.current = { x: event.clientX, y: event.clientY, opened: false };
  };

  const onEdgeMove = (event: React.PointerEvent) => {
    const gesture = gestureRef.current;
    if (!gesture || gesture.opened) return;
    const dx = event.clientX - gesture.x;
    const dy = event.clientY - gesture.y;
    if (dx < SWIPE_DX && Math.abs(dy) < SWIPE_DY_SLOP) {
      gesture.opened = true;
      setOpen(true);
    }
  };

  const onEdgeUp = (event: React.PointerEvent) => {
    const gesture = gestureRef.current;
    gestureRef.current = null;
    if (!gesture || gesture.opened) return;
    // No swipe happened: a stationary press on the edge strip also opens the panel.
    if (Math.abs(event.clientX - gesture.x) < TAP_SLOP && Math.abs(event.clientY - gesture.y) < TAP_SLOP) {
      setOpen(true);
    }
  };

  if (questions.length < 2) return null;

  return (
    <>
      <div
        className="pointer-events-none absolute right-1 top-1/2 -translate-y-1/2 z-10 flex flex-col items-end gap-1.5"
        aria-hidden
      >
        {questions.map((question) => (
          <span key={question.index} className="block rounded-full bg-muted-foreground/35" style={{ width: 3, height: 20 }} />
        ))}
      </div>

      <div
        className="absolute inset-y-0 right-0 z-20 w-6"
        style={{ touchAction: 'pan-y' }}
        role="button"
        aria-label={i18n('Questions')}
        onPointerDown={onEdgeDown}
        onPointerMove={onEdgeMove}
        onPointerUp={onEdgeUp}
        onPointerCancel={() => { gestureRef.current = null; }}
      />

      {open && (
        <>
          <div className="absolute inset-0 z-30 bg-black/40" onClick={() => setOpen(false)} />
          <div className="absolute inset-y-0 right-0 z-40 flex w-[85vw] max-w-sm flex-col border-l border-border bg-background shadow-xl">
            <div className="flex shrink-0 items-center justify-between border-b border-border px-3 py-2">
              <span className="text-sm font-medium">{i18n('Questions')}</span>
              <button
                type="button"
                onClick={() => setOpen(false)}
                aria-label={i18n('Close')}
                className="mobile-hit rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              >
                <X className="size-5" />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto">
              {questions.map((question, order) => (
                <button
                  key={question.index}
                  type="button"
                  className="w-full border-b border-border px-3 py-3 text-left transition-colors active:bg-muted"
                  onClick={() => { setOpen(false); jump(question.index); }}
                >
                  <div className="mb-1 text-xs text-muted-foreground">#{order + 1}</div>
                  <div className="line-clamp-2 whitespace-pre-wrap text-sm font-medium">{question.question}</div>
                  {question.answerPreview && (
                    <div className="mt-1 line-clamp-2 text-xs text-muted-foreground">{question.answerPreview}</div>
                  )}
                </button>
              ))}
            </div>
          </div>
        </>
      )}
    </>
  );
};
