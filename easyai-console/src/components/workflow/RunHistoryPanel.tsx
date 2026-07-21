import React, { useState, useEffect, useCallback } from 'react';
import { X, CheckCircle2, XCircle, Loader2, Clock, PauseCircle, PlayCircle, Trash2 } from 'lucide-react';
import type { RunSummary } from '@/services/swarm-service';
import { i18n } from '@/utils/i18n';
import { formatTokenCount } from '@/utils/format';

interface RunHistoryPanelProps {
  runs: RunSummary[];
  onSelectRun: (run: RunSummary) => void;
  onClose: () => void;
  onDeleteRun?: (run: RunSummary) => Promise<void>;
  hideHeader?: boolean;
}

const STATUS_BADGE: Record<string, { icon: React.ReactNode; className: string }> = {
  PENDING: { icon: <Clock className="w-3 h-3" />, className: 'text-muted-foreground bg-muted' },
  RUNNING: { icon: <Loader2 className="w-3 h-3 animate-spin" />, className: 'text-blue-600 bg-blue-50 dark:bg-blue-950' },
  COMPLETED: { icon: <CheckCircle2 className="w-3 h-3" />, className: 'text-green-600 bg-green-50 dark:bg-green-950' },
  FAILED: { icon: <XCircle className="w-3 h-3" />, className: 'text-red-600 bg-red-50 dark:bg-red-950' },
  CANCELLED: { icon: <XCircle className="w-3 h-3" />, className: 'text-orange-600 bg-orange-50 dark:bg-orange-950' },
  PAUSED: { icon: <PauseCircle className="w-3 h-3" />, className: 'text-purple-600 bg-purple-50 dark:bg-purple-950' },
  RESUMING: { icon: <PlayCircle className="w-3 h-3 animate-spin" />, className: 'text-blue-600 bg-blue-50 dark:bg-blue-950' },
};

export const RunHistoryPanel: React.FC<RunHistoryPanelProps> = ({ runs, onSelectRun, onClose, onDeleteRun, hideHeader = false }) => {
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  // Auto-reset confirm state after 3 seconds
  useEffect(() => {
    if (!confirmingId) return;
    const timer = setTimeout(() => setConfirmingId(null), 3000);
    return () => clearTimeout(timer);
  }, [confirmingId]);

  const handleDeleteClick = useCallback((e: React.MouseEvent, run: RunSummary) => {
    e.stopPropagation();
    if (deletingId) return;

    if (confirmingId === run.id) {
      // Second click within 3s — execute delete
      setDeletingId(run.id);
      setConfirmingId(null);
      onDeleteRun?.(run).finally(() => setDeletingId(null));
    } else {
      // First click — enter confirm state
      setConfirmingId(run.id);
    }
  }, [confirmingId, deletingId, onDeleteRun]);

  return (
    <div className="w-full border-l border-border flex flex-col flex-1 min-h-0">
      {!hideHeader && (
        <div className="flex items-center justify-between px-3 py-2 border-b border-border">
          <h4 className="text-sm font-medium truncate">{i18n('Run History')}</h4>
          <button onClick={onClose} className="p-1 rounded hover:bg-muted">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      <div className="flex-1 overflow-y-auto p-2">
          {runs.length === 0 ? (
            <div className="text-sm text-muted-foreground text-center py-8">
              {i18n('No history yet')}
            </div>
          ) : (
            runs.map((run) => {
              const badge = STATUS_BADGE[run.status] || STATUS_BADGE.PENDING;
              const isConfirming = confirmingId === run.id;
              const isDeleting = deletingId === run.id;
              return (
                <div key={run.id} className="relative">
                  <button
                    onClick={() => !isDeleting && onSelectRun(run)}
                    disabled={isDeleting}
                    className="w-full text-left px-3 py-2.5 rounded-md hover:bg-muted transition-colors disabled:opacity-60"
                  >
                    <div className="flex items-center gap-2">
                      <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs font-medium ${badge.className}`}>
                        {badge.icon}
                        {run.status}
                      </span>
                      <span className="text-sm truncate flex-1">{run.title || run.presetName}</span>
                      {/* Delete icon — right side */}
                      {onDeleteRun && (
                        <>
                          {isConfirming && (
                            <span className="text-xs text-red-500 font-medium shrink-0">
                              {i18n('Click again.')}
                            </span>
                          )}
                          <span
                            role="button"
                            onClick={(e) => handleDeleteClick(e, run)}
                            className={`shrink-0 inline-flex items-center justify-center w-5 h-5 rounded transition-colors ${
                              isConfirming
                                ? 'text-red-500'
                                : 'text-muted-foreground/50 hover:text-red-500'
                            }`}
                            title={isConfirming ? i18n('Click again to confirm') : i18n('Delete')}
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </span>
                        </>
                      )}
                    </div>
                    <div className="text-xs text-muted-foreground mt-1 flex gap-3">
                      <span>{new Date(run.createdAt).toLocaleString()}</span>
                      <span>{formatTokenCount(run.totalInputTokens + run.totalOutputTokens)} tokens</span>
                    </div>
                  </button>
                  {/* Indeterminate progress bar during deletion */}
                  {isDeleting && (
                    <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-muted overflow-hidden rounded-b-md">
                      <div className="h-full bg-red-500 animate-[indeterminate_1s_ease-in-out_infinite]" />
                    </div>
                  )}
                </div>
              );
            })
          )}
      </div>
    </div>
  );
};
