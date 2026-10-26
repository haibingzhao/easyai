import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft,
  BellRing,
  ExternalLink,
  PauseCircle,
  Pencil,
  PlayCircle,
  Plus,
  RefreshCw,
  Trash2,
  TriangleAlert,
  XCircle,
} from 'lucide-react';
import { Dialog } from '@/components/ui/Dialog';
import {
  formatInZone,
  scheduleSummary,
  UnknownVerdictBadge,
  VerdictBadge,
  WatchStatusBadge,
  weekdayLabel,
} from '@/components/watch/watch-ui';
import { useWatchStore } from '@/services/stores/watch-store';
import type {
  Watch,
  WatchFindingTask,
  WatchMode,
  WatchNotifyPolicy,
  WatchScheduleDraft,
  WatchScheduleKind,
  WatchTag,
  WatchUpdatePayload,
} from '@/types/watch';
import { isWatchTerminal, parseWatchRunResult } from '@/types/watch';
import { i18n } from '@/utils/i18n';

const POLL_INTERVAL_MS = 30_000;
const INPUT_CLASS =
  'w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring';
const LABEL_CLASS = 'mb-1 block text-xs font-medium text-muted-foreground';

const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

interface EditState {
  title: string;
  condition: string;
  mode: WatchMode;
  reminder: string;
  kind: WatchScheduleKind;
  atDate: string;
  time: string;
  weekday: string;
  dayOfMonth: string;
  timezone: string;
  until: string;
  tags: WatchTag[];
  completeOnMet: boolean;
  notifyPolicy: WatchNotifyPolicy;
  notifyTags: string[];
  scheduleDirty: boolean;
}

