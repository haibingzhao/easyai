import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Loader2 } from 'lucide-react';
import { MessageList } from '@/components/chat/MessageList';
import { useChatStore } from '@/services/stores/chat-store';
import { useTaskStore } from '@/services/stores/task-store';
import { loadTranscriptSession } from '@/services/session-switch';
import { getTaskDescriptor, taskTitle, taskTypeLabel } from '@/constants/task-types';
import type { AsyncTaskStatus } from '@/types/task';
import { i18n } from '@/utils/i18n';

const STATUS_STYLE: Record<AsyncTaskStatus, string> = {
  pending: 'bg-muted text-muted-foreground',
  running: 'bg-blue-500/15 text-blue-500',
  completed: 'bg-emerald-500/15 text-emerald-500',
  failed: 'bg-destructive/15 text-destructive',
  timed_out: 'bg-orange-500/15 text-orange-500',
  cancelled: 'bg-muted text-muted-foreground',
  interrupted: 'bg-amber-500/15 text-amber-500',
};

const STATUS_KEY: Record<AsyncTaskStatus, string> = {
  pending: 'Pending',
  running: 'Running',
  completed: 'Completed',
  failed: 'Failed',
  timed_out: 'Timed out',
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
 * Read-only transcript viewer for a background task's hidden session.
 * Reached from the Tasks page via "Open transcript"; renders the task metadata
 * header and the session's message list with no input, no agent/model selectors,
 * and no right panel.
 */
export const TaskTranscriptPage: React.FC = () => {
  const navigate = useNavigate();
  const { taskId } = useParams<{ taskId: string }>();
  const tasks = useTaskStore((s) => s.tasks);
  const messages = useChatStore((s) => s.messages);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const task = tasks.find((t) => t.id === taskId) ?? null;

  useEffect(() => {
    let cancelled = false;

    async function load() {
      if (!task?.sessionId) {
        if (!cancelled) {
          setError(i18n('Task not found or has no session'));
          setLoading(false);
        }
        return;
      }

      try {
        await loadTranscriptSession(task.sessionId);
        if (!cancelled) setLoading(false);
      } catch (e) {
        if (!cancelled) {
          console.error('Failed to load task transcript:', e);
          setError(String(e));
          setLoading(false);
        }
      }
    }

    if (task) {
      void load();
    } else if (tasks.length > 0) {
      // Tasks have loaded but this id is not among them — the row may have been deleted.
      setError(i18n('Task not found or has no session'));
      setLoading(false);
    }
    // If tasks are still loading (length === 0), wait for the next render.

    return () => {
      cancelled = true;
    };
  }, [task, tasks.length]);

  // Clear chat store state when leaving the transcript so a subsequent navigation
  // to `/` does not show the task's messages in the regular chat view.
  useEffect(() => {
    return () => {
      useChatStore.getState().clearChat();
    };
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full text-muted-foreground">
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
        {i18n('Loading...')}
      </div>
    );
  }

  if (error || !task) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-3">
        <p className="text-sm">{error ?? i18n('Task not found or has no session')}</p>
        <button
          onClick={() => navigate('/tasks')}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-border text-sm hover:bg-muted transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          {i18n('Back to Tasks')}
        </button>
      </div>
    );
  }

  const descriptor = getTaskDescriptor(task.type);
  const Icon = descriptor.icon;
  const summary = descriptor.summarize ? descriptor.summarize(task) : '';

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header bar */}
      <div className="shrink-0 border-b border-border px-4 py-3">
        <div className="flex items-center gap-3 mb-2">
          <button
            onClick={() => navigate('/tasks')}
            className="p-1.5 rounded-md hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
            title={i18n('Back to Tasks')}
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <Icon className="w-5 h-5 text-primary flex-shrink-0" />
          <h1 className="text-base font-semibold truncate flex-1">{taskTitle(task)}</h1>
          <span className={`text-xs px-2 py-0.5 rounded-full ${statusStyle(task.status)}`}>
            {statusLabel(task.status)}
          </span>
        </div>
        <div className="flex items-center gap-3 text-xs text-muted-foreground ml-8 flex-wrap">
          <span>{taskTypeLabel(task.type)}</span>
          <span>·</span>
          <span>{triggerLabel(task.trigger)}</span>
          <span>·</span>
          <span>{formatTime(task.createdAt)}</span>
          {task.durationMs != null && (
            <>
              <span>·</span>
              <span>{formatDuration(task.durationMs)}</span>
            </>
          )}
          {task.retryCount > 0 && (
            <>
              <span>·</span>
              <span>{i18n('Retried')} {task.retryCount}×</span>
            </>
          )}
        </div>
        {summary && (
          <div className="text-xs text-muted-foreground ml-8 mt-1 truncate">{summary}</div>
        )}
        {task.error && (
          <div className="text-xs text-destructive ml-8 mt-1 truncate">{task.error}</div>
        )}
      </div>

      {/* Message list — read-only, no input */}
      <div className="flex-1 min-h-0 overflow-y-auto">
        {messages.length === 0 ? (
          <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
            {i18n('No messages in this session')}
          </div>
        ) : (
          <MessageList messages={messages} isStreaming={false} disableEdit={true} />
        )}
      </div>
    </div>
  );
};
