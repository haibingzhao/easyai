import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Ban, ListChecks, MessageSquare, RefreshCw, RotateCcw, Trash2, X } from 'lucide-react';
import { useTaskStore } from '@/services/stores/task-store';
import { Dialog } from '@/components/ui/Dialog';
import { getTaskDescriptor, taskTitle, taskTypeLabel, TASK_TYPE_REGISTRY } from '@/constants/task-types';
import type { AsyncTaskStatus, TaskFilters } from '@/types/task';
import { isTerminalStatus } from '@/types/task';
import { i18n } from '@/utils/i18n';

const STATUS_STYLE: Record<AsyncTaskStatus, string> = {
  pending: 'bg-muted text-muted-foreground',
  running: 'bg-blue-500/15 text-blue-500',
  completed: 'bg-emerald-500/15 text-emerald-500',
  failed: 'bg-destructive/15 text-destructive',
  cancelled: 'bg-muted text-muted-foreground',
  interrupted: 'bg-amber-500/15 text-amber-500',
};

const STATUS_KEY: Record<AsyncTaskStatus, string> = {
  pending: 'Pending',
  running: 'Running',
  completed: 'Completed',
  failed: 'Failed',
  cancelled: 'Cancelled',
  interrupted: 'Interrupted',
};

const TRIGGER_KEY: Record<string, string> = {
  auto: 'Auto',
  frontend: 'Frontend',
  schedule: 'Scheduled',
};

function statusLabel(status: AsyncTaskStatus): string {
  return i18n(STATUS_KEY[status] ?? status);
}

/** A status this build has no style for must still render, not splice `undefined` into the class list. */
function statusStyle(status: AsyncTaskStatus): string {
  return STATUS_STYLE[status] ?? 'bg-muted text-muted-foreground';
}

function triggerLabel(trigger: string): string {
  return i18n(TRIGGER_KEY[trigger] ?? trigger);
}

function formatTime(ts?: number | null): string {
  return ts ? new Date(ts).toLocaleString() : '—';
}

