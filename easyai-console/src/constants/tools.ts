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
} as const;

/**
 * Tools unavailable in Swarm runtime context.
 * The backend does not support Skills and Sub Agents for swarm agents:
 * - load_skill: skills are cleared in swarm context
 * - task: SubAgentTool is not created (parentAgentId recursion guard)
 * - run_swarm: mainAgentOnly tool, blocked for non-main agents
 */
export const SWARM_EXCLUDED_TOOLS: string[] = ['load_skill', 'task', 'run_swarm'];
