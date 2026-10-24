import type { AssetScope } from '@/services/api-client';

export type SkillScanSeverity = 'critical' | 'high' | 'medium' | 'low' | 'info';
export type SkillScanCategory = 'spec' | 'security' | 'quality';

export interface SkillScanFinding {
  id: string;
  category: SkillScanCategory;
  severity: SkillScanSeverity;
  file?: string;
  line?: number;
  message: string;
  suggestion: string;
  blocking: boolean;
  /** True when `message` carries a partially masked secret; keep it out of model context. */
  redacted?: boolean;
}

export interface SkillScanReport {
  findings: SkillScanFinding[];
  blocking: boolean;
  requiresAcknowledgement: boolean;
  counts: Record<SkillScanSeverity, number>;
  /** Per-category totals over the full scan, so badges stay correct when `findings` is capped. */
  categoryCounts: Record<SkillScanCategory, Record<SkillScanSeverity, number>>;
}

export interface SkillProposalFile {
  path: string;
  size: number;
  content: string;
}

export type SkillTargetLayer = 'personal' | 'group' | 'shared';

export interface SkillProposal {
  name: string;
  description?: string;
  tags: string[];
  version: string;
  summary: string;
  files: SkillProposalFile[];
  warnings: string[];
  target: SkillTargetLayer;
  scan: SkillScanReport;
}

export interface SkillArchiveFile {
  path: string;
  size: number;
}

/**
 * One skill discovered inside a downloaded archive. `files` never carries content: the archive is
 * re-downloaded and hash-checked when the user installs, and the model only needs the inventory.
 */
export interface SkillArchiveEntry {
  entryPath: string;
  name: string;
  description?: string;
  version: string;
  tags: string[];
  files: SkillArchiveFile[];
  scan: SkillScanReport;
  warnings: string[];
  /** Set when an installed skill already owns this name; the entry cannot be installed. */
  conflict?: string | null;
  invalidReason?: string | null;
  /** Client-side install state; absent means idle. Never sent back to the backend. */
  state?: 'installing' | 'installed' | 'error';
  /** The user ticked the high-risk acknowledgement for this entry. */
  acknowledged?: boolean;
  error?: string;
  installWarning?: string;
}

export interface SkillArchiveProposal {
  sourceUrl: string;
  finalUrl: string;
  sha256: string;
  archiveBytes: number;
  target: SkillTargetLayer;
  skills: SkillArchiveEntry[];
}

/** Compact inventory replayed to the backend; no file content and no per-file sizes. */
export interface SkillArchiveInput {
  url: string;
  sha256: string;
  skills: Array<{ name: string; entryPath: string; conflict?: string }>;
}

export interface SkillArchiveInstallRequest {
  url: string;
  sha256: string;
  entries: Array<{ entryPath: string; name: string }>;
  scope?: AssetScope;
}

/** `outcome` is one of `installed`, `conflict`, `invalid` or `missing`; more may be added. */
export interface SkillArchiveInstallResult {
  entryPath: string;
  name: string;
  outcome: string;
  message?: string | null;
}

export interface SkillArchiveInstallResponse {
  results: SkillArchiveInstallResult[];
}

/** The subset replayed to the backend so follow-up turns can edit the package. */
export interface SkillProposalInput {
  name: string;
  summary?: string;
  description?: string;
  version?: string;
  tags?: string[];
  files: Array<{ path: string; content: string }>;
  scanSummary?: string;
}

export interface SkillAgentHistoryTurn {
  role: 'user' | 'assistant';
  content: string;
}

export interface SkillAgentRequest {
  modelConfigId: string;
  message: string;
  history: SkillAgentHistoryTurn[];
  currentProposal?: SkillProposalInput;
  currentArchive?: SkillArchiveInput;
  scope?: AssetScope;
}
