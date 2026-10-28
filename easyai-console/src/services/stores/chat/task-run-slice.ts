import type { Message } from '@/types/message';
import type { AsyncTask } from '@/types/task';

const WATCH_RUN_KEY = 'watchRun';

function watchRunIdOf(message: Message): string | undefined {
  if (message.role !== 'user' && message.role !== 'user-with-attachments') return undefined;
  return message.metadata?.[WATCH_RUN_KEY];
}

/**
 * Every watch firing reuses one audit session, and the task transcript page loads that session
 * whole. Slicing to the firing this task opened keeps a timed-out run's page from ending with the
 * next run's answer.
 *
 * Tasks of other types own their session outright and keep the full transcript. A watch session
 * written before the run tag existed has no boundaries to cut on, so it also stays whole; a tagged
 * session with no turn for this task renders nothing rather than another firing's messages.
 */
export function sliceTaskTranscript(
  messages: Message[],
  task: Pick<AsyncTask, 'id' | 'type'> & Partial<Pick<AsyncTask, 'retriedAt'>>
): Message[] {
  if (task.type !== 'watch') return messages;
  const runIds = messages.map(watchRunIdOf);
  if (!runIds.some((runId) => runId !== undefined)) return messages;

  const retriedAt = task.retriedAt;
  let start = -1;
  for (let i = 0; i < runIds.length; i++) {
    if (runIds[i] !== task.id) continue;
    // A retried task keeps its id, so a retry that failed before saving its prompt would
    // otherwise match the attempt it replaced; only markers from this attempt count.
    if (retriedAt != null && messages[i].timestamp < retriedAt) continue;
    start = i;
  }
  if (start < 0) return [];

  let end = messages.length;
  for (let i = start + 1; i < runIds.length; i++) {
    const runId = runIds[i];
    if (runId !== undefined && runId !== task.id) {
      end = i;
      break;
    }
  }
  return messages.slice(start, end);
}