/** Wall-clock fields of an instant in a specific zone, for `<input type=date|time>` values. */
function zonedParts(epochMs: number, timeZone: string): { date: string; time: string } {
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).formatToParts(new Date(epochMs));
    const get = (type: string) => parts.find((part) => part.type === type)?.value ?? '';
    const hour = get('hour') === '24' ? '00' : get('hour');
    return { date: `${get('year')}-${get('month')}-${get('day')}`, time: `${hour}:${get('minute')}` };
  } catch {
    const date = new Date(epochMs);
    const pad = (value: number) => String(value).padStart(2, '0');
    return {
      date: `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
      time: `${pad(date.getHours())}:${pad(date.getMinutes())}`,
    };
  }
}

function editStateOf(watch: Watch): EditState {
  const atParts =
    watch.schedule.kind === 'at' && watch.schedule.anchorAt != null
      ? zonedParts(watch.schedule.anchorAt, watch.schedule.timezone)
      : null;
  return {
    title: watch.title,
    condition: watch.condition,
    mode: watch.mode,
    reminder: watch.reminder ?? '',
    kind: watch.schedule.kind,
    atDate: atParts?.date ?? '',
    time: atParts?.time ?? watch.schedule.localTime ?? '09:00',
    weekday: watch.schedule.weekday ?? 'MONDAY',
    dayOfMonth: String(watch.schedule.dayOfMonth ?? 1),
    timezone: watch.schedule.timezone,
    until: watch.untilAt ? zonedParts(watch.untilAt, watch.schedule.timezone).date : '',
    tags: watch.tags.map((tag) => ({ ...tag })),
    completeOnMet: watch.completeOnMet,
    notifyPolicy: watch.notifyPolicy,
    notifyTags: [...watch.notifyTags],
    // A terminal watch has no future run left; saving must carry a new schedule to renew it.
    scheduleDirty: isWatchTerminal(watch.status),
  };
}

function buildSchedule(state: EditState): WatchScheduleDraft | null {
  if (state.kind === 'at') {
    if (!state.atDate || !state.time) return null;
    return { at: `${state.atDate}T${state.time}` };
  }
  if (!state.time) return null;
  if (state.kind === 'daily') return { every: 'daily', time: state.time };
  if (state.kind === 'weekly') {
    return { every: 'weekly', time: state.time, weekday: state.weekday };
  }
  const day = Number(state.dayOfMonth);
  if (!Number.isInteger(day) || day < 1 || day > 31) return null;
  return { every: 'monthly', time: state.time, dayOfMonth: day };
}

const NOTIFY_OPTIONS: Array<{ value: WatchNotifyPolicy; label: string }> = [
  { value: 'on_change', label: 'On change' },
  { value: 'on_complete', label: 'On completion' },
  { value: 'every', label: 'Every run' },
  { value: 'never', label: 'Never' },
];

const TASK_STATUS: Record<WatchFindingTask['status'], string> = {
  pending: 'Pending',
  running: 'Running',
  completed: 'Completed',
  failed: 'Failed',
  timed_out: 'Timed out',
  cancelled: 'Cancelled',
  interrupted: 'Interrupted',
};

const FindingEntry: React.FC<{
  task: WatchFindingTask;
  timeZone: string;
  onOpenTranscript: () => void;
}> = ({ task, timeZone, onOpenTranscript }) => {
  const parsed = parseWatchRunResult(task.result);
  const skipped = parsed == null && task.result?.kind === 'skipped' ? task.result : null;
  const skippedReason = typeof skipped?.reason === 'string' ? skipped.reason : '';
  const decisionLabel = parsed?.kind === 'expiry_review' && parsed.decision
    ? parsed.decision === 'renew'
      ? i18n('Renew')
      : parsed.decision === 'complete'
        ? i18n('Completed')
        : i18n('Expired')
    : '—';
  return (
    <div className="border border-border rounded-lg bg-card p-3">
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
        <div className="flex items-center gap-2">
          <span>{new Date(task.createdAt).toLocaleString()}</span>
          <span className="rounded bg-muted px-1.5 py-0.5">{i18n(TASK_STATUS[task.status])}</span>
          {parsed?.stale && (
            <span className="text-amber-600 dark:text-amber-400">{i18n('Stale result')}</span>
          )}
        </div>
        <button
          type="button"
          onClick={onOpenTranscript}
          className="inline-flex items-center gap-1 hover:text-foreground"
        >
          <ExternalLink className="size-3" />
          {i18n('Transcript')}
        </button>
      </div>

      {task.error && (
        <div className="mt-2 rounded-md bg-destructive/10 p-2 text-sm text-destructive">
          {task.error}
        </div>
      )}

      {parsed?.kind === 'check' && (
        <div className="mt-2">
          <div className="flex flex-wrap items-center gap-2">
            {parsed.verdict ? (
              <VerdictBadge verdict={parsed.verdict} />
            ) : (
              <UnknownVerdictBadge />
            )}
            {parsed.tags.map((tag) => (
              <span
                key={tag.key}
                className="rounded-full border border-border px-2 py-0.5 text-xs text-muted-foreground"
              >
                {tag.label}
              </span>
            ))}
          </div>
          <p className="mt-2 whitespace-pre-wrap text-sm">{parsed.conclusion}</p>
          {parsed.sources.length > 0 && (
            <div className="mt-2 flex flex-wrap gap-2 text-xs">
              {parsed.sources.map((source) => (
                <a
                  key={source.url}
                  href={source.url}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 text-primary hover:underline"
                >
                  <ExternalLink className="size-3" />
                  {source.label}
                </a>
              ))}
            </div>
          )}
        </div>
      )}

      {parsed?.kind === 'expiry_review' && (
        <div className="mt-2">
          <div className="text-xs font-medium text-muted-foreground">
            {i18n('Expiry decision')}: {decisionLabel}
          </div>
          {parsed.conclusion && <p className="mt-1 text-sm">{parsed.conclusion}</p>}
          {parsed.reason && (
            <p className="mt-1 text-xs text-muted-foreground">
              {i18n('Reason')}: {parsed.reason}
            </p>
          )}
          {(parsed.newUntil || parsed.rescheduleAt) && (
            <p className="mt-1 text-xs text-muted-foreground">
              {i18n('Next check')}: {formatInZone(parsed.newUntil ?? parsed.rescheduleAt, timeZone)}
            </p>
          )}
        </div>
      )}

      {skipped && (
        <div className="mt-2 text-sm text-muted-foreground">
          {i18n('Skipped')}
          {skippedReason ? `: ${skippedReason}` : ''}
        </div>
      )}

      {!parsed && !task.error && !skipped && (
        <div className="mt-2 text-sm text-muted-foreground">{i18n('No findings yet')}</div>
      )}
    </div>
  );
};

/**
 * One watch: definition, manual controls, and the timeline of every firing.
 *
 * Editing a COMPLETED/EXPIRED watch always carries a schedule — that is the renewal path. An
 * ACTIVE watch with `pending_action=expiry_review` can still be edited; saving a new schedule wins
 * over the queued review, which the version guard then discards.
 */
export const WatchDetailPage: React.FC = () => {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const detail = useWatchStore((s) => s.detail);
  const findings = useWatchStore((s) => s.findings);
  const detailLoading = useWatchStore((s) => s.detailLoading);
  const findingsLoading = useWatchStore((s) => s.findingsLoading);
  const error = useWatchStore((s) => s.error);
  const loadDetail = useWatchStore((s) => s.loadDetail);
  const loadFindings = useWatchStore((s) => s.loadFindings);
  const updateWatch = useWatchStore((s) => s.updateWatch);
  const pause = useWatchStore((s) => s.pause);
  const resume = useWatchStore((s) => s.resume);
  const cancel = useWatchStore((s) => s.cancel);
  const runNow = useWatchStore((s) => s.runNow);
  const remove = useWatchStore((s) => s.remove);
  const clearDetail = useWatchStore((s) => s.clearDetail);
  const clearError = useWatchStore((s) => s.clearError);

  const [editOpen, setEditOpen] = useState(false);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<'cancel' | 'delete' | null>(null);

  const refresh = useCallback(
    (silent = false) => {
      if (!id) return;
      void loadDetail(id, { silent });
      void loadFindings(id, { silent });
    },
    [id, loadDetail, loadFindings],
  );

  useEffect(() => {
    clearDetail();
    setEditOpen(false);
    setNotice(null);
    refresh();
    return () => clearDetail();
  }, [id, refresh, clearDetail]);

  useEffect(() => {
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible' && detail?.status === 'active') refresh(true);
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [refresh, detail?.status]);

  const watch = detail && detail.id === id ? detail : null;

  const definitionStale = useMemo(() => {
    if (!watch?.lastFinding) return false;
    return watch.version > watch.lastFinding.definitionVersion;
  }, [watch]);

  const openEdit = () => {
    if (!watch) return;
    setEdit(editStateOf(watch));
    setFormError(null);
    setEditOpen(true);
  };

  const save = async () => {
    if (!watch || !edit) return;
    setFormError(null);
    if (!edit.title.trim()) {
      setFormError(i18n('Title is required'));
      return;
    }
    if (!edit.condition.trim()) {
      setFormError(i18n('Condition is required'));
      return;
    }
    if (edit.mode === 'remind' && !edit.reminder.trim()) {
      setFormError(i18n('Reminder text is required'));
      return;
    }
    const payload: WatchUpdatePayload = {
      title: edit.title.trim(),
      condition: edit.condition.trim(),
      mode: edit.mode,
      reminder: edit.mode === 'remind' ? edit.reminder.trim() : undefined,
      timezone: edit.timezone.trim(),
      tags: edit.tags
        .map((tag) => ({
          key: tag.key.trim(),
          label: tag.label.trim(),
          description: tag.description?.trim() || null,
        }))
        .filter((tag) => tag.key !== '' && tag.label !== ''),
      completeOnMet: edit.completeOnMet,
      notifyPolicy: edit.notifyPolicy,
      notifyTags: edit.notifyTags,
      until: edit.until || undefined,
      clearUntil: !edit.until && watch.untilAt != null,
    };
    if (edit.scheduleDirty || isWatchTerminal(watch.status)) {
      const schedule = buildSchedule(edit);
      if (!schedule) {
        setFormError(
          edit.kind === 'at'
            ? i18n('Pick a date and time for the next run')
            : i18n('Fill in the schedule fields'),
        );
        return;
      }
      payload.schedule = schedule;
    }
    const saved = await updateWatch(watch.id, payload);
    if (!saved) return;
    setEditOpen(false);
    setNotice(i18n('Saved'));
    refresh(true);
  };

  const doRunNow = async () => {
    if (!watch) return;
    const taskId = await runNow(watch.id);
    if (!taskId) return;
    setNotice(`${i18n('Check now requested')} · ${taskId}`);
    setTimeout(() => refresh(true), 1500);
  };

  const doCancel = async () => {
    if (!watch) return;
    setConfirm(null);
    const updated = await cancel(watch.id);
    if (updated) setNotice(i18n('Cancelling stops future checks; the definition stays visible.'));
  };

  const doDelete = async () => {
    if (!watch) return;
    setConfirm(null);
    if (await remove(watch.id)) navigate('/watches');
  };

  if (detailLoading && !watch) {
    return <div className="p-6 text-sm text-muted-foreground">{i18n('Loading...')}</div>;
  }

  if (!watch) {
    return (
      <div className="p-6">
        <button
          type="button"
          onClick={() => navigate('/watches')}
          className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" />
          {i18n('Watches')}
        </button>
        <div className="mt-4 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
          {error ?? i18n('Watch not found')}
        </div>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-4xl p-6">
        <div className="flex items-center justify-between gap-3">
          <button
            type="button"
            onClick={() => navigate('/watches')}
            className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
          >
            <ArrowLeft className="size-4" />
            {i18n('Watches')}
          </button>
          <div className="flex flex-wrap items-center gap-2">
            {watch.status === 'active' && (
              <>
                <button
                  type="button"
                  onClick={doRunNow}
                  className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
                >
                  <PlayCircle className="size-4" />
                  {i18n('Check now')}
                </button>
                <button
                  type="button"
                  onClick={() => void pause(watch.id)}
                  className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
                >
                  <PauseCircle className="size-4" />
                  {i18n('Pause')}
                </button>
              </>
            )}
            {watch.status === 'paused' && (
              <button
                type="button"
                onClick={() => void resume(watch.id)}
                className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
              >
                <PlayCircle className="size-4" />
                {i18n('Resume')}
              </button>
            )}
            <button
              type="button"
              onClick={openEdit}
              className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
            >
              <Pencil className="size-4" />
              {i18n('Edit')}
            </button>
            <button
              type="button"
              onClick={() => refresh()}
              className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
            >
              <RefreshCw className={`size-4 ${detailLoading ? 'animate-spin' : ''}`} />
            </button>
            {(watch.status === 'active' || watch.status === 'paused') && (
              <button
                type="button"
                onClick={() => setConfirm('cancel')}
                className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
              >
                <XCircle className="size-4" />
                {i18n('Cancel watch')}
              </button>
            )}
            <button
              type="button"
              onClick={() => setConfirm('delete')}
              className="inline-flex items-center gap-1.5 rounded-md border border-destructive/40 px-3 py-1.5 text-sm text-destructive hover:bg-destructive/10"
            >
              <Trash2 className="size-4" />
              {i18n('Delete')}
            </button>
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-2">
          <BellRing className="size-5 text-muted-foreground" />
          <h1 className="text-xl font-semibold">{watch.title}</h1>
          <WatchStatusBadge status={watch.status} />
          {watch.pendingAction === 'expiry_review' && (
            <span className="rounded-full bg-amber-500/15 px-2 py-0.5 text-xs text-amber-600 dark:text-amber-400">
              {i18n('Reviewing expiry')}
            </span>
          )}
        </div>

        {notice && (
          <div className="mt-3 rounded-md border border-border bg-muted/50 p-3 text-sm">{notice}</div>
        )}
        {error && (
          <div className="mt-3 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
            <div className="flex items-start justify-between gap-3">
              <span>{error}</span>
              <button type="button" onClick={clearError} className="text-xs underline">
                {i18n('Dismiss')}
              </button>
            </div>
          </div>
        )}
        {definitionStale && (
          <div className="mt-3 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm text-amber-700 dark:text-amber-300">
            {i18n('Definition updated; waiting for the next check.')}
          </div>
        )}
        {watch.pendingAction === 'expiry_review' && (
          <div className="mt-3 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm text-amber-700 dark:text-amber-300">
            {i18n('An expiry review is pending; you can still edit the schedule to override it.')}
          </div>
        )}
        {isWatchTerminal(watch.status) && (
          <div className="mt-3 rounded-md border border-border bg-muted/40 p-3 text-sm text-muted-foreground">
            {i18n('This watch has ended. Supplying a new schedule renews it.')}
          </div>
        )}
        {watch.consecutiveFailures > 0 && (
          <div className="mt-3 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
            <span className="inline-flex items-center gap-1">
              <TriangleAlert className="size-4" />
              {i18n('Consecutive failures')}: {watch.consecutiveFailures}
            </span>
            {watch.lastError && <div className="mt-1 text-xs">{watch.lastError}</div>}
          </div>
        )}

        <div className="mt-5 grid gap-3 sm:grid-cols-2">
          <div className="rounded-lg border border-border bg-card p-4">
            <div className={LABEL_CLASS}>{i18n('Condition')}</div>
            <div className="whitespace-pre-wrap text-sm">{watch.condition}</div>
            {watch.mode === 'remind' && watch.reminder && (
              <>
                <div className={`${LABEL_CLASS} mt-3`}>{i18n('Reminder text')}</div>
                <div className="text-sm">{watch.reminder}</div>
              </>
            )}
          </div>
          <div className="rounded-lg border border-border bg-card p-4 text-sm">
            <div className={LABEL_CLASS}>{i18n('Schedule')}</div>
            <div>{scheduleSummary(watch)}</div>
            <div className="mt-2 text-xs text-muted-foreground">
              {i18n('Next check')}: {formatInZone(watch.nextDueAt, watch.schedule.timezone)}
            </div>
            <div className="text-xs text-muted-foreground">
              {i18n('Until')}:{' '}
              {watch.untilAt ? formatInZone(watch.untilAt, watch.schedule.timezone) : i18n('No deadline')}
            </div>
            <div className="mt-2 text-xs text-muted-foreground">
              {i18n('Notify policy')}: {i18n(NOTIFY_OPTIONS.find((o) => o.value === watch.notifyPolicy)?.label ?? watch.notifyPolicy)}
            </div>
            <div className="text-xs text-muted-foreground">
              {i18n('Automatic renewals')}: {watch.extensionCount}
            </div>
          </div>
          <div className="rounded-lg border border-border bg-card p-4 text-sm">
            <div className={LABEL_CLASS}>{i18n('Tags')}</div>
            {watch.tags.length === 0 ? (
              <div className="text-muted-foreground">{i18n('No tags defined')}</div>
            ) : (
              <div className="flex flex-wrap gap-1.5">
                {watch.tags.map((tag) => (
                  <span
                    key={tag.key}
                    title={tag.description ?? undefined}
                    className="rounded-full border border-border px-2 py-0.5 text-xs"
                  >
                    {tag.label} <span className="text-muted-foreground">{tag.key}</span>
                  </span>
                ))}
              </div>
            )}
          </div>
          <div className="rounded-lg border border-border bg-card p-4 text-sm">
            <div className={LABEL_CLASS}>{i18n('Allowed tools')}</div>
            <div>{watch.allowedTools.length > 0 ? watch.allowedTools.join(', ') : '—'}</div>
            {watch.sourceHints && (
              <>
                <div className={`${LABEL_CLASS} mt-3`}>{i18n('Source hints')}</div>
                <div className="whitespace-pre-wrap text-sm">{watch.sourceHints}</div>
              </>
            )}
          </div>
        </div>

        <div className="mt-6">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold">{i18n('Findings')}</h2>
            <span className="text-xs text-muted-foreground">
              {i18n('Watch checks report here; the original chat is not updated.')}
            </span>
          </div>
          <div className="mt-3 space-y-2">
            {findings.map((task) => (
              <FindingEntry
                key={task.taskId}
                task={task}
                timeZone={watch.schedule.timezone}
                onOpenTranscript={() => navigate(`/tasks/${task.taskId}/transcript`)}
              />
            ))}
            {!findingsLoading && findings.length === 0 && (
              <div className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
                {i18n('No findings yet')}
              </div>
            )}
          </div>
        </div>

        {edit && (
          <Dialog
            open={editOpen}
            onClose={() => setEditOpen(false)}
            title={isWatchTerminal(watch.status) ? i18n('Renew') : i18n('Edit')}
          >
            <div className="space-y-3">
              <div>
                <label className={LABEL_CLASS}>{i18n('Title')}</label>
                <input
                  className={INPUT_CLASS}
                  value={edit.title}
                  onChange={(event) => setEdit({ ...edit, title: event.target.value })}
                />
              </div>
              <div>
                <label className={LABEL_CLASS}>{i18n('Condition')}</label>
                <textarea
                  className={INPUT_CLASS}
                  rows={3}
                  value={edit.condition}
                  onChange={(event) => setEdit({ ...edit, condition: event.target.value })}
                />
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <label className={LABEL_CLASS}>{i18n('Mode')}</label>
                  <select
                    className={INPUT_CLASS}
                    value={edit.mode}
                    onChange={(event) =>
                      setEdit({ ...edit, mode: event.target.value as WatchMode })
                    }
                  >
                    <option value="check">{i18n('Check — gather evidence and judge')}</option>
                    <option value="remind">{i18n('Remind — deliver a text')}</option>
                  </select>
                </div>
                <div>
                  <label className={LABEL_CLASS}>{i18n('Timezone')}</label>
                  <input
                    className={INPUT_CLASS}
                    value={edit.timezone}
                    onChange={(event) =>
                      setEdit({ ...edit, timezone: event.target.value, scheduleDirty: true })
                    }
                  />
                </div>
              </div>
              {edit.mode === 'remind' && (
                <div>
                  <label className={LABEL_CLASS}>{i18n('Reminder text')}</label>
                  <textarea
                    className={INPUT_CLASS}
                    rows={2}
                    value={edit.reminder}
                    onChange={(event) => setEdit({ ...edit, reminder: event.target.value })}
                  />
                </div>
              )}

              <div className="rounded-md border border-border p-3">
                <div className="grid gap-3 sm:grid-cols-2">
                  <div>
                    <label className={LABEL_CLASS}>{i18n('Schedule')}</label>
                    <select
                      className={INPUT_CLASS}
                      value={edit.kind}
                      onChange={(event) =>
                        setEdit({
                          ...edit,
                          kind: event.target.value as WatchScheduleKind,
                          scheduleDirty: true,
                        })
                      }
                    >
                      <option value="at">{i18n('One time')}</option>
                      <option value="daily">{i18n('Daily')}</option>
                      <option value="weekly">{i18n('Weekly')}</option>
                      <option value="monthly">{i18n('Monthly')}</option>
                    </select>
                  </div>
                  {edit.kind === 'at' && (
                    <div>
                      <label className={LABEL_CLASS}>{i18n('Date')}</label>
                      <input
                        type="date"
                        className={INPUT_CLASS}
                        value={edit.atDate}
                        onChange={(event) =>
                          setEdit({ ...edit, atDate: event.target.value, scheduleDirty: true })
                        }
                      />
                    </div>
                  )}
                  {edit.kind === 'weekly' && (
                    <div>
                      <label className={LABEL_CLASS}>{i18n('Weekday')}</label>
                      <select
                        className={INPUT_CLASS}
                        value={edit.weekday}
                        onChange={(event) =>
                          setEdit({ ...edit, weekday: event.target.value, scheduleDirty: true })
                        }
                      >
                        {WEEKDAYS.map((day) => (
                          <option key={day} value={day}>
                            {weekdayLabel(day)}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}
                  {edit.kind === 'monthly' && (
                    <div>
                      <label className={LABEL_CLASS}>{i18n('Day of month')}</label>
                      <input
                        type="number"
                        min={1}
                        max={31}
                        className={INPUT_CLASS}
                        value={edit.dayOfMonth}
                        onChange={(event) =>
                          setEdit({ ...edit, dayOfMonth: event.target.value, scheduleDirty: true })
                        }
                      />
                    </div>
                  )}
                  {edit.kind !== 'at' && (
                    <div>
                      <label className={LABEL_CLASS}>{i18n('Time')}</label>
                      <input
                        type="time"
                        className={INPUT_CLASS}
                        value={edit.time}
                        onChange={(event) =>
                          setEdit({ ...edit, time: event.target.value, scheduleDirty: true })
                        }
                      />
                    </div>
                  )}
                </div>
                {edit.kind === 'at' && (
                  <div className="mt-3">
                    <label className={LABEL_CLASS}>{i18n('Time')}</label>
                    <input
                      type="time"
                      className={INPUT_CLASS}
                      value={edit.time}
                      onChange={(event) =>
                        setEdit({ ...edit, time: event.target.value, scheduleDirty: true })
                      }
                    />
                  </div>
                )}
              </div>

              <div>
                <label className={LABEL_CLASS}>{i18n('Until')}</label>
                <input
                  type="date"
                  className={INPUT_CLASS}
                  value={edit.until}
                  onChange={(event) => setEdit({ ...edit, until: event.target.value })}
                />
              </div>

              <div>
                <div className="flex items-center justify-between">
                  <label className={LABEL_CLASS}>{i18n('Tags')}</label>
                  <button
                    type="button"
                    onClick={() =>
                      setEdit({
                        ...edit,
                        tags: [...edit.tags, { key: '', label: '', description: null }],
                      })
                    }
                    className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                  >
                    <Plus className="size-3" />
                    {i18n('Add')}
                  </button>
                </div>
                <div className="space-y-2">
                  {edit.tags.map((tag, index) => (
                    <div key={index} className="grid gap-2 sm:grid-cols-[1fr_1fr_2fr_auto]">
                      <input
                        className={INPUT_CLASS}
                        placeholder={i18n('Key')}
                        value={tag.key}
                        onChange={(event) => {
                          const tags = [...edit.tags];
                          tags[index] = { ...tag, key: event.target.value };
                          setEdit({ ...edit, tags });
                        }}
                      />
                      <input
                        className={INPUT_CLASS}
                        placeholder={i18n('Label')}
                        value={tag.label}
                        onChange={(event) => {
                          const tags = [...edit.tags];
                          tags[index] = { ...tag, label: event.target.value };
                          setEdit({ ...edit, tags });
                        }}
                      />
                      <input
                        className={INPUT_CLASS}
                        placeholder={i18n('Description')}
                        value={tag.description ?? ''}
                        onChange={(event) => {
                          const tags = [...edit.tags];
                          tags[index] = { ...tag, description: event.target.value };
                          setEdit({ ...edit, tags });
                        }}
                      />
                      <button
                        type="button"
                        onClick={() => {
                          const removed = tag.key;
                          setEdit({
                            ...edit,
                            tags: edit.tags.filter((_, position) => position !== index),
                            notifyTags: edit.notifyTags.filter((key) => key !== removed),
                          });
                        }}
                        className="rounded-md border border-border px-2 text-muted-foreground hover:bg-muted"
                      >
                        <XCircle className="size-4" />
                      </button>
                    </div>
                  ))}
                </div>
              </div>

              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <label className={LABEL_CLASS}>{i18n('Notify policy')}</label>
                  <select
                    className={INPUT_CLASS}
                    value={edit.notifyPolicy}
                    onChange={(event) =>
                      setEdit({
                        ...edit,
                        notifyPolicy: event.target.value as WatchNotifyPolicy,
                      })
                    }
                  >
                    {NOTIFY_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {i18n(option.label)}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className={LABEL_CLASS}>{i18n('Complete when met')}</label>
                  <label className="mt-2 inline-flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={edit.completeOnMet}
                      onChange={(event) =>
                        setEdit({ ...edit, completeOnMet: event.target.checked })
                      }
                    />
                    {i18n('Mark completed when the condition is met')}
                  </label>
                </div>
              </div>

              {edit.tags.length > 0 && (
                <div>
                  <label className={LABEL_CLASS}>{i18n('Always notify on these tags')}</label>
                  <div className="flex flex-wrap gap-2">
                    {edit.tags.map((tag) => (
                      <label key={tag.key} className="inline-flex items-center gap-1 text-xs">
                        <input
                          type="checkbox"
                          disabled={!tag.key}
                          checked={edit.notifyTags.includes(tag.key)}
                          onChange={(event) =>
                            setEdit({
                              ...edit,
                              notifyTags: event.target.checked
                                ? [...edit.notifyTags, tag.key]
                                : edit.notifyTags.filter((key) => key !== tag.key),
                            })
                          }
                        />
                        {tag.label || tag.key}
                      </label>
                    ))}
                  </div>
                </div>
              )}

              {formError && (
                <div className="rounded-md border border-destructive/40 bg-destructive/10 p-2 text-sm text-destructive">
                  {formError}
                </div>
              )}

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setEditOpen(false)}
                  className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
                >
                  {i18n('Cancel')}
                </button>
                <button
                  type="button"
                  onClick={() => void save()}
                  className="rounded-md bg-foreground px-3 py-1.5 text-sm text-background hover:opacity-90"
                >
                  {i18n('Save')}
                </button>
              </div>
            </div>
          </Dialog>
        )}

        <Dialog
          open={confirm !== null}
          onClose={() => setConfirm(null)}
          title={confirm === 'delete' ? i18n('Delete watch') : i18n('Cancel watch')}
        >
          <p className="text-sm text-muted-foreground">
            {confirm === 'delete'
              ? i18n('Deleting this watch removes its definition; its past runs stay in Tasks.')
              : i18n('Cancelling stops future checks; the definition stays visible.')}
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setConfirm(null)}
              className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
            >
              {i18n('Cancel')}
            </button>
            <button
              type="button"
              onClick={() => (confirm === 'delete' ? void doDelete() : void doCancel())}
              className="rounded-md bg-destructive px-3 py-1.5 text-sm text-destructive-foreground hover:opacity-90"
            >
              {confirm === 'delete' ? i18n('Delete') : i18n('Cancel watch')}
            </button>
          </div>
        </Dialog>
      </div>
    </div>
  );
};
