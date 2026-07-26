/**
 * Well-known tool names used for frontend behavior logic.
 * These constants centralize tool name references across the frontend.
 */
export const TOOL_NAMES = {
  /** Tool that asks user questions and pauses execution */
  ASK_QUESTION: 'ask_question',
  /** Tool that writes/updates the todo list */
  TODO_WRITE: 'todo_write',
  /** Tool that manages goal state (update_status, update_objective, add_evidence) */
  GOAL: 'goal',
  /** Team coordination tools (TEAM agent only) */
  DELEGATE_TO_MEMBER: 'delegate_to_member',
  WAIT_FOR_MEMBER_EVENTS: 'wait_for_member_events',
  RESUME_MEMBER: 'resume_member',
} as const;

/**
 * Tools unavailable in Swarm runtime context.
 * The backend does not support Skills and Sub Agents for swarm agents:
 * - load_skill: skills are cleared in swarm context
 * - task: SubAgentTool is not created (parentAgentId recursion guard)
 * - run_swarm: mainAgentOnly tool, blocked for non-main agents
 */
export const SWARM_EXCLUDED_TOOLS: string[] = ['load_skill', 'task', 'run_swarm'];

/**
 * Tools blocked at runtime for SUBAGENT-type agents.
 * A SUBAGENT always runs with a parent agent (parentAgentId != null), so:
 * - task: SubAgentTool is not built (parentAgentId recursion guard)
 * - run_swarm: mainAgentOnly tool, blocked for non-main agents
 * They remain selectable in the config UI (the agent could be re-purposed as a
 * main agent), but are shown with a de-emphasized "runtime unavailable" hint.
 */
export const SUBAGENT_BLOCKED_TOOLS: string[] = ['task', 'run_swarm'];

/**
 * Tools hidden from the selection list for TEAM-type agents.
 * - task: SubAgentTool only launches agents in the subAgentIds whitelist, but the
 *   Sub Agents section is hidden for TEAM (leaders coordinate their defined members
 *   via delegate_to_member instead). With an always-empty whitelist, task can never
 *   succeed for a TEAM leader, so it is hidden rather than offered.
 */
export const TEAM_EXCLUDED_TOOLS: string[] = ['task'];

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
