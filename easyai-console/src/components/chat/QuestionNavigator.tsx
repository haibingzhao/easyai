import React, { useCallback, useEffect, useMemo, useState } from 'react';
import type { Message, AssistantMessage, UserMessage as UserMessageType } from '../../types/message';
import { i18n } from '../../utils/i18n';

interface QuestionMark {
  index: number;
  question: string;
  answerPreview: string | null;
}

interface QuestionNavigatorProps {
  messages: Message[];
  containerRef: React.RefObject<HTMLDivElement | null>;
}

const MIN_ROW_HEIGHT = 4;
const MAX_ROW_HEIGHT = 11;
const MIN_GAP = 3;
const MAX_GAP = 10;

/**
 * System-injected user-role messages (background-task steering, resume guidance,
 * repetition guard, Auto-continue notices, compaction summaries) are not questions
 * the user typed. Queued steer/followUp messages carry no systemOrigin and count.
 */
function isUserQuestion(message: Message): message is UserMessageType {
  return (
    (message.role === 'user' || message.role === 'user-with-attachments') &&
    !message.metadata?.systemOrigin &&
    message.metadata?.isCompactionSummary !== 'true' &&
    // Legacy fallback for Auto-continue rows persisted before the systemOrigin marker
    message.metadata?.source !== 'completion_check'
  );
}

function extractQuestions(messages: Message[]): QuestionMark[] {
  const marks: QuestionMark[] = [];
  messages.forEach((message, index) => {
    if (!isUserQuestion(message)) return;
    let answerPreview: string | null = null;
    for (let i = index + 1; i < messages.length; i++) {
      const next = messages[i];
      if (next.role === 'assistant' && (next as AssistantMessage).content) {
        answerPreview = (next as AssistantMessage).content;
        break;
      }
    }
    marks.push({ index, question: message.content, answerPreview });
  });
  return marks;
}

export const QuestionNavigator: React.FC<QuestionNavigatorProps> = ({ messages, containerRef }) => {
  const questions = useMemo(() => extractQuestions(messages), [messages]);

  // The dash geometry is derived from the viewport, so it has to be recomputed on resize — keyed on
  // the question count alone it would keep the old layout after the window changes shape.
  const [viewportHeight, setViewportHeight] = useState(() => window.innerHeight);
  useEffect(() => {
    const handleResize = () => setViewportHeight(window.innerHeight);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // Shrink row height and gap so long sessions keep every dash reachable
  const { rowHeight, gap } = useMemo(() => {
    const n = questions.length;
    if (n < 2) return { rowHeight: MAX_ROW_HEIGHT, gap: MAX_GAP };
    const available = Math.min(viewportHeight * 0.7, 640);
    const height = Math.max(MIN_ROW_HEIGHT, Math.min(MAX_ROW_HEIGHT, Math.floor((available - (n - 1) * MIN_GAP) / n)));
    const computedGap = Math.max(MIN_GAP, Math.min(MAX_GAP, Math.floor((available - n * height) / (n - 1))));
    return { rowHeight: height, gap: computedGap };
  }, [questions.length, viewportHeight]);

  const scrollToQuestion = useCallback((index: number) => {
    const container = containerRef.current;
    if (!container) return;
    const target = container.querySelector<HTMLElement>(`[data-msg-index="${index}"]`);
    if (!target) return;
    const header = container.firstElementChild as HTMLElement | null;
    const headerHeight = header ? header.getBoundingClientRect().height : 0;
    const top =
      container.scrollTop +
      target.getBoundingClientRect().top -
      container.getBoundingClientRect().top -
      headerHeight -
      8;
    container.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
  }, [containerRef]);

  if (questions.length < 2) return null;

  return (
    <div className="absolute right-2 top-1/2 -translate-y-1/2 z-20 flex flex-col items-end" style={{ gap }}>
      {questions.map((question) => (
        <div
          key={question.index}
          className="group relative flex items-center justify-end w-6"
          style={{ height: rowHeight }}
        >
          <button
            type="button"
            aria-label={i18n('Go to question')}
            onClick={() => scrollToQuestion(question.index)}
            className="block w-2.5 h-[3px] rounded-full bg-muted-foreground/40 hover:bg-primary transition-colors cursor-pointer"
          />
          <div
            onClick={() => scrollToQuestion(question.index)}
            className="hidden group-hover:block absolute right-full top-1/2 -translate-y-1/2 pr-2 cursor-pointer"
          >
            <div className="w-72 rounded-lg border border-border bg-background p-3 shadow-xl text-left">
              <div className="text-sm font-semibold line-clamp-2 whitespace-pre-wrap">{question.question}</div>
              {question.answerPreview && (
                <div className="mt-1 text-xs text-muted-foreground line-clamp-3">{question.answerPreview}</div>
              )}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
};
