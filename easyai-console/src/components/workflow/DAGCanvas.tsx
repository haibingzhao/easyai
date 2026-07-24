import React, { useState, useMemo, useCallback, useEffect } from 'react';
import { useResizable } from '@/hooks/useResizable';
import { ArrowLeft, Play, History, StopCircle, PauseCircle, X, Globe } from 'lucide-react';
import type { PresetInfo, TaskSummary } from '@/services/swarm-service';
import { useSwarmStore } from '@/services/stores/swarm-store';
import { modelConfigService } from '@/services/model-config-service';
import { storageService } from '@/services/storage-service';
import type { ModelProviderConfig } from '@/types/settings';
import { WorkflowNode } from './WorkflowNode';
import { NodeDetailPanel } from './NodeDetailPanel';
import { RunHistoryPanel } from './RunHistoryPanel';
import { TeamSubCanvas } from '@/components/swarm/TeamSubCanvas';
import { TeamConsultationPanel } from '@/components/swarm/TeamConsultationPanel';
import { i18n } from '@/utils/i18n';
import type { RunSummary } from '@/services/swarm-service';
import {
  computeNodePositions,
  computeEdges,
  computeCanvasSize,
  type NodePosition,
} from '@/utils/dag-layout';

interface DAGCanvasProps {
  preset: PresetInfo;
  onBack: () => void;
}

