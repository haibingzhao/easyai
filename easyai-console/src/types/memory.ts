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

/** Memory categories (backend MemoryType 6 classes), in display order. */
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
