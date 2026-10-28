import { BellRing, Layers, ListChecks } from 'lucide-react';
import type { AsyncTask, TaskTypeDescriptor } from '@/types/task';
import { categoryLabelKey, type ConsolidationPlanItem } from '@/types/memory';
import { i18n } from '@/utils/i18n';

const VERDICT_LABEL: Record<string, string> = {
  met: 'Met',
  not_met: 'Not met',
  unknown: 'Unknown',
  note: 'Note',
};

function findingTagLabels(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (item == null || typeof item !== 'object' || Array.isArray(item)) return [];
    const record = item as Record<string, unknown>;
    const label = typeof record.label === 'string' ? record.label : undefined;
    const key = typeof record.key === 'string' ? record.key : undefined;
    return label ? [label] : key ? [key] : [];
  });
}

/**
 * Presentation registry for async task types — the single place to teach the UI about a new backend
 * task type. The Tasks page reads from here, so adding a type is one entry (label + icon + optional
 * title/summarizer/link); no page or handler changes needed.
 *
 * Backend rows carry no localized display text: `title` builds it from `payload`, `summarize` from
 * the numeric `result`, so a row written under one language still reads correctly under another.
 */
export const TASK_TYPE_REGISTRY: Record<string, TaskTypeDescriptor> = {
  memory_consolidation: {
    labelKey: 'Memory Consolidation',
    icon: Layers,
    title: (task) => {
      const type = task.payload?.type;
      if (typeof type !== 'string') return i18n('Memory Consolidation');
      return `${i18n('Memory Consolidation')} · ${i18n(categoryLabelKey(type))}`;
    },
    summarize: (outcome) => {
      const r = outcome.result;
      if (!r) return outcome.error ?? '';
      const count = (value: unknown) => (typeof value === 'number' ? value : 0);
      const plan = Array.isArray(r.plan) ? (r.plan as ConsolidationPlanItem[]) : [];
      // A preview is the grouping pass only: it says which entries would be merged, and never pays for
      // bodies nobody was shown. Saying "clusters" alone made it read like a finished merge.
      if (r.dryRun === true) {
        const planned = plan.filter((item) => !item.rejected).length;
        const rejected = plan.length - planned;
        const base = `${i18n('Preview')}: ${planned} ${i18n('clusters')} · ${i18n('no bodies written')}`;
        return rejected > 0 ? `${base} · ${i18n('Rejected')} ${rejected}` : base;
      }
      // Proposed first, then what was written: a run that proposed 12 clusters and merged 3 is a
      // different story from one that proposed 3 and merged 3, and the archived count cannot tell them.
      const parts = [
        `${i18n('Proposed')} ${count(r.clustersProposed)}`,
        `${i18n('Merged')} ${count(r.mergedWritten)}`,
        `${i18n('Archived')} ${count(r.archived)}`,
      ];
      // Refused groups are the silent failure of the two-pass design: grouping proposed something the
      // size limits could not distill, so those entries stay exactly as they were.
      if (count(r.droppedGroups) > 0) parts.push(`${i18n('Groups refused')} ${count(r.droppedGroups)}`);
      // Over target but stored: the reader wants to know the digest came out long, not that it failed.
      if (count(r.overTarget) > 0) parts.push(`${i18n('Over target')} ${count(r.overTarget)}`);
      // A merged document whose sources are still active means the entry is searchable twice; the
      // backend knows why only per id, so this is the one number that says the run left a duplicate.
      if (count(r.archiveShortfall) > 0) {
        parts.push(`${i18n('Not archived')} ${count(r.archiveShortfall)}`);
      }
      if (count(r.skippedOverCap) > 0) parts.push(`${i18n('Deferred')} ${count(r.skippedOverCap)}`);
      // Counted apart from rejected batches on purpose: here the model never got the question, so the
      // reason is the actionable part and travels with the count.
      if (count(r.llmFailures) > 0) {
        const reason = typeof r.llmFailureReason === 'string' ? ` (${r.llmFailureReason})` : '';
        parts.push(`${i18n('Model calls failed')} ${count(r.llmFailures)}${reason}`);
      }
      if (count(r.rejectedBatches) > 0) {
        parts.push(`${i18n('Rejected batches')} ${count(r.rejectedBatches)}`);
      }
      if (count(r.uncoveredDocIds) > 0) {
        parts.push(`${i18n('Unaccounted entries')} ${count(r.uncoveredDocIds)}`);
      }
      return parts.join(' · ');
    },
  },
  watch: {
    labelKey: 'Watch',
    icon: BellRing,
    title: (task) => {
      const title = task.payload?.title;
      if (typeof title === 'string' && title !== '') return `${i18n('Watch')} · ${title}`;
      // Older rows carry only the backend-generated stored title.
      const stored = task.title;
      if (stored && stored !== i18n('Watch')) return stored;
      return i18n('Watch');
    },
    summarize: (outcome) => {
      const r = outcome.result;
      if (!r) return outcome.error ?? '';
      const conclusion = typeof r.conclusion === 'string' ? r.conclusion : '';
      // A firing that arrived after the user paused/cancelled/renewed the watch: nothing was judged,
      // and saying "Unknown" would read like a model failure.
      if (r.kind === 'skipped') {
        const reason = typeof r.reason === 'string' ? r.reason : '';
        return reason ? `${i18n('Skipped')} · ${reason}` : i18n('Skipped');
      }
      // An expiry review is a different shape: its decision is the outcome, and its conclusion is
      // the sentence the user saw in the notification.
      if (r.kind === 'expiry_review') {
        const decision = typeof r.decision === 'string' ? r.decision : '';
        const decisionLabel = decision === 'renew'
          ? i18n('Renewed')
          : decision === 'complete'
            ? i18n('Completed')
            : decision === 'expire'
              ? i18n('Expired')
              : i18n('Expiry review');
        return conclusion ? `${decisionLabel} · ${conclusion}` : decisionLabel;
      }
      const verdict = typeof r.verdict === 'string' ? VERDICT_LABEL[r.verdict] : undefined;
      const parts = [
        verdict ? i18n(verdict) : i18n('Unknown'),
        ...findingTagLabels(r.tags).map((label) => `#${label}`),
      ];
      if (conclusion) parts.push(conclusion);
      return parts.join(' · ');
    },
  },
};

/**
 * Register host-specific task types (labels/icons/summaries) without editing this package. Call it
 * once at app startup, before rendering the Tasks page; later registrations win for the same key.
 */
export function registerTaskTypes(entries: Record<string, TaskTypeDescriptor>): void {
  Object.assign(TASK_TYPE_REGISTRY, entries);
}

export const DEFAULT_TASK_DESCRIPTOR: TaskTypeDescriptor = {
  labelKey: 'Task',
  icon: ListChecks,
};

export function getTaskDescriptor(type: string): TaskTypeDescriptor {
  return TASK_TYPE_REGISTRY[type] ?? DEFAULT_TASK_DESCRIPTOR;
}

export function taskTypeLabel(type: string): string {
  return i18n(getTaskDescriptor(type).labelKey);
}

/** Localized task title: the type's own rendering, then any persisted title, then the type label. */
export function taskTitle(task: Pick<AsyncTask, 'type' | 'payload' | 'title'>): string {
  const descriptor = getTaskDescriptor(task.type);
  return descriptor.title?.(task) || task.title || taskTypeLabel(task.type);
}