export const DAGCanvas: React.FC<DAGCanvasProps> = ({ preset, onBack }) => {
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [showHistory, setShowHistory] = useState(false);
  const [variableValues, setVariableValues] = useState<Record<string, string>>({});
  const [showVarDialog, setShowVarDialog] = useState(false);
  const [validationErrors, setValidationErrors] = useState<Record<string, boolean>>({});
  const [modelConfigs, setModelConfigs] = useState<ModelProviderConfig[]>([]);
  const [groupNames, setGroupNames] = useState<Record<string, string>>({});
  const [selectedModelConfigId, setSelectedModelConfigId] = useState<string>(
    () => storageService.getSwarmPresetModel(preset.name),
  );
  const [activeSidebarTab, setActiveSidebarTab] = useState<'detail' | 'history'>('detail');
  const [sidebarWidth, setSidebarWidth] = useState(380);
  const [panelResizing, setPanelResizing] = useState(false);
  const [subCanvasTaskId, setSubCanvasTaskId] = useState<string | null>(null);

  const sidebarResizer = useResizable({
    minWidth: 280,
    maxWidth: 700,
    onResize: (w) => setSidebarWidth(Math.round(w)),
    direction: 'left',
    onResizeStart: () => setPanelResizing(true),
    onResizeEnd: () => setPanelResizing(false),
  });

  const {
    activeRunDetail,
    runs,
    launchRun,
    cancelRun,
    pauseRun,
    resumeRun,
    deleteRun,
    loadRunDetail,
    pollActiveRun,
    loadRuns,
    pendingConsultation,
    clearPendingConsultation,
  } = useSwarmStore();

  const positions = useMemo(() => computeNodePositions(preset.tasks), [preset.tasks]);
  const posMap = useMemo(() => new Map<string, NodePosition>(positions.map((p) => [p.taskId, p])), [positions]);
  const canvasSize = useMemo(() => computeCanvasSize(positions), [positions]);

  // Merge preset tasks with run detail task statuses
  const taskStatuses = useMemo(() => {
    const map = new Map<string, TaskSummary>();
    if (activeRunDetail) {
      activeRunDetail.tasks.forEach((t) => map.set(t.id, t));
    }
    return map;
  }, [activeRunDetail]);

  const isRunning = activeRunDetail?.status === 'RUNNING' || activeRunDetail?.status === 'PENDING';
  const isPaused = activeRunDetail?.status === 'PAUSED';
  const isDryRun = activeRunDetail?.dryRun === true;

  // Load model configs and group names when variable dialog opens
  useEffect(() => {
    if (showVarDialog) {
      modelConfigService.getUserConfigurations().then(setModelConfigs).catch(() => {/* ignore */});
      modelConfigService.getGroups().then((groups) => {
        const map: Record<string, string> = {};
        groups.forEach((g) => { map[g.id] = g.name; });
        setGroupNames(map);
      }).catch(() => {/* ignore */});
    }
  }, [showVarDialog]);

  // Variables shown in the run dialog: exclude updatable ones (those are set by agents at runtime)
  const runtimeVariables = useMemo(
    () => (preset.variables ?? []).filter((v) => !v.updatable),
    [preset.variables],
  );

  const handleRun = useCallback(() => {
    if (runtimeVariables.length > 0) {
      const defaults: Record<string, string> = {};
      runtimeVariables.forEach((v) => { defaults[v.name] = v.defaultValue ?? ''; });
      setVariableValues(defaults);
      setValidationErrors({});
      setShowVarDialog(true);
    } else {
      launchRun(preset.name);
    }
  }, [preset, launchRun, runtimeVariables]);

  const handleStartRun = useCallback(() => {
    // Validate required fields
    const errors: Record<string, boolean> = {};
    let hasError = false;
    runtimeVariables.forEach((v) => {
      if (v.required && !variableValues[v.name]?.trim()) {
        errors[v.name] = true;
        hasError = true;
      }
    });
    if (hasError) {
      setValidationErrors(errors);
      return;
    }
    setShowVarDialog(false);
    launchRun(preset.name, variableValues, selectedModelConfigId || undefined);
  }, [preset.name, variableValues, selectedModelConfigId, launchRun, runtimeVariables]);

  const handleStartDryRun = useCallback(() => {
    // Validate required fields (same as normal run)
    const errors: Record<string, boolean> = {};
    let hasError = false;
    runtimeVariables.forEach((v) => {
      if (v.required && !variableValues[v.name]?.trim()) {
        errors[v.name] = true;
        hasError = true;
      }
    });
    if (hasError) {
      setValidationErrors(errors);
      return;
    }
    setShowVarDialog(false);
    launchRun(preset.name, variableValues, selectedModelConfigId || undefined, true);
  }, [preset.name, variableValues, selectedModelConfigId, launchRun, runtimeVariables]);

  const handleCancel = useCallback(() => {
    if (activeRunDetail) {
      cancelRun(activeRunDetail.id);
    }
  }, [activeRunDetail, cancelRun]);

  const handlePause = useCallback(() => {
    if (activeRunDetail) {
      pauseRun(activeRunDetail.id);
    }
  }, [activeRunDetail, pauseRun]);

  const handleResume = useCallback(() => {
    if (activeRunDetail) {
      resumeRun(activeRunDetail.id);
    }
  }, [activeRunDetail, resumeRun]);

  const handleSelectHistoryRun = useCallback(
    (run: RunSummary) => {
      setShowHistory(false);
      loadRunDetail(run.id);
      // Don't start polling for historical runs that are already done
      if (run.status === 'RUNNING' || run.status === 'PENDING') {
        pollActiveRun(run.id);
      }
    },
    [loadRunDetail, pollActiveRun]
  );

  const handleShowHistory = useCallback(async () => {
    await loadRuns();
    setShowHistory(true);
  }, [loadRuns]);

  // Compute SVG edges
  const svgEdges = useMemo(() => computeEdges(preset.tasks, posMap), [preset.tasks, posMap]);

  const agentMap = useMemo(
    () => new Map(preset.agents.map((a) => [a.id, a.role])),
    [preset.agents]
  );

  const selectedPresetTask = useMemo(
    () => preset.tasks.find((t) => t.id === selectedTaskId),
    [selectedTaskId, preset.tasks],
  );

  const selectedTask = useMemo(() => {
    if (!selectedTaskId) return null;
    // Prefer runtime status from activeRunDetail
    const runtimeTask = taskStatuses.get(selectedTaskId);
    if (runtimeTask) return runtimeTask;
    // Fallback to preset task definition (for pending nodes before any run)
    const presetTask = preset.tasks.find((t) => t.id === selectedTaskId);
    if (presetTask) {
      return {
        id: presetTask.id,
        agentId: presetTask.agentId,
        type: presetTask.type,
        status: 'PENDING' as const,
        summary: null,
        error: null,
        workerIterations: 0,
        inputTokens: 0,
        outputTokens: 0,
      };
    }
    return null;
  }, [selectedTaskId, taskStatuses, preset.tasks]);

  const selectedTaskLabel = useMemo(() => {
    if (!selectedTask) return '';
    if (selectedTask.type === 'DELIBERATION' && selectedPresetTask?.deliberation) {
      return selectedPresetTask.id;
    }
    if (selectedTask.type === 'TEAM' && selectedPresetTask?.team) {
      return selectedPresetTask.team.leader;
    }
    return selectedTask.agentId || selectedTask.id;
  }, [selectedTask, selectedPresetTask]);

  // Filter runs to this preset
  const presetRuns = runs.filter((r) => r.presetName === preset.name);

  // Auto-switch tabs: open → switch to it; close → fallback to remaining tab
  useEffect(() => {
    if (selectedTask) {
      setActiveSidebarTab('detail');
    } else if (showHistory) {
      setActiveSidebarTab('history');
    }
  }, [selectedTask, showHistory]);

  useEffect(() => {
    if (showHistory) {
      setActiveSidebarTab('history');
    } else if (selectedTask) {
      setActiveSidebarTab('detail');
    }
  }, [showHistory, selectedTask]);

  return (
    <div className="flex flex-col h-full">
      {/* Toolbar */}
      <div className="flex items-center gap-2 px-4 py-2 border-b border-border shrink-0">
        <button
          onClick={onBack}
          className="inline-flex items-center gap-1.5 px-2.5 py-1.5 text-sm rounded-md hover:bg-muted transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          {i18n('Back')}
        </button>

        <span className="font-medium text-sm truncate flex-1">{preset.title}</span>
        {isDryRun && (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded-full bg-amber-500/10 text-amber-600 shrink-0">
            {i18n('Dry Run')}
          </span>
        )}
        {(activeRunDetail?.language || preset.language) && (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded-full bg-blue-500/10 text-blue-600 shrink-0">
            <Globe className="w-3 h-3" />
            {activeRunDetail?.language || preset.language}
          </span>
        )}

        {isRunning ? (
          <>
            <button
              onClick={handleRun}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              <Play className="w-4 h-4" />
              {i18n('Launch Run')}
            </button>
            {!isDryRun && (
              <button
                onClick={handlePause}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-yellow-500/10 text-yellow-600 hover:bg-yellow-500/20 transition-colors"
              >
                <PauseCircle className="w-4 h-4" />
                {i18n('Pause')}
              </button>
            )}
            <button
              onClick={handleCancel}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-red-500/10 text-red-600 hover:bg-red-500/20 transition-colors"
            >
              <StopCircle className="w-4 h-4" />
              {i18n('Cancel Run')}
            </button>
          </>
        ) : isPaused ? (
          <>
            <button
              onClick={handleRun}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              <Play className="w-4 h-4" />
              {i18n('Launch Run')}
            </button>
            <button
              onClick={handleResume}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-green-500/10 text-green-600 hover:bg-green-500/20 transition-colors"
            >
              <Play className="w-4 h-4" />
              {i18n('Resume')}
            </button>
            <button
              onClick={handleCancel}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-red-500/10 text-red-600 hover:bg-red-500/20 transition-colors"
            >
              <StopCircle className="w-4 h-4" />
              {i18n('Cancel Run')}
            </button>
          </>
        ) : (
          <button
            onClick={handleRun}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <Play className="w-4 h-4" />
            {i18n('Launch Run')}
          </button>
        )}

        <button
          onClick={handleShowHistory}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md border border-border hover:bg-muted transition-colors"
        >
          <History className="w-4 h-4" />
          {i18n('Run History')}
        </button>
      </div>

      {/* Main area */}
      <div className="flex flex-1 min-h-0">
        {/* DAG canvas */}
        <div className="flex-1 overflow-auto relative bg-muted/30 p-4">
          <div className="relative" style={{ width: canvasSize.width, height: canvasSize.height, minWidth: 400, minHeight: 300 }}>
            {/* SVG edges */}
            <svg className="absolute inset-0 pointer-events-none" width={canvasSize.width} height={canvasSize.height}>
              <defs>
                <marker
                  id="arrowhead"
                  markerWidth="6"
                  markerHeight="4"
                  refX="3"
                  refY="2"
                  orient="auto"
                >
                  <polygon points="0 0, 6 2, 0 4" fill="currentColor" className="text-border" />
                </marker>
              </defs>
              {svgEdges.map((edge, i) => (
                <path
                  key={i}
                  d={`M ${edge.x1} ${edge.y1} C ${edge.x1} ${edge.y1 + 40}, ${edge.x2} ${edge.y2 - 40}, ${edge.x2} ${edge.y2}`}
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  className="text-border"
                  markerEnd="url(#arrowhead)"
                />
              ))}
            </svg>

            {/* Nodes */}
            {preset.tasks.map((task) => {
              const pos = posMap.get(task.id);
              if (!pos) return null;
              const taskStatus = taskStatuses.get(task.id);

              return (
                <WorkflowNode
                  key={task.id}
                  task={
                    taskStatus || {
                      id: task.id,
                      agentId: task.agentId,
                      type: task.type,
                      status: 'PENDING',
                      summary: null,
                      error: null,
                      workerIterations: 0,
                      inputTokens: 0,
                      outputTokens: 0,
                    }
                  }
                  isSelected={selectedTaskId === task.id}
                  onClick={() => setSelectedTaskId(selectedTaskId === task.id ? null : task.id)}
                  onDoubleClick={task.type === 'TEAM' && task.team ? () => setSubCanvasTaskId(task.id) : undefined}
                  waitingUserAnswer={pendingConsultation?.taskId === task.id}
                  agentRole={agentMap.get(task.agentId)}
                  x={pos.x}
                  y={pos.y}
                />
              );
            })}
          </div>

          {/* Team Sub-Canvas overlay */}
          {subCanvasTaskId && (() => {
            const subTask = preset.tasks.find((t) => t.id === subCanvasTaskId);
            if (!subTask?.team) return null;
            return (
              <div className="absolute inset-0 z-30 bg-background/95 p-4">
                <TeamSubCanvas
                  teamSpec={subTask.team}
                  agents={preset.agents}
                  onBack={() => setSubCanvasTaskId(null)}
                  readOnly
                />
              </div>
            );
          })()}

          {/* Team Consultation Panel — floating bottom-right */}
          {pendingConsultation && (
            <div className="absolute bottom-4 right-4 z-40 w-80">
              <TeamConsultationPanel
                runId={pendingConsultation.runId}
                taskId={pendingConsultation.taskId}
                consultation={pendingConsultation.consultation}
                onResolved={clearPendingConsultation}
                onClose={clearPendingConsultation}
              />
            </div>
          )}
        </div>

        {/* Drag handle */}
        {(selectedTask || showHistory) && (
          <div
            className={`resize-handle ${panelResizing ? 'active' : ''}`}
            onMouseDown={(e) => {
              sidebarResizer.setCurrentWidth(sidebarWidth);
              sidebarResizer.onMouseDown(e);
            }}
            onTouchStart={(e) => {
              sidebarResizer.setCurrentWidth(sidebarWidth);
              sidebarResizer.onTouchStart(e);
            }}
          />
        )}

        {/* Right sidebar with tab switching */}
        {(selectedTask || showHistory) && (
          <div className="flex flex-col min-h-0 shrink-0" style={{ width: sidebarWidth }}>
            {/* Tab bar */}
            <div className="flex items-center border-b border-border bg-muted/20 shrink-0">
              {selectedTask && (
                <button
                  onClick={() => setActiveSidebarTab('detail')}
                  className={[
                    'inline-flex items-center gap-1 px-3 py-2 text-xs font-medium transition-colors border-b-2 whitespace-nowrap',
                    activeSidebarTab === 'detail'
                      ? 'border-primary text-primary'
                      : 'border-transparent text-muted-foreground hover:text-foreground',
                  ].join(' ')}
                >
                  {selectedTaskLabel}
                </button>
              )}
              {showHistory && (
                <button
                  onClick={() => setActiveSidebarTab('history')}
                  className={[
                    'inline-flex items-center gap-1 px-3 py-2 text-xs font-medium transition-colors border-b-2 whitespace-nowrap',
                    activeSidebarTab === 'history'
                      ? 'border-primary text-primary'
                      : 'border-transparent text-muted-foreground hover:text-foreground',
                  ].join(' ')}
                >
                  {i18n('Run History')}
                </button>
              )}
              <div className="flex-1" />
              <button
                onClick={() => {
                  if (activeSidebarTab === 'detail') {
                    setSelectedTaskId(null);
                  } else {
                    setShowHistory(false);
                  }
                }}
                className="p-1.5 rounded hover:bg-muted mr-1 shrink-0"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>

            {/* Tab content */}
            {activeSidebarTab === 'detail' && selectedTask && (
              <NodeDetailPanel
                runId={activeRunDetail?.id}
                taskId={selectedTask.id}
                task={selectedTask}
                presetTask={selectedPresetTask}
                agentMap={agentMap}
                onClose={() => setSelectedTaskId(null)}
                hideHeader
              />
            )}
            {activeSidebarTab === 'history' && showHistory && (
              <RunHistoryPanel
                runs={presetRuns}
                onSelectRun={handleSelectHistoryRun}
                onClose={() => setShowHistory(false)}
                onDeleteRun={(run) => deleteRun(run.id)}
                hideHeader
              />
            )}
          </div>
        )}
      </div>

      {/* Variable input dialog */}
      {showVarDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-background border border-border rounded-lg shadow-lg w-[400px] p-4">
            <h3 className="font-medium text-sm mb-4">{i18n('Variables')}</h3>
            {/* Model selection */}
            {modelConfigs.length > 0 && (
              <div className="mb-3">
                <label className="text-xs text-muted-foreground block mb-1">
                  {i18n('Model Configuration')}
                </label>
                <select
                  value={selectedModelConfigId}
                  onChange={(e) => {
                    setSelectedModelConfigId(e.target.value);
                    storageService.saveSwarmPresetModel(preset.name, e.target.value);
                  }}
                  className="w-full h-8 px-2 text-sm rounded-md border border-input bg-transparent"
                >
                  <option value="">{i18n('Default')}</option>
                  {modelConfigs.map((mc) => (
                    <option key={mc.id} value={mc.id}>
                      {mc.name}{mc.groupId && groupNames[mc.groupId] ? ` (${groupNames[mc.groupId]})` : ''}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="space-y-3">
              {runtimeVariables.map((v) => (
                <div key={v.name}>
                  <label className="text-xs text-muted-foreground block mb-1">
                    {v.name}{v.required ? <span className="text-red-500 ml-0.5">*</span> : ''}{v.description ? ` — ${v.description}` : ''}
                  </label>
                  <input
                    type="text"
                    value={variableValues[v.name] || ''}
                    onChange={(e) => {
                      setVariableValues({ ...variableValues, [v.name]: e.target.value });
                      if (validationErrors[v.name]) {
                        setValidationErrors({ ...validationErrors, [v.name]: false });
                      }
                    }}
                    className={`w-full h-8 px-2 text-sm rounded-md border bg-transparent ${
                      validationErrors[v.name] ? 'border-red-500' : 'border-input'
                    }`}
                  />
                  {validationErrors[v.name] && (
                    <p className="text-xs text-red-500 mt-0.5">{i18n('This field is required')}</p>
                  )}
                </div>
              ))}
            </div>
            <div className="flex justify-end gap-2 mt-4">
              <button
                onClick={() => setShowVarDialog(false)}
                className="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-muted"
              >
                {i18n('Cancel')}
              </button>
              <button
                onClick={handleStartDryRun}
                title={i18n('Execute without saving to DB or Run History')}
                className="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-muted"
              >
                {i18n('Dry Run')}
              </button>
              <button
                onClick={handleStartRun}
                className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90"
              >
                {i18n('Start Run')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
