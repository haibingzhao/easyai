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
