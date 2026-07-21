import React, { useState, useMemo, useCallback, useRef } from 'react';
import { Plus, AlertTriangle } from 'lucide-react';
import type { SwarmTaskDto, SwarmAgentSpecDto } from '@/services/swarm-service';
import { validateDag } from '@/utils/dag-validator';
import {
  computeNodePositions,
  computeEdges,
  computeCanvasSize,
  generateTaskId,
  NODE_WIDTH,
  NODE_HEIGHT,
  type EdgeData,
} from '@/utils/dag-layout';
import { EditableWorkflowNode } from './EditableWorkflowNode';
import { i18n } from '@/utils/i18n';

interface WorkflowEditorCanvasProps {
  tasks: SwarmTaskDto[];
  onChange: (tasks: SwarmTaskDto[]) => void;
  selectedTaskId: string | null;
  onSelectTask: (taskId: string | null) => void;
  agents: SwarmAgentSpecDto[];
}

interface DragState {
  sourceTaskId: string;
}

/** Check if adding edge from→to would create a cycle */
function wouldCreateCycle(tasks: SwarmTaskDto[], fromId: string, toId: string): boolean {
  if (fromId === toId) return true;
  const visited = new Set<string>();
  const dfs = (current: string): boolean => {
    if (current === fromId) return true;
    if (visited.has(current)) return false;
    visited.add(current);
    const task = tasks.find((t) => t.id === current);
    return (task?.dependsOn ?? []).some((dep) => dfs(dep));
  };
  return dfs(toId);
}

const DEFAULT_TASK: SwarmTaskDto = {
  id: '',
  agentId: '',
  promptTemplate: '',
  dependsOn: [],
  inputFrom: {},
  type: 'SINGLE',
  maxRetries: 2,
};

