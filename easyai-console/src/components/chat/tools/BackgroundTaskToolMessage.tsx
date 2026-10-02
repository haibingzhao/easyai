/**
 * Background Task Tools 消息渲染组件
 *
 * 渲染 run_background / task_status / task_list 三个工具的调用卡片：
 * - run_background: 显示任务启动信息、状态徽章（running/completed/failed/cancelled）、结果预览
 * - task_status: 显示单任务详情
 * - task_list: 显示任务列表摘要
 */

import { useState } from 'react';
import {
  Play,
  CircleDot,
  ListChecks,
  ChevronDown,
  CheckCircle2,
  XCircle,
  XOctagon,
  Loader2,
} from 'lucide-react';
import type { ToolMessageProps } from './types';
import { getToolRowSummary } from './parsers';
import { ToolRowHeader } from './ToolRowHeader';
import { useStreamingRowExpand } from './useStreamingRowExpand';
import { useChatStore } from '@/services/stores/chat-store';

// ---------------------------------------------------------------------------
// Argument parsing
// ---------------------------------------------------------------------------

interface RunBackgroundArgs {
  toolName: string;
  arguments: Record<string, unknown>;
  description?: string;
}

interface TaskStatusArgs {
  taskId: string;
}

interface TaskListArgs {
  status?: string;
}

function parseRunBackgroundArgs(args: string): RunBackgroundArgs | null {
  try {
    return JSON.parse(args);
  } catch {
    return null;
  }
}

function parseTaskStatusArgs(args: string): TaskStatusArgs | null {
  try {
    return JSON.parse(args);
  } catch {
    return null;
  }
}

