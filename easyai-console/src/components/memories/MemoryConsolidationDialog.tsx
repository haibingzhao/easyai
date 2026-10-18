import React, { useEffect, useMemo, useRef, useState } from 'react';
import { AlertCircle, CheckCircle2, Eye, Layers, Loader2, Play } from 'lucide-react';
import { Dialog } from '@/components/ui/Dialog';
import { ConsolidationSwitch } from '@/components/memories/ConsolidationSwitch';
import { useMemoryStore } from '@/services/stores/memory-store';
import { useCategoryStore } from '@/services/stores/category-store';
import type { ConsolidationReport } from '@/types/memory';
import { i18n } from '@/utils/i18n';

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

  const handleRun = async (dryRun: boolean) => {
    if (!type) return;
    if (!dryRun && !window.confirm(i18n('Run consolidation now? Absorbed memories are archived, not deleted.'))) {
      return;
    }
    setAction(dryRun ? 'preview' : 'run');
    setReport(null);
    setTaskId(null);
    setNotice(null);
    clearError();
    try {
      const response = await consolidate({
        scope,
        projectPath: scope === 'project' ? currentProjectPath : null,
        type,
        dryRun,
      });
      if (dryRun) {
        setReport(response.report ?? null);
      } else {
        setTaskId(response.taskId ?? null);
        setNotice({
          type: 'success',
          text: i18n('Consolidation task submitted. Track its progress on the Tasks page.'),
        });
      }
    } catch {
      // error already set in store
    } finally {
      setAction(null);
    }
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
          <label className="block text-sm font-medium mb-1">{i18n('Scan schedule')}</label>
          <input
            type="text"
            value={cronDraft}
            onChange={(e) => setCronDraft(e.target.value)}
            placeholder={consolidation?.defaultScanCron ?? ''}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm font-mono focus:outline-none focus:ring-2 focus:ring-primary"
          />
          <p className="text-xs text-muted-foreground mt-1">
            {i18n('Cron expression; leave blank to use the deployment default')}
          </p>
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
              onClick={() => void handleRun(true)}
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
              onClick={() => void handleRun(false)}
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
              {rejectedClusters.length > 0 &&
                ` · ${i18n('Rejected')} ${rejectedClusters.length}`}
            </div>
            {(report.rejectedBatches > 0 || report.uncoveredDocIds > 0) && (
              <div className="flex items-start gap-2 p-2 rounded-md bg-amber-500/10 text-amber-600 text-xs">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>
                  {report.rejectedBatches > 0 &&
                    `${i18n('Batches rejected: the model answer did not match the required format.')} ${report.rejectedBatches}`}
                  {report.rejectedBatches > 0 && report.uncoveredDocIds > 0 && ' · '}
                  {report.uncoveredDocIds > 0 &&
                    `${i18n('Entries the model did not account for:')} ${report.uncoveredDocIds}`}
                </span>
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
    </Dialog>
  );
};
