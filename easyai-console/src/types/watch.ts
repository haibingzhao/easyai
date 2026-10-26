/**
 * Wire shapes of the watch subsystem (backend `WatchDtos.kt`).
 *
 * A watch is a *definition*; each firing is an ordinary async task, which is why the findings
 * timeline is typed separately from the watch row.
 */

export type WatchMode = 'check' | 'remind';
export type WatchScheduleKind = 'at' | 'daily' | 'weekly' | 'monthly';
export type WatchStatus = 'active' | 'paused' | 'completed' | 'expired' | 'cancelled';
export type WatchPendingAction = 'check' | 'expiry_review';
export type WatchVerdict = 'met' | 'not_met' | 'unknown' | 'note';
export type WatchNotifyPolicy = 'on_change' | 'on_complete' | 'every' | 'never';
export type WatchExpiryDecision = 'renew' | 'complete' | 'expire';

export interface WatchTag {
  key: string;
  label: string;
  description?: string | null;
}

export interface WatchSource {
  label: string;
  url: string;
}

export interface WatchSchedule {
  kind: WatchScheduleKind;
  timezone: string;
  /** `HH:mm` for recurring kinds; null for a one-shot. */
  localTime?: string | null;
  weekday?: string | null;
  dayOfMonth?: number | null;
  /** Epoch millis of the one-shot instant. */
  anchorAt?: number | null;
}

export interface WatchFinding {
  conclusion: string;
  verdict: WatchVerdict;
  tags: WatchTag[];
  sources: WatchSource[];
  definitionVersion: number;
  changed: boolean;
  notified: boolean;
  applied: boolean;
  stale: boolean;
  scheduledFor?: number | null;
  lateByMs: number;
}

/** One watch definition, as returned by `GET /api/watches`. */
export interface Watch {
  id: string;
  title: string;
  condition: string;
  mode: WatchMode;
  reminder?: string | null;
  schedule: WatchSchedule;
  untilAt?: number | null;
  nextDueAt: number;
  status: WatchStatus;
  pendingAction: WatchPendingAction;
  version: number;
  extensionCount: number;
  tags: WatchTag[];
  completeOnMet: boolean;
  notifyPolicy: WatchNotifyPolicy;
  notifyTags: string[];
  sourceAgentId: string;
  originSessionId?: string | null;
  runSessionId: string;
  allowedTools: string[];
  sourceHints?: string | null;
  lastTaskId?: string | null;
  lastRunAt?: number | null;
  lastFinding?: WatchFinding | null;
  consecutiveFailures: number;
  lastError?: string | null;
  statusReason?: string | null;
  createdAt: number;
  updatedAt: number;
}

/** One firing (check or expiry review), read from the shared async-task table. */
export interface WatchFindingTask {
  taskId: string;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'timed_out' | 'cancelled' | 'interrupted';
  createdAt: number;
  completedAt?: number | null;
  error?: string | null;
  result?: Record<string, unknown> | null;
}

export interface WatchScheduleDraft {
  at?: string | null;
  within?: string | null;
  dayOffset?: number | null;
  every?: WatchScheduleKind | null;
  time?: string | null;
  weekday?: string | null;
  dayOfMonth?: number | null;
}

/** PATCH body; every field is optional and null means "leave it alone". */
export interface WatchUpdatePayload {
  title?: string;
  condition?: string;
  mode?: WatchMode;
  reminder?: string | null;
  timezone?: string;
  until?: string | null;
  clearUntil?: boolean;
  tags?: WatchTag[];
  completeOnMet?: boolean;
  notifyPolicy?: WatchNotifyPolicy;
  notifyTags?: string[];
  sourceHints?: string | null;
  schedule?: WatchScheduleDraft;
}

export const isWatchActive = (status: WatchStatus): boolean => status === 'active';

/** Terminal definitions can be renewed by supplying a new schedule. */
export const isWatchTerminal = (status: WatchStatus): boolean =>
  status === 'completed' || status === 'expired';

export interface WatchCheckResult {
  kind: 'check';
  conclusion: string;
  verdict?: WatchVerdict;
  tags: WatchTag[];
  sources: WatchSource[];
  applied: boolean;
  stale: boolean;
  definitionVersion?: number;
}

export interface WatchReviewResult {
  kind: 'expiry_review';
  decision?: WatchExpiryDecision;
  reason?: string;
  conclusion?: string;
  applied: boolean;
  stale: boolean;
  newUntil?: number;
  rescheduleAt?: number;
}

export type WatchRunResult = WatchCheckResult | WatchReviewResult;

function asRecord(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function booleanValue(value: unknown, fallback = false): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

function parseTags(value: unknown): WatchTag[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    const record = asRecord(item);
    const key = stringValue(record?.key);
    if (!key) return [];
    return [{ key, label: stringValue(record?.label) ?? key }];
  });
}

function parseSources(value: unknown): WatchSource[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    const record = asRecord(item);
    const url = stringValue(record?.url);
    if (!url) return [];
    return [{ label: stringValue(record?.label) ?? url, url }];
  });
}

const VERDICTS: WatchVerdict[] = ['met', 'not_met', 'unknown', 'note'];
const DECISIONS: WatchExpiryDecision[] = ['renew', 'complete', 'expire'];

/** Narrow the generic `result` map of an async task into the two finding shapes the UI renders. */
export function parseWatchRunResult(value: unknown): WatchRunResult | null {
  const record = asRecord(value);
  if (!record) return null;
  const kind = stringValue(record.kind);
  if (kind === 'check') {
    const conclusion = stringValue(record.conclusion);
    if (!conclusion) return null;
    const verdictCandidate = stringValue(record.verdict);
    const verdict = VERDICTS.find((candidate) => candidate === verdictCandidate);
    return {
      kind: 'check',
      conclusion,
      verdict,
      tags: parseTags(record.tags),
      sources: parseSources(record.sources),
      applied: booleanValue(record.applied),
      stale: booleanValue(record.stale),
      definitionVersion: numberValue(record.definitionVersion),
    };
  }
  if (kind === 'expiry_review') {
    const decisionCandidate = stringValue(record.decision);
    return {
      kind: 'expiry_review',
      decision: DECISIONS.find((candidate) => candidate === decisionCandidate),
      reason: stringValue(record.reason),
      conclusion: stringValue(record.conclusion),
      applied: booleanValue(record.applied),
      stale: booleanValue(record.stale),
      newUntil: numberValue(record.newUntil),
      rescheduleAt: numberValue(record.rescheduleAt),
    };
  }
  return null;
}
