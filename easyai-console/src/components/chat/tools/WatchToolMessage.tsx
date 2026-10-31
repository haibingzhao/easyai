import { useState } from 'react';
import { BellRing, ChevronDown, ShieldAlert } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { ToolRowHeader } from './ToolRowHeader';
import { useStreamingRowExpand } from './useStreamingRowExpand';
import { getToolDisplayName } from './icons';
import { verdictLabel } from '@/components/watch/watch-ui';
import { i18n } from '@/utils/i18n';

interface WatchListItem {
  watchId?: string;
  title?: string;
  status?: string;
  nextRunLocal?: string;
  pendingAction?: string;
}

interface WatchToolView {
  watchId?: string;
  title?: string;
  status?: string;
  pendingAction?: string;
  nextRunLocal?: string;
  schedule?: string;
  timezone?: string;
  untilLocal?: string;
  completeOnMet?: boolean;
  allowedTools: string[];
  tags: Array<{ key: string; label: string }>;
  warnings: string[];
  created?: boolean;
  lastFinding?: { verdict?: string; conclusion?: string };
  isList: boolean;
  watches: WatchListItem[];
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function parseDetails(details: Record<string, unknown> | undefined): WatchToolView {
  const record = details ?? {};
  const tags = Array.isArray(record.tags)
    ? record.tags.flatMap((item) => {
        const tag = asRecord(item);
        const key = stringValue(tag?.key);
        if (!key) return [];
        return [{ key, label: stringValue(tag?.label) ?? key }];
      })
    : [];
  const finding = asRecord(record.lastFinding);
  const watches = Array.isArray(record.watches)
    ? record.watches.flatMap((item) => {
        const row = asRecord(item);
        const watchId = stringValue(row?.watchId);
        if (!watchId) return [];
        return [
          {
            watchId,
            title: stringValue(row?.title),
            status: stringValue(row?.status),
            nextRunLocal: stringValue(row?.nextRunLocal),
            pendingAction: stringValue(row?.pendingAction),
          },
        ];
      })
    : [];
  return {
    watchId: stringValue(record.watchId),
    title: stringValue(record.title),
    status: stringValue(record.status),
    pendingAction: stringValue(record.pendingAction),
    nextRunLocal: stringValue(record.nextRunLocal),
    schedule: stringValue(record.schedule),
    timezone: stringValue(record.timezone),
    untilLocal: stringValue(record.untilLocal),
    completeOnMet: typeof record.completeOnMet === 'boolean' ? record.completeOnMet : undefined,
    allowedTools: stringList(record.allowedTools),
    tags,
    warnings: stringList(record.warnings),
    created: typeof record.created === 'boolean' ? record.created : undefined,
    lastFinding: finding
      ? {
          verdict: stringValue(finding.verdict),
          conclusion: stringValue(finding.conclusion),
        }
      : undefined,
    isList: Array.isArray(record.watches),
    watches,
  };
}

function statusLabel(status: string): string {
  return i18n(status.charAt(0).toUpperCase() + status.slice(1));
}

function parseAction(args: string): string {
  try {
    const parsed = asRecord(JSON.parse(args));
    return stringValue(parsed?.action) ?? 'watch';
  } catch {
    return 'watch';
  }
}

const ACTION_LABEL: Record<string, string> = {
  create: 'Create',
  list: 'List',
  update: 'Update',
  pause: 'Pause',
  resume: 'Resume',
  cancel: 'Cancel',
};

function actionLabel(raw: string): string {
  return i18n(ACTION_LABEL[raw] ?? 'Watch');
}

const STATUS_STYLE: Record<string, string> = {
  active: 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400',
  paused: 'bg-amber-500/15 text-amber-600 dark:text-amber-400',
  completed: 'bg-blue-500/15 text-blue-600 dark:text-blue-400',
  expired: 'bg-muted text-muted-foreground',
  cancelled: 'bg-muted text-muted-foreground',
};

/**
 * Watch tool card. Everything shown here comes from `result.details` — the prose in `result.result`
 * is addressed to the model and is never parsed. The next run is the server-resolved absolute time,
 * which is the whole point of the tool returning it.
 */
export function WatchToolMessage({ toolCall, result, status, compact }: ToolMessageProps) {
  const view = parseDetails(result?.details);
  const action = actionLabel(parseAction(toolCall.args));
  const isStreaming = (status === 'RUNNING' || status === 'PENDING') && !result;
  const [expanded, setExpanded] = useState(isStreaming);
  const markTouched = useStreamingRowExpand(status, setExpanded, !!result);
  const toggle = () => {
    markTouched();
    setExpanded((prev) => !prev);
  };

  const summary = view.title
    ? `${action} · ${view.title}`
    : view.watchId
      ? `${action} · ${view.watchId}`
      : action;
  const rowHeader = (
    <ToolRowHeader
      toolName={toolCall.toolName}
      status={status ?? 'PENDING'}
      summary={summary}
      expanded={expanded}
      onToggle={toggle}
    />
  );
  if (compact && !expanded) return rowHeader;

  return (
    <div className={compact ? 'overflow-hidden' : 'border border-border rounded-lg bg-card overflow-hidden'}>
      {compact ? (
        rowHeader
      ) : (
        <div
          className="p-3 flex items-center justify-between gap-2 cursor-pointer hover:bg-muted/50 transition-colors"
          onClick={toggle}
        >
          <div className="flex min-w-0 items-center gap-2">
            <BellRing className="w-4 h-4 shrink-0 text-muted-foreground" />
            <span className="text-sm font-medium">{getToolDisplayName(toolCall.toolName)}</span>
            <span className="truncate text-xs text-muted-foreground">{summary}</span>
          </div>
          <ChevronDown className={`w-4 h-4 shrink-0 transition-transform ${expanded ? 'rotate-180' : ''}`} />
        </div>
      )}

      {expanded && (
        <div className="border-t border-border p-3 space-y-2 text-sm">
          {view.status && (
            <div className="flex flex-wrap items-center gap-2">
              <span
                className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                  STATUS_STYLE[view.status] ?? 'bg-muted text-muted-foreground'
                }`}
              >
                {statusLabel(view.status)}
              </span>
              {view.pendingAction === 'expiry_review' && (
                <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-xs text-amber-600 dark:text-amber-400">
                  <ShieldAlert className="size-3" />
                  {i18n('Reviewing expiry')}
                </span>
              )}
              {view.created === false && (
                <span className="text-xs text-muted-foreground">{i18n('Existing watch returned')}</span>
              )}
            </div>
          )}

          {view.nextRunLocal && view.status === 'active' && (
            <div className="text-xs text-muted-foreground">
              {i18n('Next check')}: <span className="text-foreground">{view.nextRunLocal}</span>
              {view.timezone ? ` · ${view.timezone}` : ''}
            </div>
          )}
          {view.schedule && <div className="text-xs text-muted-foreground">{view.schedule}</div>}
          {view.completeOnMet !== undefined && (
            <div className="text-xs text-muted-foreground">
              {i18n('Complete when met')}: {i18n(view.completeOnMet ? 'Enabled' : 'Disabled')}
            </div>
          )}
          {view.untilLocal && (
            <div className="text-xs text-muted-foreground">
              {i18n('Until')}: {view.untilLocal}
            </div>
          )}
          {view.tags.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {view.tags.map((tag) => (
                <span
                  key={tag.key}
                  className="rounded-full border border-border px-2 py-0.5 text-xs text-muted-foreground"
                >
                  {tag.label} <span className="opacity-60">{tag.key}</span>
                </span>
              ))}
            </div>
          )}
          {view.allowedTools.length > 0 && (
            <div className="text-xs text-muted-foreground">
              {i18n('Allowed tools')}: {view.allowedTools.join(', ')}
            </div>
          )}
          {view.warnings.map((warning) => (
            <div
              key={warning}
              className="rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-700 dark:text-amber-300"
            >
              {warning}
            </div>
          ))}
          {view.lastFinding?.conclusion && (
            <div className="rounded-md bg-muted/40 p-2">
              <div className="text-xs font-medium text-muted-foreground">
                {i18n('Findings')}: {view.lastFinding.verdict ? verdictLabel(view.lastFinding.verdict) : '—'}
              </div>
              <div className="mt-1 text-sm">{view.lastFinding.conclusion}</div>
            </div>
          )}
          {view.watches.length > 0 && (
            <div className="space-y-1.5">
              {view.watches.map((item) => (
                <div key={item.watchId} className="rounded-md border border-border p-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium">{item.title ?? item.watchId}</span>
                    {item.status && (
                      <span
                        className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                          STATUS_STYLE[item.status] ?? 'bg-muted text-muted-foreground'
                        }`}
                      >
                        {statusLabel(item.status)}
                      </span>
                    )}
                    {item.pendingAction === 'expiry_review' && (
                      <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-xs text-amber-600 dark:text-amber-400">
                        <ShieldAlert className="size-3" />
                        {i18n('Reviewing expiry')}
                      </span>
                    )}
                  </div>
                  {item.nextRunLocal && item.status === 'active' && (
                    <div className="mt-1 text-xs text-muted-foreground">
                      {i18n('Next check')}: {item.nextRunLocal}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
          {view.isList && view.watches.length === 0 && (
            <div className="text-xs text-muted-foreground">
              {result?.result || i18n('No watches yet')}
            </div>
          )}
          {!view.status && !view.watchId && !view.isList && (
            <div className="text-xs text-muted-foreground">
              {result?.isError ? result.result : i18n('No watch returned')}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
