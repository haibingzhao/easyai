import React from 'react';
import { Plus, Trash2 } from 'lucide-react';
import type { SwarmTaskDto } from '@/services/swarm-service';
import { i18n } from '@/utils/i18n';
import { NODE_WIDTH, NODE_HEIGHT } from '@/utils/dag-layout';

interface EditableWorkflowNodeProps {
  task: SwarmTaskDto;
  isSelected: boolean;
  onClick: () => void;
  onDoubleClick?: () => void;
  onAddChild: (parentId: string) => void;
  onAddSibling: (siblingId: string) => void;
  onDelete: (taskId: string) => void;
  onDragStart: (taskId: string, e: React.MouseEvent) => void;
  agentRole?: string;
  x: number;
  y: number;
}

export const EditableWorkflowNode: React.FC<EditableWorkflowNodeProps> = ({
  task,
  isSelected,
  onClick,
  onDoubleClick,
  onAddChild,
  onAddSibling,
  onDelete,
  onDragStart,
  agentRole,
  x,
  y,
}) => {
  return (
    <div
      className={[
        'group absolute rounded-lg border bg-card cursor-pointer transition-all',
        'hover:shadow-md',
        isSelected ? 'border-primary ring-2 ring-primary/30' : 'border-border hover:border-primary/40',
      ].join(' ')}
      style={{ left: x, top: y, width: NODE_WIDTH, minHeight: NODE_HEIGHT }}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      onDoubleClick={(e) => {
        e.stopPropagation();
        onDoubleClick?.();
      }}
    >
      {/* Input port (top center) */}
      <div className="absolute -top-[5px] left-1/2 -translate-x-1/2 w-2.5 h-2.5 rounded-full bg-border border-2 border-card z-10 pointer-events-none" />

      {/* Hover: delete button (top-right corner) */}
      <button
        type="button"
        className="absolute -top-2 -right-2 w-5 h-5 rounded-full bg-destructive text-destructive-foreground opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center shadow-sm hover:scale-110 z-20"
        onClick={(e) => {
          e.stopPropagation();
          onDelete(task.id);
        }}
        title={i18n('Delete task')}
      >
        <Trash2 className="w-3 h-3" />
      </button>

      {/* Output port (bottom center) — draggable */}
      <div
        className="absolute -bottom-[5px] left-1/2 -translate-x-1/2 w-2.5 h-2.5 rounded-full bg-border border-2 border-card z-10 cursor-crosshair hover:bg-primary hover:border-primary hover:scale-150 transition-all"
        onMouseDown={(e) => {
          e.stopPropagation();
          onDragStart(task.id, e);
        }}
      />

      {/* Node body */}
      <div className="p-3">
        <div className="flex items-start justify-between gap-1.5">
          <div className="min-w-0 flex-1">
            <div className="text-sm font-medium truncate text-foreground">{task.id}</div>
            <div className="text-xs text-muted-foreground truncate mt-0.5">
              {agentRole || task.agentId || (task.type === 'TEAM' && task.team?.leader ? `Leader: ${task.team.leader}` : i18n('No agent'))}
            </div>
          </div>
          <span
            className={[
              'shrink-0 text-[10px] px-1.5 py-0.5 rounded font-medium',
              task.type === 'DELIBERATION'
                ? 'bg-purple-500/10 text-purple-500'
                : task.type === 'TEAM'
                  ? 'bg-teal-500/10 text-teal-500'
                  : 'bg-muted text-muted-foreground',
            ].join(' ')}
          >
            {task.type}
          </span>
        </div>

        {/* Dependency count hint */}
        {(task.dependsOn?.length ?? 0) > 0 && (
          <div className="text-[10px] text-muted-foreground/60 mt-1.5">
            ← {task.dependsOn!.length} {i18n('dep(s)')}
          </div>
        )}
      </div>

      {/* Hover: add child button (below node) */}
      <button
        type="button"
        className="absolute -bottom-7 left-1/2 -translate-x-1/2 w-5 h-5 rounded-full bg-primary text-primary-foreground opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center shadow-sm hover:scale-110 z-20"
        onClick={(e) => {
          e.stopPropagation();
          onAddChild(task.id);
        }}
        title={i18n('Add child task')}
      >
        <Plus className="w-3 h-3" />
      </button>

      {/* Hover: add sibling button (right of node) */}
      <button
        type="button"
        className="absolute top-1/2 -right-7 -translate-y-1/2 w-5 h-5 rounded-full bg-muted text-muted-foreground border border-border opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center shadow-sm hover:bg-primary hover:text-primary-foreground hover:border-primary z-20"
        onClick={(e) => {
          e.stopPropagation();
          onAddSibling(task.id);
        }}
        title={i18n('Add parallel task')}
      >
        <Plus className="w-3 h-3" />
      </button>
    </div>
  );
};
