/**
 * Team Agent execution types (mirrors backend core.team models).
 */

/** Member execution status lifecycle. */
export type TeamMemberStatus =
  | 'RUNNING'
  | 'COMPLETED'
  | 'ESCALATED'
  | 'ERROR'
  | 'SUSPENDED'
  | 'RESUMED'
  | 'REASSIGNED';

/** A single member execution record from GET /api/team/sessions/{id}/executions. */
export interface TeamMemberExecution {
  id: string;
  memberId: string;
  memberName: string;
  round: number;
  assignment: string;
  status: TeamMemberStatus;
  summary?: string | null;
  /** Escalation/block reason reported by the member. */
  blockedQuestion?: string | null;
  /** Member session ID — used to load the member's message history. */
  memberSessionId?: string | null;
  /** The delegate_to_member tool call ID that triggered this execution. */
  toolCallId?: string | null;
  inputTokens: number;
  outputTokens: number;
  startedAt?: number | null;
  completedAt?: number | null;
}

/** A coordination round record from GET /api/team/sessions/{id}/rounds. */
export interface TeamRoundRecord {
  id: string;
  round: number;
  delegatedMembers: string[];
  completedMembers: string[];
  blockedMembers: string[];
  resumedMembers: string[];
  createdAt: number;
}

/** Filter tabs for the Team Member Panel. */
export type TeamFilter = 'ALL' | 'RUNNING' | 'DONE' | 'BLOCKED' | 'ERROR';

/** Statuses considered "done" (terminal success). */
export const DONE_STATUSES: TeamMemberStatus[] = ['COMPLETED', 'RESUMED', 'REASSIGNED'];

/** Statuses considered "blocked" (awaiting leader action). */
export const BLOCKED_STATUSES: TeamMemberStatus[] = ['ESCALATED', 'SUSPENDED'];

/** Statuses considered "error". */
export const ERROR_STATUSES: TeamMemberStatus[] = ['ERROR'];

/**
 * Statuses that are fully terminal — no further state change is expected
 * unless the leader takes a new action (re-delegate / resume), which itself
 * arrives via SSE and re-triggers a refresh.
 */
export const TERMINAL_STATUSES: TeamMemberStatus[] = ['COMPLETED', 'ERROR', 'REASSIGNED'];

/** Check whether a status matches the given filter tab. */
export function matchesTeamFilter(status: TeamMemberStatus, filter: TeamFilter): boolean {
  switch (filter) {
    case 'ALL':
      return true;
    case 'RUNNING':
      return status === 'RUNNING';
    case 'DONE':
      return DONE_STATUSES.includes(status);
    case 'BLOCKED':
      return BLOCKED_STATUSES.includes(status);
    case 'ERROR':
      return ERROR_STATUSES.includes(status);
  }
}
