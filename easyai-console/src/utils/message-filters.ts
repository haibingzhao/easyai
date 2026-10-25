import type { Message, UserMessage } from '@/types/message';

/**
 * System-injected user-role messages (background-task steering, resume guidance,
 * repetition guard, Auto-continue notices, compaction summaries) are not questions
 * the user typed. Queued steer/followUp messages carry no systemOrigin and count.
 */
export function isUserQuestion(message: Message): message is UserMessage {
  return (
    (message.role === 'user' || message.role === 'user-with-attachments') &&
    !message.metadata?.systemOrigin &&
    message.metadata?.isCompactionSummary !== 'true' &&
    // Legacy fallback for Auto-continue rows persisted before the systemOrigin marker
    message.metadata?.source !== 'completion_check'
  );
}
