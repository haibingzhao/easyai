import React, { useState } from 'react';
import { ChevronDown, ChevronRight, CheckCircle2, Circle, Clock, XCircle, Bot } from 'lucide-react';
import type { TodoInfo, SubAgentTodoGroup } from '@/types/todo';
import { i18n } from '@/utils/i18n';

interface TodoPanelProps {
  mainTodos: TodoInfo[];
  subAgentTodos: Record<string, SubAgentTodoGroup>;
}

const statusIcons: Record<string, React.ReactNode> = {
  pending: <Circle className="w-4 h-4 text-muted-foreground" />,
  in_progress: <Clock className="w-4 h-4 text-yellow-500" />,
  completed: <CheckCircle2 className="w-4 h-4 text-green-500" />,
  cancelled: <XCircle className="w-4 h-4 text-red-500" />,
};

function countByStatus(todos: TodoInfo[], status: string) {
  return todos.filter((t) => t.status === status).length;
}

/** Render a single todo item row */
function TodoItem({ todo }: { todo: TodoInfo }) {
  return (
    <div className="flex items-start gap-2 py-1 text-sm px-2">
      <span className="mt-0.5 shrink-0">{statusIcons[todo.status]}</span>
      <span className="flex-1 text-foreground">{todo.content}</span>
      {todo.priority === 'high' && (
        <span className="text-xs font-medium text-red-600 dark:text-red-400">HIGH</span>
      )}
      {todo.priority === 'medium' && (
        <span className="text-xs font-medium text-yellow-600 dark:text-yellow-400">MEDIUM</span>
      )}
      {todo.priority === 'low' && (
        <span className="text-xs font-medium text-gray-500 dark:text-gray-400">LOW</span>
      )}
    </div>
  );
}

/** Collapsible sub-agent group with purple accent */
function SubAgentGroup({ agentName, group }: { agentName: string; group: SubAgentTodoGroup }) {
  const [isExpanded, setIsExpanded] = useState(true);
  const { todos, toolCallId } = group;
  const completed = countByStatus(todos, 'completed');

  return (
    <div className="border-l-2 border-purple-500/40 ml-2">
      <div
        className="flex items-center justify-between py-1.5 cursor-pointer hover:bg-muted/50 transition-colors px-2 rounded-r"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <div className="flex items-center gap-1.5 min-w-0">
          <Bot className="w-4 h-4 text-purple-400 shrink-0" />
          <span className="text-xs font-medium text-purple-500 dark:text-purple-400 shrink-0">
            SubAgent:
          </span>
          <span className="text-xs text-purple-500 dark:text-purple-400 truncate max-w-[120px]" title={agentName}>
            {agentName}
          </span>
          {toolCallId && toolCallId !== agentName && (
            <span className="text-xs text-muted-foreground/60 truncate max-w-[100px]" title={toolCallId}>
              {toolCallId}
            </span>
          )}
        </div>
        <div className="flex items-center gap-1">
          <span className="text-xs text-muted-foreground">
            {completed}/{todos.length}
          </span>
          {isExpanded ? (
            <ChevronDown className="w-3 h-3 text-muted-foreground shrink-0" />
          ) : (
            <ChevronRight className="w-3 h-3 text-muted-foreground shrink-0" />
          )}
        </div>
      </div>
      {isExpanded && (
        <div className="space-y-0.5 pl-1">
          {todos.map((todo) => (
            <TodoItem key={todo.id} todo={todo} />
          ))}
        </div>
      )}
    </div>
  );
}

export function TodoPanel({ mainTodos, subAgentTodos }: TodoPanelProps) {
  const [isExpanded, setIsExpanded] = useState(true);

  const mainCount = mainTodos.length;
  const mainCompleted = countByStatus(mainTodos, 'completed');

  // Aggregate sub-agent counts
  const subAgentEntries = Object.entries(subAgentTodos).filter(([, group]) => group.todos.length > 0);
  const hasSubAgents = subAgentEntries.length > 0;

  // Total across main + all sub-agents
  const totalAll = mainCount + subAgentEntries.reduce((sum, [, group]) => sum + group.todos.length, 0);
  const completedAll = mainCompleted + subAgentEntries.reduce(
    (sum, [, group]) => sum + countByStatus(group.todos, 'completed'),
    0
  );

  const hasAny = mainCount > 0 || hasSubAgents;

  return (
    <div className="text-sm">
      {/* Header */}
      <div
        className="flex items-center justify-between py-2 cursor-pointer hover:bg-muted/50 transition-colors px-2 rounded"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <span className="text-muted-foreground font-medium">
          {i18n('Progress')}
          {hasAny && (
            <span className="ml-1.5 text-xs text-muted-foreground/70">
              ({completedAll}/{totalAll})
            </span>
          )}
        </span>
        {isExpanded ? (
          <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />
        ) : (
          <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0" />
        )}
      </div>

      {/* Content */}
      {isExpanded && (
        <div className="pb-2">
          {!hasAny ? (
            <div className="text-muted-foreground text-xs px-2">{i18n('No tasks to track yet')}</div>
          ) : (
            <div className="space-y-1">
              {/* Main agent todos */}
              {mainCount > 0 && (
                <div className="space-y-0.5">
                  {mainTodos.map((todo) => (
                    <TodoItem key={todo.id} todo={todo} />
                  ))}
                </div>
              )}

              {/* Sub-agent groups */}
              {hasSubAgents && (
                <>
                  {mainCount > 0 && (
                    <div className="border-t border-dashed border-border my-1.5" />
                  )}
                  <div className="space-y-1.5">
                    {subAgentEntries.map(([agentName, group]) => (
                      <SubAgentGroup key={agentName} agentName={agentName} group={group} />
                    ))}
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
