import React, { useState, useCallback, useMemo } from 'react';
import type { SwarmTaskDto, SwarmVariableDto, SwarmAgentSpecDto } from '@/services/swarm-service';
import type { AgentDto, AgentEnv } from '@/types/agent';
import { WorkflowEditorCanvas } from './WorkflowEditorCanvas';
import { TaskDetailPanel } from './TaskDetailPanel';

interface SwarmTaskEditorProps {
  tasks: SwarmTaskDto[];
  onChange: (tasks: SwarmTaskDto[]) => void;
  agents: SwarmAgentSpecDto[];
  variables: SwarmVariableDto[];
  availableAgents?: AgentDto[];
}

export const SwarmTaskEditor: React.FC<SwarmTaskEditorProps> = ({
  tasks,
  onChange,
  agents,
  variables,
  availableAgents,
}) => {
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);

  const selectedTask = selectedTaskId ? tasks.find((t) => t.id === selectedTaskId) : null;

  // Build agentContextMap: agent spec ID → agentContext from the agent store
  const agentContextMap: Record<string, AgentEnv> | undefined = useMemo(() => {
    if (!availableAgents || availableAgents.length === 0) return undefined;
    const agentMap: Record<string, AgentEnv> = {};
    for (const spec of agents) {
      const agentDto = availableAgents.find((a) => a.id === spec.agentDefinitionId);
      if (agentDto) {
        agentMap[spec.id] = agentDto.agentContext;
      }
    }
    return agentMap;
  }, [availableAgents, agents]);

  // Handle task update from detail panel
  const handleTaskUpdate = useCallback(
    (updatedTask: SwarmTaskDto) => {
      const idx = tasks.findIndex((t) => t.id === selectedTaskId);
      if (idx === -1) {
        // ID might have changed, find by position or just use the updated task
        const updated = tasks.map((t) => (t === selectedTask ? updatedTask : t));
        onChange(updated);
        return;
      }

      const oldId = tasks[idx].id;
      const newId = updatedTask.id;

      // Guard: prevent empty ID — revert to old ID
      if (!newId.trim()) {
        const guarded = { ...updatedTask, id: oldId };
        const updated = [...tasks];
        updated[idx] = guarded;
        onChange(updated);
        return;
      }

      if (oldId !== newId) {
        // ID changed — propagate to downstream references
        const updated = tasks.map((t, i) => {
          if (i === idx) return updatedTask;
          return {
            ...t,
            dependsOn: (t.dependsOn ?? []).map((d) => (d === oldId ? newId : d)),
            inputFrom: Object.fromEntries(
              Object.entries(t.inputFrom ?? {}).map(([k, v]) => [k, v === oldId ? newId : v])
            ),
          };
        });
        onChange(updated);
        setSelectedTaskId(newId);
      } else {
        const updated = [...tasks];
        updated[idx] = updatedTask;
        onChange(updated);
      }
    },
    [tasks, selectedTaskId, onChange]
  );

  // Handle task deletion
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
      setSelectedTaskId(null);
    },
    [tasks, onChange]
  );

  return (
    <div className="flex rounded-lg border border-border overflow-hidden" style={{ height: 560 }}>
      {/* Left: Workflow Canvas */}
      <div className="flex-1 min-w-0 p-3">
        <WorkflowEditorCanvas
          tasks={tasks}
          onChange={onChange}
          selectedTaskId={selectedTaskId}
          onSelectTask={setSelectedTaskId}
          agents={agents}
        />
      </div>

      {/* Right: Task Detail Panel */}
      {selectedTask && (
        <TaskDetailPanel
          task={selectedTask}
          allTasks={tasks}
          agents={agents}
          variables={variables}
          agentContextMap={agentContextMap}
          availableAgents={availableAgents}
          onUpdate={handleTaskUpdate}
          onDelete={handleDeleteTask}
          onClose={() => setSelectedTaskId(null)}
        />
      )}
    </div>
  );
};
