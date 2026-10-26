import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  BellRing,
  ChevronRight,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import { useWatchStore } from '@/services/stores/watch-store';
import { formatInZone, scheduleSummary, VerdictBadge, WatchStatusBadge } from '@/components/watch/watch-ui';
import type { Watch, WatchStatus } from '@/types/watch';
import { i18n } from '@/utils/i18n';

const FILTERS: Array<{ id: WatchStatus | 'all'; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'active', label: 'Active' },
  { id: 'paused', label: 'Paused' },
  { id: 'completed', label: 'Completed' },
  { id: 'expired', label: 'Expired' },
];

const POLL_INTERVAL_MS = 30_000;

function relativeTime(epochMs: number): string {
  const delta = epochMs - Date.now();
  const abs = Math.abs(delta);
  const minutes = Math.round(abs / 60_000);
  if (minutes < 1) return delta >= 0 ? '<1m' : '<1m ago';
  const value = minutes < 60
    ? `${minutes}m`
    : minutes < 60 * 24
      ? `${Math.round(minutes / 60)}h`
      : `${Math.round(minutes / (60 * 24))}d`;
  return delta >= 0 ? `in ${value}` : `${value} ago`;
}

const WatchCard: React.FC<{ watch: Watch; onOpen: () => void }> = ({ watch, onOpen }) => {
  const finding = watch.lastFinding;
  return (
    <button
      type="button"
      onClick={onOpen}
      className="w-full text-left border border-border rounded-lg bg-card p-4 hover:bg-muted/40 transition-colors"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="font-medium truncate">{watch.title}</span>
            <WatchStatusBadge status={watch.status} />
            {watch.pendingAction === 'expiry_review' && (
              <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-xs text-amber-600 dark:text-amber-400">
                <ShieldAlert className="size-3" />
                {i18n('Reviewing expiry')}
              </span>
            )}
            {watch.consecutiveFailures > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full bg-destructive/15 px-2 py-0.5 text-xs text-destructive">
                <TriangleAlert className="size-3" />
                {i18n('Consecutive failures')} {watch.consecutiveFailures}
              </span>
            )}
          </div>
          <div className="mt-1 text-xs text-muted-foreground">{watch.condition}</div>
        </div>
        <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <span>
          {i18n('Next check')}: {formatInZone(watch.nextDueAt, watch.schedule.timezone)}
          {watch.status === 'active' ? ` (${relativeTime(watch.nextDueAt)})` : ''}
        </span>
        <span>{scheduleSummary(watch)}</span>
      </div>

      {finding ? (
        <div className="mt-3 rounded-md bg-muted/40 p-2">
          <div className="flex items-center gap-2">
            <VerdictBadge verdict={finding.verdict} />
            {finding.tags.map((tag) => (
              <span
                key={tag.key}
                className="rounded-full border border-border px-2 py-0.5 text-xs text-muted-foreground"
              >
                {tag.label}
              </span>
            ))}
            {finding.stale && (
              <span className="text-xs text-amber-600 dark:text-amber-400">{i18n('Stale result')}</span>
            )}
          </div>
          <div className="mt-1 line-clamp-2 text-sm">{finding.conclusion}</div>
        </div>
      ) : (
        <div className="mt-3 text-xs text-muted-foreground">{i18n('No findings yet')}</div>
      )}
    </button>
  );
};

/**
 * The user's watches: definitions the assistant created, their next run, and the newest conclusion.
 * Polls while the tab is visible; the list API returns the denormalized last finding, so one request
 * paints the whole page.
 */
export const WatchesPage: React.FC = () => {
  const navigate = useNavigate();
  const watches = useWatchStore((s) => s.watches);
  const loading = useWatchStore((s) => s.loading);
  const error = useWatchStore((s) => s.error);
  const load = useWatchStore((s) => s.load);
  const [filter, setFilter] = useState<WatchStatus | 'all'>('all');

  const refresh = useCallback(
    (silent = false) => {
      void load(filter, { silent });
    },
    [filter, load],
  );

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') refresh(true);
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-4xl p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="flex items-center gap-2 text-2xl font-semibold">
              <BellRing className="size-6" />
              {i18n('Watches')}
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              {i18n('Standing intents checked automatically; conclusions land here.')}
            </p>
          </div>
          <button
            type="button"
            onClick={() => refresh()}
            className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
          >
            <RefreshCw className={`size-4 ${loading ? 'animate-spin' : ''}`} />
            {i18n('Refresh')}
          </button>
        </div>

        <div className="mt-4 flex flex-wrap gap-1">
          {FILTERS.map((candidate) => (
            <button
              key={candidate.id}
              type="button"
              onClick={() => setFilter(candidate.id)}
              className={`rounded-md px-3 py-1.5 text-sm ${
                filter === candidate.id
                  ? 'bg-foreground text-background'
                  : 'text-muted-foreground hover:bg-muted'
              }`}
            >
              {i18n(candidate.label)}
            </button>
          ))}
        </div>

        {error && (
          <div className="mt-4 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
            {error}
          </div>
        )}

        <div className="mt-4 space-y-3">
          {watches.map((watch) => (
            <WatchCard
              key={watch.id}
              watch={watch}
              onOpen={() => navigate(`/watches/${watch.id}`)}
            />
          ))}
          {!loading && watches.length === 0 && (
            <div className="rounded-lg border border-dashed border-border p-10 text-center">
              <BellRing className="mx-auto size-8 text-muted-foreground" />
              <div className="mt-3 font-medium">{i18n('No watches yet')}</div>
              <p className="mt-1 text-sm text-muted-foreground">
                {i18n(
                  'Ask the assistant in chat to watch something — for example "check the price tomorrow at 9am".',
                )}
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
