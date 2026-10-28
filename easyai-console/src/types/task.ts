import type { ComponentType } from 'react';

/**
 * Async task types. Kept as an open string union (not a closed enum) so a host or a future backend
 * type degrades gracefully: an unknown type still lists, it just falls back to the generic label /
 * icon in `TASK_TYPE_REGISTRY`.
 */
export type AsyncTaskType = 'memory_consolidation' | (string & {});

export type AsyncTaskStatus =
  | 'pending'
  | 'running'
  | 'completed'
  | 'failed'
  | 'timed_out'
  | 'cancelled'
  | 'interrupted';

export type TaskTrigger = 'auto' | 'frontend' | 'schedule';

/** Wire shape of an `async_task` row (see backend `AsyncTaskDto`). */
export interface AsyncTask {
  id: string;
  type: AsyncTaskType;
  status: AsyncTaskStatus;
  trigger: TaskTrigger;
  title: string;
  sessionId?: string | null;
  /** Application that ran the task; absent on rows written before ownership was recorded. */
  ownerApp?: string | null;
  /**
   * False when another application wrote the row: it lists here (the queue is shared) but the
   * control actions belong to that application, which is the only one holding the worker.
   */
  controllable?: boolean;
  timeoutMs?: number | null;
  payload?: Record<string, unknown> | null;
  result?: Record<string, unknown> | null;
  error?: string | null;
  retryCount: number;
  retriedAt?: number | null;
  createdAt: number;
  startedAt?: number | null;
  completedAt?: number | null;
  updatedAt: number;
  durationMs?: number | null;
}

export interface TaskFilters {
  type?: AsyncTaskType;
  status?: AsyncTaskStatus;
  sessionId?: string;
  limit?: number;
}

/**
 * A host-owned page that shows what one run produced — the Tasks page only navigates to `path`,
 * it never interprets it.
 */
export interface TaskLink {
  /** Button text: an i18n key, or the host's own display string. */
  labelKey: string;
  /** In-app route to navigate to, built from the row. */
  path: string;
}

/**
 * Per-type presentation, the single extension point for the Tasks page: adding a backend task type
 * only needs one entry here. `summarize` renders a one-line outcome from the type-specific `result`;
 * the detail view falls back to a generic JSON dump when absent. `link` points a run at the host's
 * own result page.
 */
export interface TaskTypeDescriptor {
  labelKey: string;
  icon: ComponentType<{ className?: string }>;
  /**
   * Localized title built from the row's `payload`, falling back to the persisted `title` for rows
   * written before a field was added to the payload.
   */
  title?: (task: Pick<AsyncTask, 'payload' | 'title'>) => string;
  /** Renders a one-line outcome from the task's `result`/`error`, the fields every row carries. */
  summarize?: (outcome: Pick<AsyncTask, 'result' | 'error'>) => string;
  /**
   * Deep link to the host's own result page for this run, built from the row's `payload`/`result`.
   * Return null when the row identifies nothing — the Tasks page then shows no link button, so
   * hosts that do not fill in a result stay on the generic row.
   */
  link?: (task: Pick<AsyncTask, 'payload' | 'result'>) => TaskLink | null;
}

export const isTerminalStatus = (status: AsyncTaskStatus): boolean =>
  status === 'completed' ||
  status === 'failed' ||
  status === 'timed_out' ||
  status === 'cancelled' ||
  status === 'interrupted';