function formatDuration(ms?: number | null): string {
  if (ms == null || ms < 0) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

/**
 * Global, user-scoped view of durable async tasks. Reads from `/api/tasks` (REST) with a manual
 * refresh plus light polling while any task is still active — there is no user-level realtime
 * stream this iteration. Per-type presentation comes from `TASK_TYPE_REGISTRY`, so a new backend
 * task type shows up here without page changes.
 */
export const TasksPage: React.FC = () => {
  const navigate = useNavigate();
  const tasks = useTaskStore((s) => s.tasks);
  const loading = useTaskStore((s) => s.loading);
  const error = useTaskStore((s) => s.error);
  const filters = useTaskStore((s) => s.filters);
  const loadTasks = useTaskStore((s) => s.loadTasks);
  const cancelTask = useTaskStore((s) => s.cancelTask);
  const retryTask = useTaskStore((s) => s.retryTask);
  const deleteTask = useTaskStore((s) => s.deleteTask);
  const clearTasks = useTaskStore((s) => s.clearTasks);
  const clearError = useTaskStore((s) => s.clearError);
  const [typeFilter, setTypeFilter] = useState<string>(filters.type ?? '');
  const [statusFilter, setStatusFilter] = useState<string>(filters.status ?? '');
  // The open task is tracked by id, not by the row clicked: a dialog holding a snapshot would freeze
  // a running task at the instant it was opened while the list behind it kept polling.
  const [detailId, setDetailId] = useState<string | null>(null);
  const detail = useMemo(() => tasks.find((t) => t.id === detailId) ?? null, [tasks, detailId]);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const refresh = useCallback(
    (silent = false) => {
      const next: TaskFilters = {
        type: typeFilter || undefined,
        status: (statusFilter || undefined) as AsyncTaskStatus | undefined,
      };
      void loadTasks(next, { silent });
    },
    [typeFilter, statusFilter, loadTasks]
  );

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Poll only while something is still in flight, so an idle page makes no requests.
  const hasActive = useMemo(() => tasks.some((t) => !isTerminalStatus(t.status)), [tasks]);
  useEffect(() => {
    if (!hasActive) {
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = null;
      return;
    }
    if (!pollRef.current) {
      pollRef.current = setInterval(() => refresh(true), 5000);
    }
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = null;
    };
  }, [hasActive, refresh]);

  const typeOptions = useMemo(() => Object.keys(TASK_TYPE_REGISTRY), []);

  /** Navigate to the dedicated transcript page for a background task's hidden session. */
  const openTranscript = (taskId: string) => {
    setDetailId(null);
    navigate(`/tasks/${taskId}/transcript`);
  };

  /** Bulk irreversible delete, so it asks first — the same bar a single row's inline confirm sets. */
  const handleClearCompleted = () => {
    if (!window.confirm(i18n('Clear completed tasks? This cannot be undone.'))) return;
    void clearTasks('completed');
  };

  return (
    <div className="flex flex-col h-full bg-background">
      <div className="max-w-4xl mx-auto w-full p-6">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold flex items-center gap-2">
              <ListChecks className="w-6 h-6 text-primary" />
              {i18n('Tasks')}
            </h1>
            <p className="text-sm text-muted-foreground mt-1">{i18n('Background tasks run app-wide and outlive their session')}</p>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={handleClearCompleted}
              className="flex items-center gap-2 px-3 py-2 rounded-md border border-input text-sm hover:bg-muted transition-colors"
              title={i18n('Clear completed')}
            >
              <Trash2 className="w-4 h-4" />
              {i18n('Clear completed')}
            </button>
            <button
              onClick={() => refresh()}
              className="flex items-center gap-2 px-3 py-2 bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors text-sm"
            >
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
              {i18n('Refresh')}
            </button>
          </div>
        </div>

        {/* Error banner */}
        {error && (
          <div className="mb-4 p-3 bg-destructive/10 text-destructive rounded-md text-sm flex items-center justify-between">
            <span>{error}</span>
            <button onClick={clearError}><X className="w-4 h-4" /></button>
          </div>
        )}

        {/* Filters */}
        <div className="flex items-center gap-3 mb-4">
          <select
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            className="px-3 py-2 rounded-md border border-input bg-background text-sm focus:ring-2 focus:ring-ring focus:outline-none"
          >
            <option value="">{i18n('All types')}</option>
            {typeOptions.map((t) => (
              <option key={t} value={t}>{taskTypeLabel(t)}</option>
            ))}
          </select>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="px-3 py-2 rounded-md border border-input bg-background text-sm focus:ring-2 focus:ring-ring focus:outline-none"
          >
            <option value="">{i18n('All statuses')}</option>
            {(['pending', 'running', 'completed', 'failed', 'cancelled', 'interrupted'] as AsyncTaskStatus[]).map((s) => (
              <option key={s} value={s}>{statusLabel(s)}</option>
            ))}
          </select>
        </div>

        {/* Loading */}
        {loading && tasks.length === 0 && (
          <div className="text-center py-8 text-muted-foreground">{i18n('Loading...')}</div>
        )}

        {/* Empty */}
        {!loading && tasks.length === 0 && (
          <div className="text-center py-12">
            <ListChecks className="w-12 h-12 mx-auto text-muted-foreground mb-3" />
            <p className="text-muted-foreground">{i18n('No tasks yet')}</p>
          </div>
        )}

        {/* List */}
        {tasks.length > 0 && (
          <div className="space-y-2">
            {tasks.map((task) => {
              const descriptor = getTaskDescriptor(task.type);
              const Icon = descriptor.icon;
              const terminal = isTerminalStatus(task.status);
              const summary = descriptor.summarize ? descriptor.summarize(task) : '';
              return (
                <div
                  key={task.id}
                  className="rounded-lg border border-border bg-card p-4 hover:bg-accent/50 transition-colors"
                >
                  <div className="flex items-start justify-between gap-3">
                    <button className="flex-1 min-w-0 text-left" onClick={() => setDetailId(task.id)}>
                      <div className="flex items-center gap-2 mb-1">
                        <Icon className="w-4 h-4 text-primary flex-shrink-0" />
                        <span className="text-sm font-semibold truncate">{taskTitle(task)}</span>
                        <span className={`text-xs px-2 py-0.5 rounded-full ${statusStyle(task.status)}`}>
                          {statusLabel(task.status)}
                        </span>
                        <span className="text-xs text-muted-foreground">{triggerLabel(task.trigger)}</span>
                      </div>
                      <div className="text-xs text-muted-foreground flex items-center gap-3 flex-wrap">
                        <span>{formatTime(task.createdAt)}</span>
                        {task.durationMs != null && <span>· {formatDuration(task.durationMs)}</span>}
                        {task.retryCount > 0 && <span>· {i18n('Retried')} {task.retryCount}×</span>}
                        {summary && <span className="truncate">· {summary}</span>}
                      </div>
                      {task.error && (
                        <p className="text-xs text-destructive mt-1 truncate">{task.error}</p>
                      )}
                    </button>
                    <div className="flex items-center gap-1 shrink-0">
                      {task.sessionId && (
                        <button
                          onClick={() => void openTranscript(task.id)}
                          className="p-1.5 rounded-md hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
                          title={i18n('Open transcript')}
                        >
                          <MessageSquare className="w-4 h-4" />
                        </button>
                      )}
                      {!terminal && (
                        <button
                          onClick={() => void cancelTask(task.id)}
                          className="p-1.5 rounded-md hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
                          title={i18n('Cancel')}
                        >
                          <Ban className="w-4 h-4" />
                        </button>
                      )}
                      {/* Retry re-runs the handler and overwrites the row's result, so it only
                          makes sense for a run that did not finish cleanly — a completed pass
                          has already done its work and re-running it just burns model calls. */}
                      {(task.status === 'failed' || task.status === 'interrupted') && (
                        <button
                          onClick={() => void retryTask(task.id)}
                          className="p-1.5 rounded-md hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
                          title={i18n('Retry')}
                        >
                          <RotateCcw className="w-4 h-4" />
                        </button>
                      )}
                      {confirmDeleteId === task.id ? (
                        <div className="flex items-center gap-1">
                          <button
                            onClick={() => { void deleteTask(task.id); setConfirmDeleteId(null); }}
                            className="px-2 py-1 text-xs bg-destructive text-destructive-foreground rounded hover:bg-destructive/90"
                          >
                            {i18n('Confirm')}
                          </button>
                          <button
                            onClick={() => setConfirmDeleteId(null)}
                            className="px-2 py-1 text-xs rounded hover:bg-muted"
                          >
                            {i18n('Cancel')}
                          </button>
                        </div>
                      ) : (
                        <button
                          onClick={() => setConfirmDeleteId(task.id)}
                          className="p-1.5 rounded-md hover:bg-destructive/10 transition-colors text-muted-foreground hover:text-destructive"
                          title={i18n('Delete')}
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Detail */}
      <Dialog open={detail !== null} onClose={() => setDetailId(null)} title={detail ? taskTitle(detail) : i18n('Task detail')}>
        {detail && (
          <div className="space-y-3 text-sm">
            <DetailRow label={i18n('Type')} value={taskTypeLabel(detail.type)} />
            <DetailRow label={i18n('Status')} value={statusLabel(detail.status)} />
            <DetailRow label={i18n('Trigger')} value={triggerLabel(detail.trigger)} />
            <DetailRow label={i18n('Created')} value={formatTime(detail.createdAt)} />
            <DetailRow label={i18n('Duration')} value={formatDuration(detail.durationMs)} />
            {detail.retryCount > 0 && (
              <DetailRow label={i18n('Retries')} value={`${detail.retryCount} (${formatTime(detail.retriedAt)})`} />
            )}
            {detail.error && <DetailRow label={i18n('Error')} value={detail.error} />}
            {detail.sessionId && (
              <button
                onClick={() => void openTranscript(detail.id)}
                className="flex items-center gap-2 px-3 py-1.5 rounded-md border border-border text-sm hover:bg-muted transition-colors"
              >
                <MessageSquare className="w-4 h-4" />
                {i18n('Open transcript')}
              </button>
            )}
            {detail.result && (
              <div>
                <div className="text-xs text-muted-foreground mb-1">{i18n('Result')}</div>
                <pre className="p-2 rounded-md bg-muted text-xs overflow-auto max-h-48 whitespace-pre-wrap break-words">
                  {JSON.stringify(detail.result, null, 2)}
                </pre>
              </div>
            )}
            {detail.payload && (
              <div>
                <div className="text-xs text-muted-foreground mb-1">{i18n('Payload')}</div>
                <pre className="p-2 rounded-md bg-muted text-xs overflow-auto max-h-48 whitespace-pre-wrap break-words">
                  {JSON.stringify(detail.payload, null, 2)}
                </pre>
              </div>
            )}
          </div>
        )}
      </Dialog>
    </div>
  );
};

const DetailRow: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <div className="flex items-baseline gap-2">
    <span className="text-xs text-muted-foreground w-20 shrink-0">{label}</span>
    <span className="break-words">{value}</span>
  </div>
);
