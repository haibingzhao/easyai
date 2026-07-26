import React from 'react';
import { Network, ExternalLink, Loader2, CheckCircle2, XCircle } from 'lucide-react';
import type { SwarmRunTracking } from '@/services/stores/chat-store';
import { formatTokenCount } from '@/utils/format';
import { i18n } from '@/utils/i18n';

interface SwarmRunCardProps {
  runs: Record<string, SwarmRunTracking>;
}

const STATUS_BADGE: Record<string, { label: string; className: string }> = {
  RUNNING: { label: 'Running', className: 'bg-blue-500/10 text-blue-600 dark:text-blue-400' },
  PENDING: { label: 'Pending', className: 'bg-yellow-500/10 text-yellow-600 dark:text-yellow-400' },
  COMPLETED: { label: 'Completed', className: 'bg-green-500/10 text-green-600 dark:text-green-400' },
  FAILED: { label: 'Failed', className: 'bg-red-500/10 text-red-600 dark:text-red-400' },
  CANCELLED: { label: 'Cancelled', className: 'bg-gray-500/10 text-gray-600 dark:text-gray-400' },
  PAUSED: { label: 'Paused', className: 'bg-purple-500/10 text-purple-600 dark:text-purple-400' },
  RESUMING: { label: 'Resuming', className: 'bg-blue-500/10 text-blue-600 dark:text-blue-400' },
};

function StatusIcon({ status }: { status: string }) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle2 className="w-3.5 h-3.5 text-green-500" />;
    case 'FAILED':
    case 'CANCELLED':
      return <XCircle className="w-3.5 h-3.5 text-red-500" />;
    default:
      return <Loader2 className="w-3.5 h-3.5 text-blue-500 animate-spin" />;
  }
}

/**
 * SwarmRunCard — displays active/recent swarm runs in the Summary panel.
 * Shows run title, status, task progress, token usage, and a link to the Workflow page.
 */
export const SwarmRunCard: React.FC<SwarmRunCardProps> = ({ runs }) => {
  const entries = Object.values(runs);
  if (entries.length === 0) return null;

  return (
    <div className="text-sm">
      <div className="flex items-center gap-1.5 py-2 px-2">
        <Network className="w-4 h-4 text-cyan-500" />
        <span className="text-muted-foreground font-medium">{i18n('Swarm')}</span>
      </div>
      <div className="space-y-2 pb-2">
        {entries.map((run) => {
          const completedTasks = run.tasks.filter((t) => t.status === 'COMPLETED').length;
          const badge = STATUS_BADGE[run.status] ?? STATUS_BADGE.RUNNING;
          return (
            <div key={run.runId} className="mx-2 rounded-md border border-border/60 bg-muted/20 p-2.5">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-1.5 min-w-0">
                  <StatusIcon status={run.status} />
                  <span className="text-xs font-medium text-foreground truncate">{run.title || run.presetName}</span>
                </div>
                <span className={`text-[10px] px-1.5 py-0.5 rounded-full font-medium shrink-0 ${badge.className}`}>
                  {badge.label}
                </span>
              </div>
              <div className="mt-1.5 flex items-center gap-3 text-[11px] text-muted-foreground">
                <span>{completedTasks}/{run.tasks.length} tasks</span>
                {(run.totalInputTokens > 0 || run.totalOutputTokens > 0) && (
                  <span>
                    {run.totalInputTokens > 0 && `↑${formatTokenCount(run.totalInputTokens)}`}
                    {run.totalInputTokens > 0 && run.totalOutputTokens > 0 && ' '}
                    {run.totalOutputTokens > 0 && `↓${formatTokenCount(run.totalOutputTokens)}`}
                  </span>
                )}
              </div>
              <a
                href={`#/workflow/${encodeURIComponent(run.presetName)}/run`}
                className="mt-1.5 inline-flex items-center gap-1 text-[11px] text-cyan-600 dark:text-cyan-400 hover:underline"
              >
                <ExternalLink className="w-3 h-3" />
                {i18n('View in Workflow')}
              </a>
            </div>
          );
        })}
      </div>
    </div>
  );
};
