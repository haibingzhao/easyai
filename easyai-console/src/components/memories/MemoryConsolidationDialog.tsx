import React, { useEffect, useMemo, useRef, useState } from 'react';
import { AlertCircle, CheckCircle2, Eye, HelpCircle, Layers, Loader2, Play } from 'lucide-react';
import { Dialog } from '@/components/ui/Dialog';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { ConsolidationSwitch } from '@/components/memories/ConsolidationSwitch';
import { useMemoryStore } from '@/services/stores/memory-store';
import { useCategoryStore } from '@/services/stores/category-store';
import type { ConsolidationReport } from '@/types/memory';
import { MEMORY_CONTENT_TARGET_CHARS, sliceKeyOf } from '@/types/memory';
import { nextOccurrences } from '@/utils/cron';
import { i18n } from '@/utils/i18n';

/** Field order and value ranges shown in the cron help tooltip, matching the backend parser. */
const CRON_FIELDS = [
  { labelKey: 'Cron field seconds', rangeKey: 'Cron range seconds' },
  { labelKey: 'Cron field minutes', rangeKey: 'Cron range minutes' },
  { labelKey: 'Cron field hours', rangeKey: 'Cron range hours' },
  { labelKey: 'Cron field day of month', rangeKey: 'Cron range day of month' },
  { labelKey: 'Cron field month', rangeKey: 'Cron range month' },
  { labelKey: 'Cron field day of week', rangeKey: 'Cron range day of week' },
];

const CRON_EXAMPLES = [
  { expr: '0 0 3 * * *', descKey: 'Cron example daily 3am' },
  { expr: '0 30 9 * * 1-5', descKey: 'Cron example weekdays 9:30' },
  { expr: '0 */15 * * * *', descKey: 'Cron example every 15 min' },
];

interface MemoryConsolidationDialogProps {
  open: boolean;
  onClose: () => void;
  /** Current real project path; when null only the global scope can be consolidated. */
  currentProjectPath: string | null;
  onViewTasks: () => void;
}

/**
 * Per-user consolidation settings: master switch, per-type thresholds, scan schedule, and a
 * single-type run with a dry-run preview. A real run creates an async task, so the dialog only
 * reports the task id — the transcript and outcome live on the Tasks page.
 */
