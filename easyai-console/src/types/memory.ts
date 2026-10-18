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

/** One cluster in a consolidation plan. Dry runs omit the merged docId / archived count. */
export interface ConsolidationPlanItem {
  title?: string;
  name?: string;
  level?: number;
  sourceDocIds?: string[];
  mergedDocId?: string;
  archived?: number;
  dryRun?: boolean;
  /** Present instead of a write result when the writer refused this cluster, with the reason. */
  rejected?: string;
}

export interface ConsolidationReport {
  type: string;
  scope: string;
  activeBefore: number;
  mergedWritten: number;
  archived: number;
  /** Sources a written cluster failed to archive, so they are still live duplicates of it. */
  archiveShortfall: number;
  skippedSingletons: number;
  skippedOverCap: number;
  /** Batches whose answer could not be applied: an unusable first answer, or an unusable re-ask. */
  rejectedBatches: number;
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
