export type TodoStatus = 'pending' | 'in_progress' | 'completed' | 'cancelled';
export type TodoPriority = 'high' | 'medium' | 'low';

export interface TodoInfo {
  id: string;
  content: string;
  status: TodoStatus;
  priority: TodoPriority;
  position: number;
  createdAt: number;
}

/**
 * Sub-agent todo group: includes the todo list plus the toolCallId
 * that identifies the sub-agent invocation in the message stream.
 */
export interface SubAgentTodoGroup {
  todos: TodoInfo[];
  toolCallId?: string;
}
