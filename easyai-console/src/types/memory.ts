export interface MemoryEntry {
  name: string;
  description: string;
  type: string;
  scope: string;
  content: string;
  keywords: string[];
  maturity?: string | null;
  scenarios: string[];
  created?: string;
  updated?: string;
  /** Populated client-side by the store: null for GLOBAL scope, otherwise the source project path */
  projectPath?: string | null;
  /** Stable EasyRAG docId; the key used by consolidation refs and archive drill-down. */
  id?: string | null;
  /** Consolidation depth: 0 for an original entry, N for a merged entry absorbing level N-1. */
  level?: number;
  /** Source docIds absorbed into this merged entry. */
  refs?: string[];
  /** Set on an archived entry: the merged docId that absorbed it. */
  mergedInto?: string | null;
  /** Archived entries are only returned by the archive drill-down endpoint. */
  archived?: boolean;
}

export interface MemoryConfig {
  enabled: boolean;
}

export interface CreateMemoryRequest {
  name: string;
  description: string;
  type: string;
  scope: string;
  content: string;
  keywords: string[];
  maturity?: string | null;
  scenarios: string[];
  /** Runtime project path; required when scope is "project" */
  projectPath?: string | null;
}

/** Partial update of a memory entry's editable fields. The type/name are immutable. */
export interface UpdateMemoryRequest {
  description?: string;
  content?: string;
  keywords?: string[];
  maturity?: string | null;
  scenarios?: string[];
}

/** Per-user consolidation preferences plus deployment defaults, read from the backend. */
export interface MemoryConsolidationSettings {
  enabled: boolean;
  /** Per-type "consolidate when active count reaches N", keyed by type dirName. */
  typeThresholds: Record<string, number>;
  /** Per-user scan override; null follows `defaultScanCron`. */
  scanCron?: string | null;
  defaultThreshold: number;
  defaultScanCron: string;
  /** Whether a MEMORY_CURATION model is resolvable; enabling requires it. */
  curationModelConfigured: boolean;
  /** Deployment kill switch. */
  available: boolean;
  /**
   * Scheduler memory per slice, keyed by `{scope}|{projectPath}|{typeDirName}` with an empty project
   * segment for global scope. Read-only, and what lets the UI say "waiting until Thursday" instead of
   * looking silently broken. Use `sliceKeyOf` to build a key.
   */
  sliceStates?: Record<string, ConsolidationSliceState>;
}

/** How the last run over a slice ended, as the scheduler judged it. */
export type ConsolidationOutcome = 'PRODUCTIVE' | 'NOTHING_TO_MERGE' | 'FAILED';

/** One slice's scheduler memory: whether the scan will run it soon, and why not. */
export interface ConsolidationSliceState {
  /** Epoch-ms of the last run over this slice; absent when it has never run. */
  lastRunAt?: number | null;
  /**
   * Epoch-ms before which the scheduled scan skips this slice; absent when no delay is in force. A
   * manual run ignores it, so this reads as a wait rather than as a block.
   */
  nextEligibleAt?: number | null;
  /** Consecutive runs that produced no merged entry, whatever the reason. */
  zeroGainStreak?: number;
  /** Absent for a slice recorded before outcomes were tracked — unknown, not a failure. */
  lastOutcome?: ConsolidationOutcome | null;
}

/**
 * The backend's slice key, rebuilt from what the dialog already has selected. Must stay character-for-character
 * aligned with `MemoryConsolidationTaskHandler.sliceKey`: scope lowercased, empty (not omitted) project
 * segment for global, then the type's dirName.
 */
export function sliceKeyOf(
  scope: 'global' | 'project',
  projectPath: string | null | undefined,
  type: string
): string {
  return `${scope}|${scope === 'project' ? (projectPath ?? '') : ''}|${type}`;
}

/** Partial update; a blank `scanCron` clears the override, `undefined` leaves it unchanged. */
export interface UpdateMemoryConsolidationSettingsRequest {
  enabled?: boolean;
  typeThresholds?: Record<string, number>;
  scanCron?: string | null;
}