function parseTaskListArgs(args: string): TaskListArgs | null {
  try {
    return JSON.parse(args);
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Status badge
// ---------------------------------------------------------------------------

function StatusBadge({ status }: { status: string }) {
  const config = {
    RUNNING: {
      label: 'Running',
      color: 'text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-950/40',
      icon: <Loader2 className="size-3 animate-spin" />,
    },
    COMPLETED: {
      label: 'Completed',
      color: 'text-green-600 dark:text-green-400 bg-green-50 dark:bg-green-950/40',
      icon: <CheckCircle2 className="size-3" />,
    },
    FAILED: {
      label: 'Failed',
      color: 'text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-950/40',
      icon: <XCircle className="size-3" />,
    },
    CANCELLED: {
      label: 'Cancelled',
      color: 'text-gray-600 dark:text-gray-400 bg-gray-100 dark:bg-gray-800/60',
      icon: <XOctagon className="size-3" />,
    },
  }[status] ?? {
    label: status,
    color: 'text-gray-600 dark:text-gray-400 bg-gray-100 dark:bg-gray-800/60',
    icon: <CircleDot className="size-3" />,
  };

  return (
    <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-xs font-medium ${config.color}`}>
      {config.icon}
      {config.label}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Tool execution status dot
// ---------------------------------------------------------------------------

function ExecStatusDot({ status }: { status: ToolMessageProps['status'] }) {
  if (status === 'RUNNING' || status === 'PENDING') {
    return <span className="w-2 h-2 rounded-full bg-muted-foreground animate-pulse" />;
  }
  if (status === 'FAILED') {
    return <span className="w-2 h-2 rounded-full bg-destructive" />;
  }
  return <span className="w-2 h-2 rounded-full bg-green-500" />;
}

// ---------------------------------------------------------------------------
// run_background renderer
// ---------------------------------------------------------------------------

function RunBackgroundRenderer({ toolCall, result, status, compact }: ToolMessageProps) {
  const [isExpanded, setIsExpanded] = useState(false);
  const markTouched = useStreamingRowExpand(status, setIsExpanded);
  const parsed = parseRunBackgroundArgs(toolCall.args);
  const backgroundTasks = useChatStore((s) => s.backgroundTasks);
  const isStreaming = useChatStore((s) => s.isStreaming);

  if (!parsed) {
    return (
      <div className="border border-border rounded-lg bg-card overflow-hidden">
        <div className="p-3 flex items-center gap-2">
          <Play className="size-4 text-muted-foreground" />
          <span className="text-sm font-medium">Background Task</span>
          <span className="text-xs text-muted-foreground">{toolCall.args}</span>
        </div>
      </div>
    );
  }

  // Extract taskId from result text (format: "Task ID: <uuid>")
  const taskIdMatch = result?.result?.match(/Task ID:\s*([a-f0-9-]+)/i);
  const taskId = taskIdMatch?.[1];
  const taskEvent = taskId ? backgroundTasks[taskId] : null;
  // For historical sessions (not streaming), infer completed status since session has ended
  const taskStatus = taskEvent?.event === 'completed' ? 'COMPLETED'
    : taskEvent?.event === 'failed' ? 'FAILED'
    : taskEvent?.event === 'cancelled' ? 'CANCELLED'
    : !isStreaming ? 'COMPLETED'
    : 'RUNNING';
  // Override tool call status for historical sessions: run_background returns immediately
  // so stored status may be 'RUNNING', but the task has already completed/failed
  const effectiveStatus = !isStreaming && (status === 'RUNNING' || status === 'PENDING') ? 'COMPLETED' : status;

  const rowHeader = (
    <ToolRowHeader
      toolName={toolCall.toolName}
      status={effectiveStatus ?? 'PENDING'}
      summary={getToolRowSummary(toolCall.toolName, toolCall.args)}
      expanded={isExpanded}
      onToggle={() => { markTouched(); setIsExpanded(prev => !prev); }}
    />
  );

  if (compact && !isExpanded) return rowHeader;

  return (
    <div className={compact ? 'overflow-hidden' : 'border border-border rounded-lg bg-card overflow-hidden'}>
      {compact ? rowHeader : (
        <>
          <div
            className="p-3 flex items-center justify-between gap-2 cursor-pointer hover:bg-muted/50 transition-colors"
            onClick={() => { markTouched(); setIsExpanded(prev => !prev); }}
          >
            <div className="flex items-center gap-2 min-w-0">
              <Play className="size-4 text-blue-500 dark:text-blue-400 shrink-0" />
              <span className="text-sm font-medium text-foreground shrink-0">Background Task</span>
              <span className="text-xs text-muted-foreground truncate">
                {parsed.toolName}
              </span>
              {parsed.description && (
                <span className="text-xs text-muted-foreground truncate">
                  — {parsed.description}
                </span>
              )}
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <ExecStatusDot status={effectiveStatus} />
              {taskId && effectiveStatus !== 'RUNNING' && effectiveStatus !== 'PENDING' && (
                <StatusBadge status={taskStatus} />
              )}
              <ChevronDown
                className={`size-4 text-muted-foreground transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
              />
            </div>
          </div>
        </>
      )}

      {isExpanded && (
        <>
          <div className="border-t border-border" />
          <div className="p-3 space-y-2">
            <div className="text-xs space-y-1">
              {taskId && (
                <div className="flex items-center gap-2">
                  <span className="text-muted-foreground">Task ID:</span>
                  <code className="text-xs bg-muted px-1.5 py-0.5 rounded">{taskId}</code>
                </div>
              )}
              <div className="flex items-center gap-2">
                <span className="text-muted-foreground">Tool:</span>
                <code className="text-xs bg-muted px-1.5 py-0.5 rounded">{parsed.toolName}</code>
              </div>
              {taskStatus && taskId && (
                <div className="flex items-center gap-2">
                  <span className="text-muted-foreground">Status:</span>
                  <StatusBadge status={taskStatus} />
                </div>
              )}
              {taskEvent?.durationMs !== undefined && (
                <div className="flex items-center gap-2">
                  <span className="text-muted-foreground">Duration:</span>
                  <span>{(taskEvent.durationMs / 1000).toFixed(2)}s</span>
                </div>
              )}
            </div>

            {taskEvent?.result && (
              <div className="text-xs">
                <div className="text-muted-foreground mb-1">Result:</div>
                <pre className="text-xs bg-muted/40 rounded px-3 py-2 whitespace-pre-wrap break-all max-h-[20em] overflow-y-auto">
                  {taskEvent.result}
                </pre>
              </div>
            )}

            {taskEvent?.error && (
              <div className="text-xs">
                <div className="text-destructive mb-1">Error:</div>
                <pre className="text-xs bg-destructive/10 rounded px-3 py-2 whitespace-pre-wrap break-all max-h-[10em] overflow-y-auto text-destructive">
                  {taskEvent.error}
                </pre>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// task_status renderer
// ---------------------------------------------------------------------------

function TaskStatusRenderer({ toolCall, result, status, compact }: ToolMessageProps) {
  const [isExpanded, setIsExpanded] = useState(false);
  const markTouched = useStreamingRowExpand(status, setIsExpanded);
  const parsed = parseTaskStatusArgs(toolCall.args);
  const backgroundTasks = useChatStore((s) => s.backgroundTasks);
  const isStreaming = useChatStore((s) => s.isStreaming);

  if (!parsed) {
    return (
      <div className="border border-border rounded-lg bg-card overflow-hidden">
        <div className="p-3 flex items-center gap-2">
          <CircleDot className="size-4 text-muted-foreground" />
          <span className="text-sm font-medium">Task Status</span>
        </div>
      </div>
    );
  }

  const taskEvent = backgroundTasks[parsed.taskId];
  // For historical sessions (not streaming), infer completed status since session has ended
  const inferredStatus = !taskEvent && !isStreaming ? 'completed' : taskEvent?.event;

  const rowHeader = (
    <ToolRowHeader
      toolName={toolCall.toolName}
      status={status ?? 'PENDING'}
      summary={getToolRowSummary(toolCall.toolName, toolCall.args)}
      expanded={isExpanded}
      onToggle={() => { markTouched(); setIsExpanded(prev => !prev); }}
    />
  );

  if (compact && !isExpanded) return rowHeader;

  return (
    <div className={compact ? 'overflow-hidden' : 'border border-border rounded-lg bg-card overflow-hidden'}>
      {compact ? rowHeader : (
        <>
          <div
            className="p-3 flex items-center justify-between gap-2 cursor-pointer hover:bg-muted/50 transition-colors"
            onClick={() => { markTouched(); setIsExpanded(prev => !prev); }}
          >
            <div className="flex items-center gap-2 min-w-0">
              <CircleDot className="size-4 text-purple-500 dark:text-purple-400 shrink-0" />
              <span className="text-sm font-medium text-foreground shrink-0">Task Status</span>
              <code className="text-xs text-muted-foreground truncate">{parsed.taskId}</code>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <ExecStatusDot status={status} />
              {(taskEvent || inferredStatus) && (
                <StatusBadge status={
                  inferredStatus === 'completed' ? 'COMPLETED'
                  : inferredStatus === 'failed' ? 'FAILED'
                  : inferredStatus === 'cancelled' ? 'CANCELLED'
                  : 'RUNNING'
                } />
              )}
              <ChevronDown
                className={`size-4 text-muted-foreground transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
              />
            </div>
          </div>
        </>
      )}

      {isExpanded && result?.result && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <pre className="text-xs bg-muted/40 rounded px-3 py-2 whitespace-pre-wrap break-all max-h-[20em] overflow-y-auto">
              {result.result}
            </pre>
          </div>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// task_list renderer
// ---------------------------------------------------------------------------

function TaskListRenderer({ toolCall, result, status, compact }: ToolMessageProps) {
  const [isExpanded, setIsExpanded] = useState(false);
  const markTouched = useStreamingRowExpand(status, setIsExpanded);
  const parsed = parseTaskListArgs(toolCall.args);

  const rowHeader = (
    <ToolRowHeader
      toolName={toolCall.toolName}
      status={status ?? 'PENDING'}
      summary={getToolRowSummary(toolCall.toolName, toolCall.args)}
      expanded={isExpanded}
      onToggle={() => { markTouched(); setIsExpanded(prev => !prev); }}
    />
  );

  if (compact && !isExpanded) return rowHeader;

  return (
    <div className={compact ? 'overflow-hidden' : 'border border-border rounded-lg bg-card overflow-hidden'}>
      {compact ? rowHeader : (
        <>
          <div
            className="p-3 flex items-center justify-between gap-2 cursor-pointer hover:bg-muted/50 transition-colors"
            onClick={() => { markTouched(); setIsExpanded(prev => !prev); }}
          >
            <div className="flex items-center gap-2 min-w-0">
              <ListChecks className="size-4 text-indigo-500 dark:text-indigo-400 shrink-0" />
              <span className="text-sm font-medium text-foreground shrink-0">Task List</span>
              {parsed?.status && (
                <span className="text-xs text-muted-foreground">
                  (filter: {parsed.status})
                </span>
              )}
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <ExecStatusDot status={status} />
              <ChevronDown
                className={`size-4 text-muted-foreground transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
              />
            </div>
          </div>
        </>
      )}

      {isExpanded && result?.result && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <pre className="text-xs bg-muted/40 rounded px-3 py-2 whitespace-pre-wrap break-all max-h-[20em] overflow-y-auto">
              {result.result}
            </pre>
          </div>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main component (router by toolName)
// ---------------------------------------------------------------------------

export function BackgroundTaskToolMessage(props: ToolMessageProps) {
  switch (props.toolCall.toolName) {
    case 'run_background':
      return <RunBackgroundRenderer {...props} />;
    case 'task_status':
      return <TaskStatusRenderer {...props} />;
    case 'task_list':
      return <TaskListRenderer {...props} />;
    default:
      return <RunBackgroundRenderer {...props} />;
  }
}
