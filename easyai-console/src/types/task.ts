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
 * Per-type presentation, the single extension point for the Tasks page: adding a backend task type
 * only needs one entry here. `summarize` renders a one-line outcome from the type-specific `result`;
 * the detail view falls back to a generic JSON dump when absent.
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
}

export const isTerminalStatus = (status: AsyncTaskStatus): boolean =>
  status === 'completed' ||
  status === 'failed' ||
  status === 'timed_out' ||
  status === 'cancelled' ||
  status === 'interrupted';
