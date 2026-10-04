import type { ToolInfo } from '@/types/agent';

/**
 * Well-known tool names used for frontend behavior logic.
 * These constants centralize tool name references across the frontend.
 */
export const TOOL_NAMES = {
  /** Tool that asks user questions and pauses execution */
  ASK_QUESTION: 'ask_question',
  /** Tool that writes/updates the todo list */
  TODO_WRITE: 'todo_write',
  /** Tool that stores/updates session-scoped variables (persists across compaction) */
  UPDATE_VARIABLE: 'update_variable',
  /** Tool that manages goal state (update_status, update_objective, add_evidence) */
  GOAL: 'goal',
  /** Tool whose payload is an HTML/SVG fragment rendered inline by the client */
  RENDER_VISUAL: 'render_visual',
  /** Team coordination tools (TEAM agent only) */
  DELEGATE_TO_MEMBER: 'delegate_to_member',
  WAIT_FOR_MEMBER_EVENTS: 'wait_for_member_events',
  RESUME_MEMBER: 'resume_member',
} as const;

/**
 * Tool availability is decided by the backend: the availability flags on each ToolInfo are derived
 * from tool metadata (capabilities + mainAgentOnly), so these helpers only project them into name
 * sets. They must not re-implement the rules, or a runtime change would need a matching edit here.
 */

/**
 * Names of tools unavailable in a Swarm runtime context (skills are cleared for swarm agents,
 * sub-agent spawning is recursion-guarded, main-agent-only tools are blocked).
 */
export function swarmExcludedToolNames(tools: ToolInfo[]): Set<string> {
  return new Set(tools.filter((t) => t.unsupportedInSwarm).map((t) => t.name));
}

/**
 * Names of tools blocked at runtime for SUBAGENT-type agents. They remain selectable in the config
 * UI (the agent could be re-purposed as a main agent), but are shown with a de-emphasized
 * "runtime unavailable" hint.
 */
export function subAgentBlockedToolNames(tools: ToolInfo[]): Set<string> {
  return new Set(tools.filter((t) => t.blockedForSubAgent).map((t) => t.name));
}

/**
 * Names of tools hidden from the selection list for TEAM-type agents. A leader coordinates its
 * members via delegate_to_member instead, so sub-agent spawning can never succeed for it.
 */
export function teamExcludedToolNames(tools: ToolInfo[]): Set<string> {
  return new Set(tools.filter((t) => t.unusableForTeam).map((t) => t.name));
}

/**
 * Tool groups that must be selected or deselected atomically.
 * Individual tools within these sets are useless (or nearly so) alone:
 * - run_background only returns a task ID — without task_list / task_status the
 *   agent can never inspect or retrieve the background task's result.
 * - knowledge_search finds entries; knowledge_read loads their full content.
 * - the memory tools form one search → read → write lifecycle, and the backend
 *   prompt guidance is only injected when memory_search is registered.
 */
export const TOOL_GROUPS: string[][] = [
  ['run_background', 'task_list', 'task_status'],
  ['knowledge_search', 'knowledge_read'],
  ['memory_search', 'memory_read', 'memory_write', 'memory_list'],
];

/** All members of the group containing `toolName`, or null if it is not grouped. */
export function toolGroupOf(toolName: string): string[] | null {
  return TOOL_GROUPS.find((group) => group.includes(toolName)) ?? null;
}

/**
 * Filter out auto-injected tools (ToolInfo.alwaysInclude) from a selectable list.
 *
 * Auto-injected tools (e.g. team coordination tools delegate_to_member /
 * wait_for_member_events / resume_member) are added by the runtime automatically
 * and bypass agent-level toolNames filtering. Offering them for manual selection
 * is redundant when they apply and meaningless when they don't, so they are hidden
 * from every tool-selection UI.
 */
export function selectableTools<T extends { alwaysInclude?: boolean }>(tools: T[]): T[] {
  return tools.filter((t) => !t.alwaysInclude);
}
