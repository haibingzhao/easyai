import React from 'react';
import { CheckCircle2, XCircle, Loader2, Clock, AlertTriangle, PauseCircle, MessageSquare } from 'lucide-react';
import type { TaskSummary } from '@/services/swarm-service';
import { i18n } from '@/utils/i18n';
import { formatTokenCount } from '@/utils/format';
import { NODE_WIDTH, NODE_HEIGHT } from '@/utils/dag-layout';

type SwarmTaskStatus = TaskSummary['status'];

interface WorkflowNodeProps {
  task: TaskSummary;
  isSelected: boolean;
  onClick: () => void;
  onDoubleClick?: () => void;
  agentRole?: string;
  x: number;
  y: number;
  /** Whether this TEAM node is waiting for user consultation answer */
  waitingUserAnswer?: boolean;
}

const STATUS_CONFIG: Record<SwarmTaskStatus, { icon: React.ReactNode; color: string; label: string }> = {
  PENDING: {
    icon: <Clock className="w-3.5 h-3.5" />,
    color: 'text-muted-foreground',
    label: 'Pending',
  },
  IN_PROGRESS: {
    icon: <Loader2 className="w-3.5 h-3.5 animate-spin" />,
    color: 'text-blue-500',
    label: 'Running...',
  },
  COMPLETED: {
    icon: <CheckCircle2 className="w-3.5 h-3.5" />,
    color: 'text-green-500',
    label: 'Run Completed',
  },
  FAILED: {
    icon: <XCircle className="w-3.5 h-3.5" />,
    color: 'text-red-500',
    label: 'Run Failed',
  },
  BLOCKED: {
    icon: <AlertTriangle className="w-3.5 h-3.5" />,
    color: 'text-orange-500',
    label: 'Blocked',
  },
  PAUSED: {
    icon: <PauseCircle className="w-3.5 h-3.5" />,
    color: 'text-purple-500',
    label: 'Paused',
  },
  CANCELLED: {
    icon: <XCircle className="w-3.5 h-3.5" />,
    color: 'text-gray-500',
    label: 'Cancelled',
  },
};

export const WorkflowNode: React.FC<WorkflowNodeProps> = ({
  task,
  isSelected,
  onClick,
  onDoubleClick,
  agentRole,
  x,
  y,
  waitingUserAnswer,
}) => {
  const config = STATUS_CONFIG[task.status];

  return (
    <div
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      className={[
        'absolute rounded-lg border bg-card p-3 cursor-pointer transition-all',
        'hover:shadow-md',
        isSelected ? 'border-primary ring-2 ring-primary/30' : 'border-border',
        task.status === 'IN_PROGRESS' ? 'border-blue-400' : '',
        waitingUserAnswer ? 'border-amber-400 animate-pulse' : '',
      ].join(' ')}
      style={{
        left: x,
        top: y,
        width: NODE_WIDTH,
        minHeight: NODE_HEIGHT,
      }}
    >
      {/* Waiting-for-user badge */}
      {waitingUserAnswer && (
        <div className="absolute -top-2 -right-2 w-5 h-5 rounded-full bg-amber-500 flex items-center justify-center shadow-sm">
          <MessageSquare className="w-3 h-3 text-white" />
        </div>
      )}

      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-xs text-muted-foreground truncate">{agentRole || task.agentId || (task.type === 'DELIBERATION' ? 'Deliberation' : task.type === 'TEAM' ? 'Team Task' : task.id)}</div>
          <div className="text-xs text-muted-foreground/70 truncate mt-0.5">
            {task.type === 'DELIBERATION' ? 'Deliberation' : task.type === 'TEAM' ? 'Team' : 'Single'}
          </div>
        </div>
        <div className={`shrink-0 ${config.color}`}>{config.icon}</div>
      </div>

      <div className={`text-xs mt-2 ${waitingUserAnswer ? 'text-amber-500' : config.color}`}>
        {waitingUserAnswer ? i18n('Waiting for user answer') : i18n(config.label)}
      </div>

      {task.status === 'COMPLETED' && (
        <div className="text-xs text-muted-foreground mt-1">
          {formatTokenCount(task.inputTokens + task.outputTokens)} tokens
        </div>
      )}

      {task.error && (
        <div className="text-xs text-red-500 mt-1 truncate" title={task.error}>
          {task.error}
        </div>
      )}
    </div>
  );
};