export const MemoryConsolidationDialog: React.FC<MemoryConsolidationDialogProps> = ({
  open,
  onClose,
  currentProjectPath,
  onViewTasks,
}) => {
  const consolidation = useMemoryStore((s) => s.consolidation);
  const entries = useMemoryStore((s) => s.entries);
  const error = useMemoryStore((s) => s.error);
  const clearError = useMemoryStore((s) => s.clearError);
  const saveConsolidation = useMemoryStore((s) => s.saveConsolidation);
  const consolidate = useMemoryStore((s) => s.consolidate);
  const memoryCategories = useCategoryStore((s) => s.memoryCategories);

  const [thresholdDrafts, setThresholdDrafts] = useState<Record<string, string>>({});
  const [cronDraft, setCronDraft] = useState('');
  const [saving, setSaving] = useState(false);
  const [scope, setScope] = useState<'global' | 'project'>('global');
  const [type, setType] = useState('');
  const [action, setAction] = useState<'preview' | 'run' | null>(null);
  const [report, setReport] = useState<ConsolidationReport | null>(null);
  const [taskId, setTaskId] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [confirmRunOpen, setConfirmRunOpen] = useState(false);

  // Initialize the drafts only on the closed→open edge. `saveConsolidation` writes a fresh
  // `consolidation` object into the store; if that identity change re-ran this effect it would
  // overwrite unsaved threshold/cron edits and clear the notice/report the user is looking at.
  const prevOpenRef = useRef(false);
  useEffect(() => {
    const justOpened = open && !prevOpenRef.current;
    prevOpenRef.current = open;
    if (!justOpened) return;
    const thresholds = consolidation?.typeThresholds ?? {};
    setThresholdDrafts(
      Object.fromEntries(
        memoryCategories.map((c) => [c.code, thresholds[c.code]?.toString() ?? ''])
      )
    );
    setCronDraft(consolidation?.scanCron ?? '');
    setScope((prev) => (prev === 'project' && !currentProjectPath ? 'global' : prev));
    setType((prev) =>
      memoryCategories.some((c) => c.code === prev) ? prev : (memoryCategories[0]?.code ?? '')
    );
    setReport(null);
    setTaskId(null);
    setNotice(null);
  }, [open, consolidation, memoryCategories, currentProjectPath]);

  // Active entry counts per type in the selected slice, so thresholds read against real data.
  const activeCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const entry of entries) {
      if (entry.archived) continue;
      const inSlice =
        scope === 'global' ? entry.projectPath == null : entry.projectPath === currentProjectPath;
      if (!inSlice) continue;
      counts[entry.type] = (counts[entry.type] ?? 0) + 1;
    }
    return counts;
  }, [entries, scope, currentProjectPath]);

  // A cluster the writer refused stays in the plan with its reason, so a pass that produced nothing
  // reads as a rejected proposal instead of looking like "nothing to merge".
  const plannedClusters = useMemo(
    () => (report?.plan ?? []).filter((item) => !item.rejected),
    [report]
  );
  const rejectedClusters = useMemo(
    () => (report?.plan ?? []).filter((item) => item.rejected),
    [report]
  );

  // Live preview of the schedule: an empty draft falls back to the deployment default so the
  // reader sees what "blank" actually means; a non-empty draft is validated client-side with the
  // same grammar the backend enforces, so a bad expression is flagged before Save.
  const cronPreview = useMemo(() => {
    const usingDefault = cronDraft.trim() === '';
    const expression = usingDefault ? (consolidation?.defaultScanCron ?? '') : cronDraft.trim();
    if (!expression) return { runs: [] as Date[], invalid: false, usingDefault };
    const runs = nextOccurrences(expression, 5);
    return { runs, invalid: runs.length === 0, usingDefault };
  }, [cronDraft, consolidation?.defaultScanCron]);

  // The scheduler's own memory of the slice selected in "Run once". Shown there rather than beside the
  // thresholds because slice state is keyed by (scope, project, type) and only that section knows the
  // scope — and because a manual run deliberately ignores the wait this describes, which is exactly what
  // a reader staring at a paused slice needs to be told. An expired delay is dropped: past its
  // `nextEligibleAt` the backend gate no longer applies, so showing it would claim a wait that is over.
  const sliceStatus = useMemo(() => {
    if (!type) return null;
    const state = consolidation?.sliceStates?.[sliceKeyOf(scope, currentProjectPath, type)];
    if (!state) return null;
    const now = Date.now();
    return {
      lastRunAt: state.lastRunAt ?? null,
      deferredUntil:
        state.nextEligibleAt != null && state.nextEligibleAt > now ? state.nextEligibleAt : null,
      outcome: state.lastOutcome ?? null,
      streak: state.zeroGainStreak ?? 0,
    };
  }, [consolidation, scope, currentProjectPath, type]);

  const handleToggle = async () => {
    if (!consolidation) return;
    setNotice(null);
    clearError();
    try {
      await saveConsolidation({ enabled: !consolidation.enabled });
    } catch {
      // error already set in store
    }
  };

  const handleSaveSettings = async () => {
    const thresholds: Record<string, number> = {};
    for (const [code, raw] of Object.entries(thresholdDrafts)) {
      const trimmed = raw.trim();
      if (!trimmed) continue;
      const value = Number(trimmed);
      if (!Number.isInteger(value) || value < 1) {
        setNotice({ type: 'error', text: i18n('Thresholds must be positive integers') });
        return;
      }
      thresholds[code] = value;
    }
    setSaving(true);
    setNotice(null);
    clearError();
    try {
      await saveConsolidation({ typeThresholds: thresholds, scanCron: cronDraft.trim() });
      setNotice({ type: 'success', text: i18n('Settings saved') });
    } catch {
      // error already set in store
    } finally {
      setSaving(false);
    }
  };

  const handlePreview = async () => {
    if (!type) return;
    setAction('preview');
    setReport(null);
    setTaskId(null);
    setNotice(null);
    clearError();
    try {
      const response = await consolidate({
        scope,
        projectPath: scope === 'project' ? currentProjectPath : null,
        type,
        dryRun: true,
      });
      setReport(response.report ?? null);
    } catch {
      // error already set in store
    } finally {
      setAction(null);
    }
  };

  const executeRun = async () => {
    if (!type) return;
    setAction('run');
    setReport(null);
    setTaskId(null);
    setNotice(null);
    clearError();
    try {
      const response = await consolidate({
        scope,
        projectPath: scope === 'project' ? currentProjectPath : null,
        type,
        dryRun: false,
      });
      setTaskId(response.taskId ?? null);
      setNotice({
        type: 'success',
        text: i18n('Consolidation task submitted. Track its progress on the Tasks page.'),
      });
    } catch {
      // error already set in store
    } finally {
      setAction(null);
    }
  };

  const handleRunRequest = () => {
    if (!type) return;
    setConfirmRunOpen(true);
  };

  const enabled = consolidation?.enabled ?? false;
  const modelConfigured = consolidation?.curationModelConfigured ?? false;
  // The backend report carries numbers only; the heading is composed here so it follows the UI locale.
  const reportCategoryKey = report
    ? (memoryCategories.find((category) => category.code === report.type)?.labelKey ?? report.type)
    : '';

  return (
    <Dialog open={open} onClose={onClose} title={i18n('Memory Consolidation')}>
      <div className="space-y-5 text-sm">
        <p className="text-xs text-muted-foreground">
          {i18n(
            'Merging related memories into higher-level entries; absorbed sources are archived, never deleted.'
          )}
        </p>

        {/* Master switch */}
        <div className="flex items-center justify-between gap-3 p-3 rounded-lg border border-border">
          <div className="min-w-0">
            <div className="text-sm font-medium">{i18n('Enable Memory Consolidation')}</div>
            <div className="text-xs text-muted-foreground">
              {i18n('Runs at the scan schedule below and only when a type reaches its threshold.')}
            </div>
          </div>
          <ConsolidationSwitch
            enabled={enabled}
            modelConfigured={modelConfigured}
            available={consolidation !== null}
            onChange={() => void handleToggle()}
          />
        </div>

        {!modelConfigured && (
          <div className="flex items-start gap-2 p-3 rounded-md bg-amber-500/10 text-amber-600 text-xs">
            <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
            <span>
              {i18n(
                'Configure the Memory Curation Model in Settings → Task Models before enabling consolidation.'
              )}
            </span>
          </div>
        )}

        {/* Per-type thresholds */}
        <div>
          <div className="text-sm font-medium mb-1">{i18n('Type thresholds')}</div>
          <p className="text-xs text-muted-foreground mb-2">
            {i18n(
              'Consolidate when a type reaches this many active entries. Leave blank to use the default.'
            )}
          </p>
          <div className="space-y-1.5">
            {memoryCategories.map((category) => (
              <div key={category.code} className="flex items-center gap-2">
                <span className="flex-1 min-w-0 text-xs truncate">{i18n(category.labelKey)}</span>
                <span className="text-[11px] text-muted-foreground shrink-0">
                  {i18n('Active entries')}: {activeCounts[category.code] ?? 0}
                </span>
                <input
                  type="number"
                  min={1}
                  step={1}
                  value={thresholdDrafts[category.code] ?? ''}
                  onChange={(e) =>
                    setThresholdDrafts((prev) => ({ ...prev, [category.code]: e.target.value }))
                  }
                  placeholder={String(consolidation?.defaultThreshold ?? '')}
                  className="w-20 px-2 py-1 rounded-md border border-border bg-background text-xs text-right focus:outline-none focus:ring-2 focus:ring-primary"
                />
              </div>
            ))}
          </div>
        </div>

        {/* Scan schedule */}
        <div>
          <div className="flex items-center gap-1.5 mb-1">
            <label className="text-sm font-medium">{i18n('Scan schedule')}</label>
            {/* Hover help: the six-field layout, per-field ranges and worked examples. */}
            <span className="relative group inline-flex">
              <HelpCircle className="w-3.5 h-3.5 text-muted-foreground cursor-help" />
              <span className="hidden group-hover:block absolute left-0 top-full mt-1 z-50 w-80 rounded-lg border border-border bg-background p-3 shadow-xl text-left">
                <span className="block text-xs font-medium mb-1.5">{i18n('Cron format')}</span>
                <span className="grid grid-cols-6 gap-1 text-[11px] font-mono mb-1">
                  {CRON_FIELDS.map((field) => (
                    <span key={field.labelKey} className="text-foreground">{i18n(field.labelKey)}</span>
                  ))}
                </span>
                <span className="grid grid-cols-6 gap-1 text-[11px] font-mono text-muted-foreground mb-2">
                  {CRON_FIELDS.map((field) => (
                    <span key={field.rangeKey} className="truncate" title={i18n(field.rangeKey)}>
                      {i18n(field.rangeKey)}
                    </span>
                  ))}
                </span>
                <span className="block text-[11px] text-muted-foreground mb-2">
                  {i18n('Cron syntax note')}
                </span>
                <span className="block text-[11px] font-medium mb-1">{i18n('Cron examples')}</span>
                <span className="block space-y-0.5 text-[11px] font-mono text-muted-foreground">
                  {CRON_EXAMPLES.map((example) => (
                    <span key={example.expr} className="block">
                      <span className="text-foreground">{example.expr}</span>
                      {'  →  '}
                      {i18n(example.descKey)}
                    </span>
                  ))}
                </span>
              </span>
            </span>
          </div>
          <input
            type="text"
            value={cronDraft}
            onChange={(e) => setCronDraft(e.target.value)}
            placeholder={consolidation?.defaultScanCron ?? ''}
            className={`w-full px-3 py-2 rounded-md border bg-background text-sm font-mono focus:outline-none focus:ring-2 focus:ring-primary ${
              cronPreview.invalid ? 'border-destructive' : 'border-border'
            }`}
          />
          <p className="text-xs text-muted-foreground mt-1">
            {i18n('Cron expression; leave blank to use the deployment default')}
          </p>
          {cronPreview.invalid && (
            <p className="text-xs text-destructive mt-1">{i18n('Invalid cron expression')}</p>
          )}
          {cronPreview.runs.length > 0 && (
            <div className="mt-1.5 text-xs text-muted-foreground">
              <span className="font-medium text-foreground">
                {i18n('Next runs')}
                {cronPreview.usingDefault && ` (${i18n('Showing deployment default')})`}
              </span>
              <span className="block mt-0.5 space-y-0.5 font-mono text-[11px]">
                {cronPreview.runs.map((run) => (
                  <span key={run.getTime()} className="block">{run.toLocaleString()}</span>
                ))}
              </span>
            </div>
          )}
        </div>

        <div className="flex justify-end">
          <button
            onClick={handleSaveSettings}
            disabled={saving}
            className="flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {saving && <Loader2 className="w-4 h-4 animate-spin" />}
            {i18n('Save settings')}
          </button>
        </div>

        {/* Run once */}
        <div className="border-t border-border pt-4">
          <div className="flex items-center gap-2 mb-2">
            <Layers className="w-4 h-4 text-primary" />
            <span className="text-sm font-medium">{i18n('Run once')}</span>
          </div>
          <div className="flex items-center gap-2">
            <select
              value={scope}
              onChange={(e) => setScope(e.target.value as 'global' | 'project')}
              className="px-2 py-1.5 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary"
            >
              <option value="global">{i18n('Global')}</option>
              {currentProjectPath && <option value="project">{i18n('Current Project')}</option>}
            </select>
            <select
              value={type}
              onChange={(e) => setType(e.target.value)}
              className="flex-1 min-w-0 px-2 py-1.5 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary"
            >
              {memoryCategories.map((category) => (
                <option key={category.code} value={category.code}>
                  {i18n(category.labelKey)} ({activeCounts[category.code] ?? 0})
                </option>
              ))}
            </select>
            <button
              onClick={() => void handlePreview()}
              disabled={action !== null || !type || !modelConfigured}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs border border-border hover:bg-muted transition-colors disabled:opacity-50"
            >
              {action === 'preview' ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Eye className="w-3.5 h-3.5" />
              )}
              {i18n('Preview')}
            </button>
            <button
              onClick={() => void handleRunRequest()}
              disabled={action !== null || !type || !modelConfigured}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
            >
              {action === 'run' ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Play className="w-3.5 h-3.5" />
              )}
              {action === 'run' ? i18n('Consolidating...') : i18n('Run now')}
            </button>
          </div>
          {!modelConfigured && (
            <p className="text-xs text-muted-foreground mt-2">
              {i18n('A configured curation model is required to preview or run a pass.')}
            </p>
          )}

          {/* Scheduler state of the selected slice: the difference between "waiting on purpose" and
              "silently broken", which is otherwise invisible. */}
          <div className="mt-2 text-[11px] text-muted-foreground">
            <span className="font-medium text-foreground">{i18n('Slice scheduler status')}</span>
            {!sliceStatus ? (
              <span className="block mt-0.5">
                {i18n('This slice has not been consolidated yet.')}
              </span>
            ) : (
              <span className="block mt-0.5 space-y-0.5">
                <span className="block">
                  {i18n('Last run')}:{' '}
                  <span className="font-mono">
                    {sliceStatus.lastRunAt
                      ? new Date(sliceStatus.lastRunAt).toLocaleString()
                      : '—'}
                  </span>
                </span>
                {sliceStatus.deferredUntil && (
                  <span className="block">
                    {i18n('Scan paused until')}{' '}
                    <span className="font-mono">
                      {new Date(sliceStatus.deferredUntil).toLocaleString()}
                    </span>
                    {sliceStatus.outcome === 'FAILED' && ` — ${i18n('the last pass failed')}`}
                    {sliceStatus.outcome === 'NOTHING_TO_MERGE' &&
                      ` — ${i18n('the last pass found nothing to merge')}`}
                    {sliceStatus.streak > 1 &&
                      ` (${sliceStatus.streak} ${i18n('consecutive unproductive runs')})`}
                    <span className="block">{i18n('Running manually ignores this wait.')}</span>
                  </span>
                )}
              </span>
            )}
          </div>
        </div>

        {/* Dry-run report */}
        {report && (
          <div className="rounded-lg border border-border p-3 space-y-2">
            <div className="text-xs font-medium">
              {i18n('Memory Consolidation')} · {i18n(reportCategoryKey)}
            </div>
            <div className="text-[11px] text-muted-foreground">
              {i18n('Active entries')}: {report.activeBefore} · {i18n('Planned clusters')}:{' '}
              {plannedClusters.length}
              {/* A cluster that survived grouping can still fail on size or budget; the count of what
                  grouping asked for is what tells those two apart. */}
              {report.clustersProposed > 0 && ` · ${i18n('Proposed')} ${report.clustersProposed}`}
              {rejectedClusters.length > 0 &&
                ` · ${i18n('Rejected')} ${rejectedClusters.length}`}
              {report.droppedGroups > 0 && ` · ${i18n('Groups refused')} ${report.droppedGroups}`}
              {report.overTarget > 0 && ` · ${i18n('Over target')} ${report.overTarget}`}
            </div>
            {report.dryRun && (
              <div className="text-[11px] text-muted-foreground">
                {i18n('A preview runs the grouping pass only: it lists which entries would merge, and writes no bodies.')}
              </div>
            )}
            {(report.llmFailures > 0 || report.rejectedBatches > 0 || report.uncoveredDocIds > 0) && (
              <div className="flex items-start gap-2 p-2 rounded-md bg-amber-500/10 text-amber-600 text-xs">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <div className="space-y-1">
                  {report.llmFailures > 0 && (
                    <div>
                      {`${i18n('Model calls failed:')} ${report.llmFailures} — ${i18n(
                        'The model was never asked; this is not an answer format problem.'
                      )}`}
                    </div>
                  )}
                  {report.llmFailureReason && <div className="break-all">{report.llmFailureReason}</div>}
                  {(report.rejectedBatches > 0 || report.uncoveredDocIds > 0) && (
                    <div>
                      {report.rejectedBatches > 0 &&
                        `${i18n('Batches rejected: the model answer did not match the required format.')} ${report.rejectedBatches}`}
                      {report.rejectedBatches > 0 && report.uncoveredDocIds > 0 && ' · '}
                      {report.uncoveredDocIds > 0 &&
                        `${i18n('Entries the model did not account for:')} ${report.uncoveredDocIds}`}
                    </div>
                  )}
                </div>
              </div>
            )}
            {plannedClusters.length === 0 && rejectedClusters.length === 0 ? (
              <div className="text-xs text-muted-foreground">{i18n('No clusters planned.')}</div>
            ) : (
              <div className="space-y-1">
                {plannedClusters.map((item, index) => (
                  <div
                    key={`${item.name ?? item.title ?? index}`}
                    className="flex items-center gap-2 text-xs"
                  >
                    <span className="truncate flex-1">{item.title ?? item.name}</span>
                    <span className="text-muted-foreground shrink-0">
                      {i18n('Level')} {item.level ?? 0}
                    </span>
                    <span className="text-muted-foreground shrink-0">
                      {item.sourceDocIds?.length ?? 0} {i18n('sources')}
                    </span>
                    {/* The size the merge pass has to distill into one digest — grouping cannot show the
                        bodies, so this number is what makes "would that even fit" answerable. */}
                    {typeof item.sourceChars === 'number' && (
                      <span className="text-muted-foreground shrink-0">{item.sourceChars} chars</span>
                    )}
                    {typeof item.contentChars === 'number' && (
                      <span
                        className={
                          item.contentChars > MEMORY_CONTENT_TARGET_CHARS
                            ? 'shrink-0 text-amber-600'
                            : 'shrink-0 text-muted-foreground'
                        }
                        title={i18n('Written body size')}
                      >
                        {item.contentChars} chars
                      </span>
                    )}
                  </div>
                ))}
                {rejectedClusters.map((item, index) => (
                  <div
                    key={`rejected-${item.title ?? index}`}
                    className="flex items-start gap-2 text-xs text-muted-foreground"
                  >
                    <AlertCircle className="w-3.5 h-3.5 shrink-0 mt-0.5 text-amber-600" />
                    <span className="truncate flex-1">{item.title}</span>
                    <span className="shrink-0 text-amber-600">{item.rejected}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Submitted task */}
        {taskId && (
          <div className="flex items-center justify-between gap-2 p-3 rounded-md bg-green-500/10 text-green-600 text-xs">
            <span className="truncate font-mono">{taskId}</span>
            <button onClick={onViewTasks} className="shrink-0 underline hover:opacity-80">
              {i18n('View tasks')}
            </button>
          </div>
        )}

        {notice && (
          <div
            className={`flex items-center gap-2 p-2.5 rounded-md text-xs ${
              notice.type === 'success'
                ? 'bg-green-500/10 text-green-600'
                : 'bg-destructive/10 text-destructive'
            }`}
          >
            {notice.type === 'success' ? (
              <CheckCircle2 className="w-4 h-4 shrink-0" />
            ) : (
              <AlertCircle className="w-4 h-4 shrink-0" />
            )}
            {notice.text}
          </div>
        )}

        {error && (
          <div className="flex items-center gap-2 p-2.5 rounded-md bg-destructive/10 text-destructive text-xs">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span className="flex-1 truncate">{error}</span>
            <button onClick={clearError} className="shrink-0 underline">
              {i18n('Close')}
            </button>
          </div>
        )}
      </div>

      <ConfirmDialog
        open={confirmRunOpen}
        message={i18n('Run consolidation now? Absorbed memories are archived, not deleted.')}
        confirmLabel={i18n('Run')}
        onConfirm={() => {
          setConfirmRunOpen(false);
          void executeRun();
        }}
        onCancel={() => setConfirmRunOpen(false)}
      />
    </Dialog>
  );
};
