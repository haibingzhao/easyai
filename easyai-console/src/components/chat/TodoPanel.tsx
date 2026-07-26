import React, { useState } from 'react';
import { ChevronDown, ChevronRight, CheckCircle2, Circle, Clock, XCircle, Bot, Network, Loader2, AlertTriangle, PauseCircle } from 'lucide-react';
import type { TodoInfo, SubAgentTodoGroup } from '@/types/todo';
import type { SwarmRunTracking } from '@/services/stores/chat-store';
import { i18n } from '@/utils/i18n';

interface TodoPanelProps {
  mainTodos: TodoInfo[];
  subAgentTodos: Record<string, SubAgentTodoGroup>;
  swarmRuns?: Record<string, SwarmRunTracking>;
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

/** Collapsible swarm run group with cyan/teal accent */
const SWARM_TASK_STATUS_ICON: Record<string, React.ReactNode> = {
  PENDING: <Circle className="w-3.5 h-3.5 text-muted-foreground" />,
  IN_PROGRESS: <Loader2 className="w-3.5 h-3.5 text-blue-500 animate-spin" />,
  COMPLETED: <CheckCircle2 className="w-3.5 h-3.5 text-green-500" />,
  FAILED: <XCircle className="w-3.5 h-3.5 text-red-500" />,
  BLOCKED: <AlertTriangle className="w-3.5 h-3.5 text-orange-500" />,
  PAUSED: <PauseCircle className="w-3.5 h-3.5 text-purple-500" />,
  CANCELLED: <XCircle className="w-3.5 h-3.5 text-gray-500" />,
};

function SwarmRunGroup({ tracking }: { tracking: SwarmRunTracking }) {
  const [isExpanded, setIsExpanded] = useState(false);
  const completedTasks = tracking.tasks.filter((t) => t.status === 'COMPLETED').length;

  return (
    <div className="border-l-2 border-cyan-500/40 ml-2">
      <div
        className="flex items-center justify-between py-1.5 cursor-pointer hover:bg-muted/50 transition-colors px-2 rounded-r"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <div className="flex items-center gap-1.5 min-w-0">
          <Network className="w-4 h-4 text-cyan-400 shrink-0" />
          <span className="text-xs font-medium text-cyan-600 dark:text-cyan-400 shrink-0">
            Swarm:
          </span>
          <span className="text-xs text-cyan-600 dark:text-cyan-400 truncate max-w-[140px]" title={tracking.presetName}>
            {tracking.presetName}
          </span>
        </div>
        <div className="flex items-center gap-1">
          <span className="text-xs text-muted-foreground">
            {completedTasks}/{tracking.tasks.length}
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
          {tracking.tasks.map((task) => (
            <div key={task.id} className="flex items-center gap-2 py-1 text-xs px-2">
              <span className="shrink-0">{SWARM_TASK_STATUS_ICON[task.status] ?? SWARM_TASK_STATUS_ICON.PENDING}</span>
              <span className="flex-1 text-foreground truncate">{task.id}</span>
              <span className="text-[10px] px-1 py-px rounded bg-muted text-muted-foreground shrink-0">
                {task.type}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function TodoPanel({ mainTodos, subAgentTodos, swarmRuns }: TodoPanelProps) {
  const [isExpanded, setIsExpanded] = useState(true);

  const mainCount = mainTodos.length;
  const mainCompleted = countByStatus(mainTodos, 'completed');

  // Aggregate sub-agent counts
  const subAgentEntries = Object.entries(subAgentTodos).filter(([, group]) => group.todos.length > 0);
  const hasSubAgents = subAgentEntries.length > 0;

  // Aggregate swarm run counts
  const swarmEntries = Object.entries(swarmRuns ?? {}).filter(([, r]) => r.tasks.length > 0);
  const hasSwarms = swarmEntries.length > 0;

  // Total across main + all sub-agents + swarm tasks
  const totalAll = mainCount
    + subAgentEntries.reduce((sum, [, group]) => sum + group.todos.length, 0)
    + swarmEntries.reduce((sum, [, r]) => sum + r.tasks.length, 0);
  const completedAll = mainCompleted
    + subAgentEntries.reduce((sum, [, group]) => sum + countByStatus(group.todos, 'completed'), 0)
    + swarmEntries.reduce((sum, [, r]) => sum + r.tasks.filter((t) => t.status === 'COMPLETED').length, 0);

  const hasAny = mainCount > 0 || hasSubAgents || hasSwarms;

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

              {/* Swarm run groups */}
              {hasSwarms && (
                <>
                  {(mainCount > 0 || hasSubAgents) && (
                    <div className="border-t border-dashed border-border my-1.5" />
                  )}
                  <div className="space-y-1.5">
                    {swarmEntries.map(([toolCallId, tracking]) => (
                      <SwarmRunGroup key={toolCallId} tracking={tracking} />
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