/** Run one consolidation pass over a single type in one scope slice. */
export interface ConsolidationRequest {
  scope: 'global' | 'project';
  /** Runtime project path; required when scope is "project". */
  projectPath?: string | null;
  /** Memory type dirName to consolidate. */
  type: string;
  /** Compute the cluster plan without writing or archiving. */
  dryRun?: boolean;
}

/**
 * One cluster in a consolidation plan. A preview reports the grouping pass's clusters (with the size they
 * would have to distill); a real run reports what the writer did with them.
 */
export interface ConsolidationPlanItem {
  title?: string;
  name?: string;
  level?: number;
  sourceDocIds?: string[];
  /** Combined stored size of this cluster's sources — what the merge pass must compress into the digest. */
  sourceChars?: number;
  /** Written body size, reported for a real run so an over-target cluster is visible per cluster. */
  contentChars?: number;
  mergedDocId?: string;
  archived?: number;
  expectedArchives?: number;
  dryRun?: boolean;
  /** Present instead of a write result when the writer refused this cluster, with the reason. */
  rejected?: string;
}

export interface ConsolidationReport {
  type: string;
  scope: string;
  activeBefore: number;
  /** Clusters the grouping pass proposed and its validator accepted, written or not. */
  clustersProposed: number;
  mergedWritten: number;
  archived: number;
  /** Groups refused as too large to distill into one merged entry even after trimming their tail. */
  droppedGroups: number;
  /** Written bodies past the key-points target but inside the hard cap: stored, and worth a look. */
  overTarget: number;
  /** Sources a written cluster failed to archive, so they are still live duplicates of it. */
  archiveShortfall: number;
  skippedSingletons: number;
  skippedOverCap: number;
  /** Batches whose answer could not be applied: an unusable first answer, or an unusable re-ask. */
  rejectedBatches: number;
  /** Batches whose model call itself failed, so the model never got to answer. */
  llmFailures: number;
  /** Why the first failed call failed, as the model client reported it. */
  llmFailureReason?: string | null;
  /** Sources the model neither clustered nor declared as leftovers. */
  uncoveredDocIds: number;
  dryRun: boolean;
  sessionId?: string | null;
  plan: ConsolidationPlanItem[];
}

/** `report` is present for dry runs; a real run returns the async `taskId`. */
export interface ConsolidationResponse {
  taskId?: string | null;
  report?: ConsolidationReport | null;
}

/**
 * Body size budget of one memory entry, mirroring the backend's `MemoryContentLimits`: 2K is what the
 * consolidation prompt asks a merged digest to fit in, 6K is what the store refuses beyond. A merged
 * entry over the target is still stored, so the UI marks it rather than treating it as a failure.
 */
export const MEMORY_CONTENT_TARGET_CHARS = 2000;
export const MEMORY_CONTENT_HARD_CAP_CHARS = 6000;

/**
 * Memory categories fallback (coding domain defaults).
 * Prefer useCategoryStore().memoryCategories for dynamic domain-aware categories.
 */
export const MEMORY_CATEGORIES = [
  { apiName: 'user_preferences', labelKey: 'User Preferences' },
  { apiName: 'project_information', labelKey: 'Project Information' },
  { apiName: 'development_standards', labelKey: 'Development Standards' },
  { apiName: 'task_summary', labelKey: 'Task Summary' },
  { apiName: 'experience_lessons', labelKey: 'Experience & Lessons' },
  { apiName: 'other', labelKey: 'Other' },
] as const;

export const MEMORY_CATEGORY_NAMES = MEMORY_CATEGORIES.map((c) => c.apiName);

/** Maturity levels with display keys. */
export const MEMORY_MATURITIES = [
  { apiName: 'low', labelKey: 'Low' },
  { apiName: 'medium', labelKey: 'Medium' },
  { apiName: 'high', labelKey: 'High' },
] as const;

export function categoryLabelKey(apiName: string): string {
  return MEMORY_CATEGORIES.find((c) => c.apiName === apiName)?.labelKey ?? apiName;
}

export function maturityLabelKey(apiName: string): string {
  return MEMORY_MATURITIES.find((m) => m.apiName === apiName)?.labelKey ?? apiName;
}