export const WorkflowEditorCanvas: React.FC<WorkflowEditorCanvasProps> = ({
  tasks,
  onChange,
  selectedTaskId,
  onSelectTask,
  agents,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const dragLineRef = useRef<SVGLineElement>(null);
  const [dragState, setDragState] = useState<DragState | null>(null);
  const [hoveredEdge, setHoveredEdge] = useState<number | null>(null);
  const dragMouseRef = useRef({ x: 0, y: 0 });

  // Build agent role lookup
  const agentRoleMap = useMemo(
    () => new Map(agents.map((a) => [a.id, a.role])),
    [agents]
  );

  // Compute layout
  const positions = useMemo(() => computeNodePositions(tasks), [tasks]);
  const posMap = useMemo(() => new Map(positions.map((p) => [p.taskId, p])), [positions]);
  const canvasSize = useMemo(() => computeCanvasSize(positions), [positions]);
  const edges = useMemo(() => computeEdges(tasks, posMap), [tasks, posMap]);

  // DAG validation
  const dagResult = useMemo(() => validateDag(tasks), [tasks]);

  // Add a new task with given dependsOn
  const addTask = useCallback(
    (dependsOn: string[] = []) => {
      const existingIds = new Set(tasks.map((t) => t.id));
      const newId = generateTaskId(existingIds);
      const newTask: SwarmTaskDto = { ...DEFAULT_TASK, id: newId, dependsOn };
      onChange([...tasks, newTask]);
      onSelectTask(newId);
    },
    [tasks, onChange, onSelectTask]
  );

  // Add child task (depends on parent)
  const handleAddChild = useCallback(
    (parentId: string) => {
      addTask([parentId]);
    },
    [addTask]
  );

  // Add sibling task (same dependsOn as sibling)
  const handleAddSibling = useCallback(
    (siblingId: string) => {
      const sibling = tasks.find((t) => t.id === siblingId);
      addTask(sibling?.dependsOn ?? []);
    },
    [tasks, addTask]
  );

  // Delete edge (remove dependency) — also cleans up inputFrom referencing the removed edge
  const handleDeleteEdge = useCallback(
    (edge: EdgeData) => {
      const updated = tasks.map((t) => {
        if (t.id === edge.toId) {
          const newDeps = (t.dependsOn ?? []).filter((d) => d !== edge.fromId);
          // Recompute ancestors after removing this dependency
          const taskMap = new Map(tasks.map((x) => [x.id, x]));
          const ancestors = new Set<string>();
          const collect = (id: string) => {
            if (ancestors.has(id)) return;
            ancestors.add(id);
            for (const dep of taskMap.get(id)?.dependsOn ?? []) collect(dep);
          };
          for (const dep of newDeps) collect(dep);
          // Remove inputFrom entries that no longer reference an ancestor
          const cleanedInputFrom = Object.fromEntries(
            Object.entries(t.inputFrom ?? {}).filter(([, v]) => ancestors.has(v))
          );
          return { ...t, dependsOn: newDeps, inputFrom: cleanedInputFrom };
        }
        return t;
      });
      onChange(updated);
    },
    [tasks, onChange]
  );

  // Delete task node (remove from list and clean up references)
  const handleDeleteTask = useCallback(
    (taskId: string) => {
      const cleaned = tasks
        .filter((t) => t.id !== taskId)
        .map((t) => ({
          ...t,
          dependsOn: (t.dependsOn ?? []).filter((d) => d !== taskId),
          inputFrom: Object.fromEntries(
            Object.entries(t.inputFrom ?? {}).filter(([, v]) => v !== taskId)
          ),
        }));
      onChange(cleaned);
      onSelectTask(null);
    },
    [tasks, onChange, onSelectTask]
  );

  // Drag-to-connect: start
  const handleDragStart = useCallback((taskId: string, e: React.MouseEvent) => {
    e.preventDefault();
    setDragState({ sourceTaskId: taskId });
    const sourcePos = posMap.get(taskId);
    if (!sourcePos) return;
    const startX = sourcePos.x + NODE_WIDTH / 2;
    const startY = sourcePos.y + NODE_HEIGHT;
    dragMouseRef.current = { x: startX, y: startY };
    if (dragLineRef.current) {
      dragLineRef.current.setAttribute('x1', String(startX));
      dragLineRef.current.setAttribute('y1', String(startY));
      dragLineRef.current.setAttribute('x2', String(startX));
      dragLineRef.current.setAttribute('y2', String(startY));
      dragLineRef.current.style.display = '';
    }
  }, [posMap]);

  // Drag-to-connect: mouse move (update line via DOM for performance)
  const handleMouseMove = useCallback(
    (e: React.MouseEvent) => {
      if (!dragState || !containerRef.current || !dragLineRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const x = e.clientX - rect.left + containerRef.current.scrollLeft;
      const y = e.clientY - rect.top + containerRef.current.scrollTop;
      dragMouseRef.current = { x, y };
      dragLineRef.current.setAttribute('x2', String(x));
      dragLineRef.current.setAttribute('y2', String(y));
    },
    [dragState]
  );

  // Drag-to-connect: mouse up (check target and create connection)
  const handleMouseUp = useCallback(
    (e: React.MouseEvent) => {
      if (!dragState || !containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const mouseX = e.clientX - rect.left + containerRef.current.scrollLeft;
      const mouseY = e.clientY - rect.top + containerRef.current.scrollTop;

      // Find target node (check if mouse is over any node's input port area)
      const targetNode = positions.find((p) => {
        const portX = p.x + NODE_WIDTH / 2;
        const portY = p.y;
        const dx = mouseX - portX;
        const dy = mouseY - portY;
        return Math.abs(dx) < 20 && Math.abs(dy) < 20;
      });

      if (targetNode && targetNode.taskId !== dragState.sourceTaskId) {
        const targetTask = tasks.find((t) => t.id === targetNode.taskId);
        const alreadyDeps = (targetTask?.dependsOn ?? []).includes(dragState.sourceTaskId);

        if (!alreadyDeps && !wouldCreateCycle(tasks, dragState.sourceTaskId, targetNode.taskId)) {
          const updated = tasks.map((t) => {
            if (t.id === targetNode.taskId) {
              return { ...t, dependsOn: [...(t.dependsOn ?? []), dragState.sourceTaskId] };
            }
            return t;
          });
          onChange(updated);
        }
      }

      setDragState(null);
      if (dragLineRef.current) {
        dragLineRef.current.style.display = 'none';
      }
    },
    [dragState, positions, tasks, onChange]
  );

  // Cancel drag on mouse leave
  const handleMouseLeave = useCallback(() => {
    if (dragState) {
      setDragState(null);
      if (dragLineRef.current) {
        dragLineRef.current.style.display = 'none';
      }
    }
  }, [dragState]);

  // Handle background click to deselect
  const handleBackgroundClick = useCallback(() => {
    onSelectTask(null);
  }, [onSelectTask]);

  return (
    <div className="flex flex-col h-full">
      {/* DAG validation warning */}
      {!dagResult.valid && (
        <div className="flex items-center gap-2 px-3 py-2 mb-2 rounded-lg bg-destructive/10 text-destructive text-xs">
          <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
          <span className="flex-1">{dagResult.error}</span>
        </div>
      )}

      {/* Canvas area */}
      <div
        ref={containerRef}
        className="flex-1 overflow-auto relative rounded-lg border border-border bg-muted/20 min-h-[400px]"
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUp}
        onMouseLeave={handleMouseLeave}
        onClick={handleBackgroundClick}
      >
        <div
          className="relative"
          style={{ width: canvasSize.width, height: canvasSize.height, minWidth: '100%', minHeight: '100%' }}
        >
          {/* SVG edges */}
          <svg
            className="absolute inset-0"
            width={canvasSize.width}
            height={canvasSize.height}
            style={{ pointerEvents: 'none' }}
          >
            <defs>
              <marker
                id="wf-editor-arrowhead"
                markerWidth="6"
                markerHeight="4"
                refX="3"
                refY="2"
                orient="auto"
              >
                <polygon points="0 0, 6 2, 0 4" fill="currentColor" className="text-border" />
              </marker>
            </defs>

            {/* Edges */}
            {edges.map((edge, i) => {
              const pathD = `M ${edge.x1} ${edge.y1} C ${edge.x1} ${edge.y1 + 40}, ${edge.x2} ${edge.y2 - 40}, ${edge.x2} ${edge.y2}`;
              const midX = (edge.x1 + edge.x2) / 2;
              const midY = (edge.y1 + edge.y2) / 2;
              const isHovered = hoveredEdge === i;

              return (
                <g key={`${edge.fromId}-${edge.toId}`}>
                  {/* Hit area (wider invisible path) */}
                  <path
                    d={pathD}
                    fill="none"
                    stroke="transparent"
                    strokeWidth={14}
                    style={{ pointerEvents: 'stroke', cursor: 'pointer' }}
                    onMouseEnter={() => setHoveredEdge(i)}
                    onMouseLeave={() => setHoveredEdge(null)}
                    onClick={(e) => {
                      e.stopPropagation();
                      handleDeleteEdge(edge);
                    }}
                  />
                  {/* Visible edge */}
                  <path
                    d={pathD}
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={isHovered ? 2 : 1.5}
                    className={isHovered ? 'text-destructive' : 'text-border'}
                    markerEnd="url(#wf-editor-arrowhead)"
                    style={{ pointerEvents: 'none' }}
                  />
                  {/* Delete indicator on hover */}
                  {isHovered && (
                    <>
                      <circle
                        cx={midX}
                        cy={midY}
                        r={8}
                        fill="currentColor"
                        className="text-destructive"
                        style={{ pointerEvents: 'none' }}
                      />
                      <text
                        x={midX}
                        y={midY + 1}
                        textAnchor="middle"
                        dominantBaseline="central"
                        fill="white"
                        fontSize="10"
                        fontWeight="bold"
                        style={{ pointerEvents: 'none' }}
                      >
                        ×
                      </text>
                    </>
                  )}
                </g>
              );
            })}

            {/* Drag preview line */}
            <line
              ref={dragLineRef}
              stroke="currentColor"
              strokeWidth={2}
              strokeDasharray="6 3"
              className="text-primary"
              style={{ display: 'none', pointerEvents: 'none' }}
            />
          </svg>

          {/* Nodes */}
          {tasks.map((task) => {
            const pos = posMap.get(task.id);
            if (!pos) return null;
            return (
              <EditableWorkflowNode
                key={task.id}
                task={task}
                isSelected={selectedTaskId === task.id}
                onClick={() => onSelectTask(task.id)}
                onAddChild={handleAddChild}
                onAddSibling={handleAddSibling}
                onDelete={handleDeleteTask}
                onDragStart={handleDragStart}
                agentRole={agentRoleMap.get(task.agentId ?? '')}
                x={pos.x}
                y={pos.y}
              />
            );
          })}

          {/* Empty state */}
          {tasks.length === 0 && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 text-muted-foreground">
              <div className="text-sm">{i18n('No tasks yet')}</div>
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  addTask();
                }}
                className="flex items-center gap-2 px-4 py-2 rounded-lg border border-dashed border-border text-sm hover:text-foreground hover:border-primary/50 transition-colors"
              >
                <Plus className="w-4 h-4" />
                {i18n('Add first task')}
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Bottom toolbar */}
      {tasks.length > 0 && (
        <div className="flex items-center gap-2 mt-2">
          <button
            type="button"
            onClick={() => addTask()}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md border border-dashed border-border text-muted-foreground hover:text-foreground hover:border-primary/50 transition-colors"
          >
            <Plus className="w-3.5 h-3.5" />
            {i18n('Add root task')}
          </button>
          <span className="text-xs text-muted-foreground/60 ml-auto">
            {i18n('Drag from bottom port to top port to connect')} · {i18n('Click edge to delete')}
          </span>
        </div>
      )}
    </div>
  );
};
