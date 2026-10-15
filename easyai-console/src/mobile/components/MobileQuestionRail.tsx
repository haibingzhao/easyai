import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useChatStore } from '@/services/stores/chat-store';
import { extractQuestionMarks } from '@/components/chat/QuestionNavigator';
import { i18n } from '@/utils/i18n';

interface MobileQuestionRailProps {
  containerRef: React.RefObject<HTMLDivElement | null>;
}

/** Touch has no hover, so the desktop tooltip's peek becomes a long-press on a dash. */
const LONG_PRESS_MS = 350;
const MOVE_SLOP_PX = 10;

/**
 * Question jump rail.
 *
 * Desktop shows a dash per user turn with a rich hover tooltip (`QuestionNavigator`), which
 * `ChatPanel` deliberately suppresses under the mobile breakpoint — hover does not exist here.
 * This is the touch equivalent: a quick tap scrolls to that turn (same as a desktop click), while
 * pressing and holding a dash peeks the question + answer preview (the hover tooltip's job).
 */
export const MobileQuestionRail: React.FC<MobileQuestionRailProps> = ({ containerRef }) => {
  const messages = useChatStore((state) => state.messages);
  const questions = useMemo(() => extractQuestionMarks(messages), [messages]);
  const [peekIndex, setPeekIndex] = useState<number | null>(null);
  const railRef = useRef<HTMLDivElement | null>(null);
  const pressRef = useRef<{ timer: number; x: number; y: number; index: number; fired: boolean } | null>(null);

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

  useEffect(() => {
    if (peekIndex === null) return;
    const onOutside = (event: PointerEvent) => {
      if (railRef.current && !railRef.current.contains(event.target as Node)) setPeekIndex(null);
    };
    document.addEventListener('pointerdown', onOutside);
    return () => document.removeEventListener('pointerdown', onOutside);
  }, [peekIndex]);

  const clearPress = useCallback(() => {
    if (pressRef.current) window.clearTimeout(pressRef.current.timer);
    pressRef.current = null;
  }, []);

  const onDashDown = (event: React.PointerEvent, index: number) => {
    clearPress();
    const { clientX, clientY } = event;
    const timer = window.setTimeout(() => {
      if (pressRef.current) pressRef.current.fired = true;
      setPeekIndex(index);
    }, LONG_PRESS_MS);
    pressRef.current = { timer, x: clientX, y: clientY, index, fired: false };
  };

  const onDashMove = (event: React.PointerEvent) => {
    const press = pressRef.current;
    if (!press || press.fired) return;
    if (Math.hypot(event.clientX - press.x, event.clientY - press.y) > MOVE_SLOP_PX) clearPress();
  };

  const onDashUp = (index: number) => {
    const press = pressRef.current;
    clearPress();
    // A held press already peeked; only a quick, unmoved tap jumps.
    if (press && !press.fired && press.index === index) jump(index);
  };

  if (questions.length < 2) return null;

  return (
    <div
      ref={railRef}
      className="absolute right-0 top-1/2 -translate-y-1/2 z-10 flex flex-col items-end gap-1.5 px-1"
      aria-label={i18n('Go to question')}
    >
      {questions.map((question, order) => (
        <div key={question.index} className="relative flex items-center justify-end" style={{ height: 20 }}>
          {peekIndex === question.index && (
            <div
              className="absolute right-full top-1/2 -translate-y-1/2 pr-2"
              onClick={() => { setPeekIndex(null); jump(question.index); }}
            >
              <div className="w-64 max-w-[72vw] rounded-lg border border-border bg-background p-3 shadow-xl text-left">
                <div className="text-sm font-semibold line-clamp-2 whitespace-pre-wrap">{question.question}</div>
                {question.answerPreview && (
                  <div className="mt-1 text-xs text-muted-foreground line-clamp-3">{question.answerPreview}</div>
                )}
              </div>
            </div>
          )}
          <button
            type="button"
            className="block rounded-full bg-muted-foreground/35 active:bg-primary touch-none"
            style={{ width: 3, height: 20 }}
            onPointerDown={(event) => onDashDown(event, question.index)}
            onPointerMove={onDashMove}
            onPointerUp={() => onDashUp(question.index)}
            onPointerCancel={clearPress}
            onContextMenu={(event) => event.preventDefault()}
            aria-label={`${i18n('Go to question')} ${order + 1}`}
          />
        </div>
      ))}
    </div>
  );
};
