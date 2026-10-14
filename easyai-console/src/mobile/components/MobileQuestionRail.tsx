import React, { useCallback } from 'react';
import { useChatStore } from '@/services/stores/chat-store';
import { i18n } from '@/utils/i18n';

interface MobileQuestionRailProps {
  containerRef: React.RefObject<HTMLDivElement | null>;
}

/**
 * Question jump rail.
 *
 * Desktop shows a dash per user turn with a rich hover tooltip (`QuestionNavigator`), which
 * `ChatPanel` deliberately suppresses under the mobile breakpoint — hover does not exist
 * here. This is the touch equivalent: a narrow rail of taps, each scrolling to its turn, with the
 * question text carried in the accessibility label instead of a tooltip.
 */
export const MobileQuestionRail: React.FC<MobileQuestionRailProps> = ({ containerRef }) => {
  const messages = useChatStore((state) => state.messages);

  const questionIndexes = messages
    .map((message, index) => ({ message, index }))
    .filter(({ message }) => message.role === 'user' || message.role === 'user-with-attachments')
    .map(({ index }) => index);

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

  if (questionIndexes.length < 2) return null;

  return (
    <div
      className="absolute right-0 top-1/2 -translate-y-1/2 z-10 flex flex-col items-end gap-1.5 px-1"
      aria-label={i18n('Go to question')}
    >
      {questionIndexes.map((index, order) => (
        <button
          key={index}
          className="block rounded-full bg-muted-foreground/35 active:bg-primary"
          style={{ width: 3, height: 20 }}
          onClick={() => jump(index)}
          aria-label={`${i18n('Go to question')} ${order + 1}`}
        />
      ))}
    </div>
  );
};
