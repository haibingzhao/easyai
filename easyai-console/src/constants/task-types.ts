import { Layers, ListChecks } from 'lucide-react';
import type { AsyncTask, TaskTypeDescriptor } from '@/types/task';
import { categoryLabelKey, type ConsolidationPlanItem } from '@/types/memory';
import { i18n } from '@/utils/i18n';

/**
 * Presentation registry for async task types — the single place to teach the UI about a new backend
 * task type. The Tasks page reads from here, so adding a type is one entry (label + icon + optional
 * title/summarizer); no page or handler changes needed.
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
      if (r.dryRun === true) {
        const planned = plan.filter((item) => !item.rejected).length;
        const rejected = plan.length - planned;
        const base = `${i18n('Preview')}: ${planned} ${i18n('clusters')}`;
        return rejected > 0 ? `${base} · ${i18n('Rejected')} ${rejected}` : base;
      }
      const parts = [
        `${i18n('Merged')} ${count(r.mergedWritten)}`,
        `${i18n('Archived')} ${count(r.archived)}`,
      ];
      // A merged document whose sources are still active means the entry is searchable twice; the
      // backend knows why only per id, so this is the one number that says the run left a duplicate.
      if (count(r.archiveShortfall) > 0) {
        parts.push(`${i18n('Not archived')} ${count(r.archiveShortfall)}`);
      }
      if (count(r.skippedOverCap) > 0) parts.push(`${i18n('Deferred')} ${count(r.skippedOverCap)}`);
      if (count(r.rejectedBatches) > 0) {
        parts.push(`${i18n('Rejected batches')} ${count(r.rejectedBatches)}`);
      }
      if (count(r.uncoveredDocIds) > 0) {
        parts.push(`${i18n('Unaccounted entries')} ${count(r.uncoveredDocIds)}`);
      }
      return parts.join(' · ');
    },
  },
};

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
