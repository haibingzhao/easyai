import React, { useState, useEffect, useRef, useMemo } from 'react';
import { X, Trash2, Check, Wand2 } from 'lucide-react';
import type { SwarmTaskDto, SwarmAgentSpecDto, SwarmVariableDto, DeliberationSpecDto, TeamSpecDto } from '@/services/swarm-service';
import type { AgentDto, AgentEnv, TemplateValidationError } from '@/types/agent';
import { JinjaTemplateEditor, type JinjaTemplateEditorHandle } from '@/components/agent/JinjaTemplateEditor';
import { ToolTooltip } from '@/components/agent/ToolItem';
import { type VariableGroup } from '@/components/agent/VariableDropdown';
import { agentService } from '@/services/agent-service';
import { i18n } from '@/utils/i18n';
import { safeParseInt } from '@/utils/format';
import { TeamConfigPanel } from './TeamConfigPanel';
import { SWARM_PROMPT_VARIABLES } from '@/constants/swarm-variables';

interface TaskDetailPanelProps {
  task: SwarmTaskDto;
  allTasks: SwarmTaskDto[];
  agents: SwarmAgentSpecDto[];
  variables: SwarmVariableDto[];
  agentContextMap?: Record<string, AgentEnv>;
  availableAgents?: AgentDto[];
  onUpdate: (task: SwarmTaskDto) => void;
  onDelete: (taskId: string) => void;
  onClose: () => void;
}

const DEFAULT_DELIBERATION: DeliberationSpecDto = {
  participants: [],
  judge: '',
  maxRounds: 3,
  order: 'SEQUENTIAL',
  contextTemplate: '',
};

const DEFAULT_TEAM: TeamSpecDto = {
  leader: '',
  members: [],
  maxIterations: 5,
  maxDynamicTasks: 10,
  roundTimeoutSeconds: 600,
  memberTimeoutSeconds: 0,
  contextTemplate: '',
};

/** Normalize deliberation order to uppercase (backend may return lowercase). */
function normalizeOrder(order?: string): DeliberationSpecDto['order'] {
  const upper = order?.toUpperCase();
  if (upper === 'SEQUENTIAL' || upper === 'ROUND_ROBIN') return upper;
  return 'SEQUENTIAL';
}

export const TaskDetailPanel: React.FC<TaskDetailPanelProps> = ({
  task,
  allTasks,
  agents,
  variables,
  agentContextMap,
  availableAgents,
  onUpdate,
  onDelete,
  onClose,
}) => {
  const [localTask, setLocalTask] = useState<SwarmTaskDto>({ ...task });
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const [inputFromKey, setInputFromKey] = useState('');
  const [inputFromValue, setInputFromValue] = useState('');
  // Custom prompt toggle: ON = user wants explicit prompt; OFF = use agent's default
  const [customPromptEnabled, setCustomPromptEnabled] = useState(!!task.promptTemplate);
  // Agent prompt toggle: ON = use Agent's SystemPrompt; OFF = disable it
  const [agentPromptEnabled, setAgentPromptEnabled] = useState(task.agentPromptEnabled !== false);
  // Additional system prompt toggle: ON = show task-level system prompt editor
  const [customSystemPromptEnabled, setCustomSystemPromptEnabled] = useState(!!task.systemPromptTemplate);
  // Template validation state (shared across all editors in this panel)
  const [templateValidating, setTemplateValidating] = useState(false);
  const [templateValidationErrors, setTemplateValidationErrors] = useState<TemplateValidationError[] | undefined>(undefined);
  const [templateValidationPassed, setTemplateValidationPassed] = useState(false);

  const handleValidateTemplate = async (template: string) => {
    setTemplateValidating(true);
    setTemplateValidationErrors(undefined);
    setTemplateValidationPassed(false);
    try {
      const result = await agentService.validateTemplate(template);
      if (result.valid) {
        setTemplateValidationErrors([]);
        setTemplateValidationPassed(true);
        setTimeout(() => setTemplateValidationPassed(false), 3000);
      } else {
        setTemplateValidationErrors(result.errors ?? []);
        setTemplateValidationPassed(false);
      }
    } catch (err) {
      setTemplateValidationErrors([{
        message: err instanceof Error ? err.message : 'Validation request failed',
      }]);
    } finally {
      setTemplateValidating(false);
    }
  };

  const promptEditorRef = useRef<JinjaTemplateEditorHandle>(null);

  // Sync when task selection changes
  useEffect(() => {
    setLocalTask({
      ...task,
      dependsOn: [...(task.dependsOn ?? [])],
      inputFrom: { ...(task.inputFrom ?? {}) },
      updatableVariables: [...(task.updatableVariables ?? [])],
      deliberation: task.deliberation ? {
        ...task.deliberation,
        participants: [...task.deliberation.participants],
        order: normalizeOrder(task.deliberation.order),
      } : undefined,
      team: task.team ? {
        ...task.team,
        members: [...task.team.members],
      } : undefined,
    });
    setDeleteConfirm(false);
    setInputFromKey('');
    setInputFromValue('');
    setCustomPromptEnabled(!!task.promptTemplate);
    setAgentPromptEnabled(task.agentPromptEnabled !== false);
    setCustomSystemPromptEnabled(!!task.systemPromptTemplate);
  }, [task.id]);

  // Build agentPromptMap: spec ID → agent's promptTemplate from the agent store
  const agentPromptMap: Record<string, string> = useMemo(() => {
    if (!availableAgents || availableAgents.length === 0) return {};
    const map: Record<string, string> = {};
    for (const spec of agents) {
      const agentDto = availableAgents.find((a) => a.id === spec.agentDefinitionId);
      if (agentDto?.promptTemplate) {
        map[spec.id] = agentDto.promptTemplate;
      }
    }
    return map;
  }, [availableAgents, agents]);

  const update = (changes: Partial<SwarmTaskDto>) => {
    const updated = { ...localTask, ...changes };
    setLocalTask(updated);
    onUpdate(updated);
  };

  // InputFrom keys defined on this task (usable as template variables)
  const inputFromKeys = Object.keys(localTask.inputFrom ?? {});

  // Deliberation built-in variable descriptions
  const DELIBERATION_BUILTIN_VARS: { name: string; description: string }[] = [
    { name: 'user_input', description: i18n('User input variable from workflow.') },
  ];

  /** Categorized variable groups for the dropdown picker. */
  const allVariableGroups: VariableGroup[] = useMemo(() => {
    const groups: VariableGroup[] = [
      {
        label: i18n('Agent Context'),
        vars: SWARM_PROMPT_VARIABLES,
        dotColor: 'bg-primary',
      },
      {
        label: i18n('Deliberation'),
        vars: localTask.type === 'DELIBERATION' ? DELIBERATION_BUILTIN_VARS : [],
        dotColor: 'bg-purple-400',
      },
      {
        label: i18n('Workflow Variables'),
        vars: variables.map((v) => ({ name: v.name, description: v.description || i18n('Workflow variable') })),
        dotColor: 'bg-emerald-400',
      },
      {
        label: i18n('Input From'),
        vars: inputFromKeys.map((k) => ({
          name: k,
          description: i18n('Upstream task result from') + ` "${localTask.inputFrom?.[k]}"`,
        })),
        dotColor: 'bg-amber-400',
      },
    ];

    // Parse selected agent's inputSchema into input.xxx variables
    if (localTask.agentId && availableAgents && agents) {
      const spec = agents.find((a) => a.id === localTask.agentId);
      const agentDto = spec ? availableAgents.find((a) => a.id === spec.agentDefinitionId) : undefined;
      const schema = agentDto?.inputSchema;
      if (schema) {
        try {
          const parsed = JSON.parse(schema);
          const props = parsed?.properties;
          if (props && typeof props === 'object') {
            const inputVars = Object.entries(props).map(([key, val]) => ({
              name: `input.${key}`,
              description: (val as Record<string, unknown>)?.description as string
                || `Input field: ${key}`,
            }));
            if (inputVars.length > 0) {
              groups.push({
                label: i18n('Input Schema'),
                vars: inputVars,
                dotColor: 'bg-cyan-400',
              });
            }
          }
        } catch {
          // Invalid JSON — silently skip
        }
      }
    }

    return groups;
  }, [localTask.type, localTask.agentId, variables, inputFromKeys, localTask.inputFrom, availableAgents, agents]);

  /**
   * Variable groups for systemPromptTemplate editor.
   * systemPromptTemplate is pre-rendered via preRenderTemplate() with ONLY userVars + inputFromVars,
   * so Agent Context / Deliberation / Input Schema variables are NOT available.
   */
  const systemPromptVariableGroups: VariableGroup[] = useMemo(() => [
    {
      label: i18n('Workflow Variables'),
      vars: variables.map((v) => ({ name: v.name, description: v.description || i18n('Workflow variable') })),
      dotColor: 'bg-emerald-400',
    },
    {
      label: i18n('Input From'),
      vars: inputFromKeys.map((k) => ({
        name: k,
        description: i18n('Upstream task result from') + ` "${localTask.inputFrom?.[k]}"`,
      })),
      dotColor: 'bg-amber-400',
    },
  ], [variables, inputFromKeys, localTask.inputFrom]);

  // Toggle dependsOn
  const toggleDependsOn = (taskId: string) => {
    const deps = localTask.dependsOn ?? [];
    if (deps.includes(taskId)) {
      update({ dependsOn: deps.filter((d) => d !== taskId) });
    } else {
      update({ dependsOn: [...deps, taskId] });
    }
  };

  // Toggle deliberation participant
  const toggleParticipant = (agentId: string) => {
    const delib = localTask.deliberation ?? { ...DEFAULT_DELIBERATION };
    const parts = delib.participants;
    if (parts.includes(agentId)) {
      update({ deliberation: { ...delib, participants: parts.filter((p) => p !== agentId) } });
    } else {
      update({ deliberation: { ...delib, participants: [...parts, agentId] } });
    }
  };

  // InputFrom
  const addInputFrom = () => {
    if (!inputFromKey.trim() || !inputFromValue) return;
    update({
      inputFrom: { ...(localTask.inputFrom ?? {}), [inputFromKey.trim()]: inputFromValue },
    });
    setInputFromKey('');
    setInputFromValue('');
  };

  const removeInputFrom = (key: string) => {
    const updated = { ...(localTask.inputFrom ?? {}) };
    delete updated[key];
    update({ inputFrom: updated });
  };

  // Compute all ancestor task IDs (direct dependsOn + their transitive ancestors)
  const ancestorTaskIds = useMemo(() => {
    const taskMap = new Map(allTasks.map((t) => [t.id, t]));
    const ancestors = new Set<string>();
    const collect = (taskId: string) => {
      if (ancestors.has(taskId)) return;
      ancestors.add(taskId);
      const t = taskMap.get(taskId);
      for (const dep of t?.dependsOn ?? []) {
        collect(dep);
      }
    };
    for (const dep of localTask.dependsOn ?? []) {
      collect(dep);
    }
    return ancestors;
  }, [allTasks, localTask.dependsOn]);

  // Only ancestor tasks are valid Input From sources
  const ancestorTasks = allTasks.filter((t) => ancestorTaskIds.has(t.id));

  // All tasks except self (for Depends On checkboxes)
  const nonSelfTasks = allTasks.filter((t) => t.id !== localTask.id);

  // Auto-clean inputFrom entries that reference non-ancestor tasks
  useEffect(() => {
    const current = localTask.inputFrom ?? {};
    const stale = Object.entries(current).filter(([, taskId]) => !ancestorTaskIds.has(taskId));
    if (stale.length > 0) {
      const cleaned = { ...current };
      for (const [key] of stale) {
        delete cleaned[key];
      }
      update({ inputFrom: cleaned });
    }
  }, [localTask.dependsOn]);

  // Determine if prompt can be customized based on selected agent's agentContext
  const agentContext = localTask.agentId ? agentContextMap?.[localTask.agentId] : undefined;
  const canCustomizePrompt = agentContext !== 'CHAT'; // SWARM, BOTH, or undefined all allow editing

  return (
    <div className="w-[400px] border-l border-border flex flex-col shrink-0 bg-card">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-border shrink-0">
        <h4 className="text-sm font-medium truncate flex-1">{localTask.id || i18n('New Task')}</h4>
        <button onClick={onClose} className="p-1 rounded hover:bg-muted">
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* Scrollable form */}
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {/* ID + Type */}
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="block text-xs font-medium mb-1">{i18n('Task ID')} *</label>
            <input
              className={`w-full px-2.5 py-1.5 text-sm rounded-md border bg-background focus:outline-none focus:ring-2 focus:ring-primary ${
                !localTask.id.trim() ? 'border-destructive ring-1 ring-destructive/50' : 'border-border'
              }`}
              value={localTask.id}
              onChange={(e) => update({ id: e.target.value })}
              onBlur={() => { if (!localTask.id.trim()) setLocalTask(prev => ({ ...prev, id: task.id })); }}
              placeholder="analyze-code"
            />
          </div>
          <div>
            <label className="block text-xs font-medium mb-1">{i18n('Type')} *</label>
            <select
              className="w-full px-2.5 py-1.5 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
              value={localTask.type}
              onChange={(e) => {
                const newType = e.target.value as SwarmTaskDto['type'];
                if (newType === 'SINGLE') {
                  update({ type: 'SINGLE', deliberation: undefined, team: undefined });
                } else if (newType === 'DELIBERATION') {
                  const delibBase = localTask.deliberation ?? { ...DEFAULT_DELIBERATION };
                  update({ type: 'DELIBERATION', agentId: '', promptTemplate: '', team: undefined, deliberation: { ...delibBase, order: normalizeOrder(delibBase.order) } });
                } else {
                  update({ type: 'TEAM', agentId: '', promptTemplate: '', deliberation: undefined, team: localTask.team ?? { ...DEFAULT_TEAM } });
                }
              }}
            >
              <option value="SINGLE">{i18n('Single')}</option>
              <option value="DELIBERATION">{i18n('Deliberation')}</option>
              <option value="TEAM">{i18n('Team')}</option>
            </select>
          </div>
        </div>

        {/* SINGLE: Agent selector */}
        {localTask.type === 'SINGLE' && (
          <div>
            <label className="block text-xs font-medium mb-1">{i18n('Agent')} *</label>
            <select
              className="w-full px-2.5 py-1.5 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
              value={localTask.agentId ?? ''}
              onChange={(e) => {
                const newAgentId = e.target.value;
                const newAgentContext = newAgentId ? agentContextMap?.[newAgentId] : undefined;
                // Clear promptTemplate when switching to a CHAT agent
                const shouldClearPrompt = newAgentContext === 'CHAT';
                update({
                  agentId: newAgentId,
                  ...(shouldClearPrompt ? { promptTemplate: '' } : {}),
                });
              }}
            >
              <option value="">{i18n('Select an agent...')}</option>
              {agents.map((a) => {
                const ctx = agentContextMap?.[a.id];
                const label = ctx === 'CHAT' ? `${a.id} (${a.role}) [Chat]` : `${a.id} (${a.role})`;
                return (
                  <option key={a.id} value={a.id}>{label}</option>
                );
              })}
            </select>
          </div>
        )}

        {/* Max Retries */}
        <div className="w-1/2">
          <label className="block text-xs font-medium mb-1">{i18n('Max Retries')}</label>
          <input
            type="number"
            min={0}
            className="w-full px-2.5 py-1.5 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
            value={localTask.maxRetries ?? 2}
            onChange={(e) => update({ maxRetries: safeParseInt(e.target.value, 0) })}
          />
        </div>

        {/* Depends On */}
        {nonSelfTasks.length > 0 && (
          <div>
            <label className="block text-xs font-medium mb-1">{i18n('Depends On')}</label>
            <div className="flex flex-wrap gap-1.5">
              {nonSelfTasks.map((t) => (
                <label
                  key={t.id}
                  className="flex items-center gap-1.5 px-2 py-1 rounded-md border border-border text-xs cursor-pointer hover:bg-muted"
                >
                  <input
                    type="checkbox"
                    checked={(localTask.dependsOn ?? []).includes(t.id)}
                    onChange={() => toggleDependsOn(t.id)}
                    className="rounded"
                  />
                  <span>{t.id}</span>
                </label>
              ))}
            </div>
          </div>
        )}

        {/* Input From — only shown when node has predecessor dependencies */}
        {ancestorTasks.length > 0 && (
          <div>
            <label className="block text-xs font-medium mb-1">{i18n('Input From')}</label>
            <div className="space-y-1.5">
              {Object.entries(localTask.inputFrom ?? {}).map(([key, val]) => (
                <div key={key} className="flex items-center gap-2 text-xs">
                  <code className="px-1.5 py-0.5 rounded bg-muted text-primary">{`{{ ${key} }}`}</code>
                  <span className="text-muted-foreground">←</span>
                  <span className="text-foreground">{val}</span>
                  <button type="button" onClick={() => removeInputFrom(key)} className="p-0.5 text-muted-foreground hover:text-destructive">
                    <X className="w-3 h-3" />
                  </button>
                </div>
              ))}
              <div className="flex items-center gap-1.5">
                <input
                  className="px-2 py-1 text-xs rounded border border-border bg-background w-28 focus:outline-none focus:ring-1 focus:ring-primary"
                  value={inputFromKey}
                  onChange={(e) => setInputFromKey(e.target.value)}
                  placeholder="var name"
                />
                <span className="text-muted-foreground text-xs">←</span>
                <select
                  className="px-2 py-1 text-xs rounded border border-border bg-background focus:outline-none focus:ring-1 focus:ring-primary flex-1 min-w-0"
                  value={inputFromValue}
                  onChange={(e) => setInputFromValue(e.target.value)}
                >
                  <option value="">{i18n('Select task...')}</option>
                  {ancestorTasks.map((t) => (
                    <option key={t.id} value={t.id}>{t.id}</option>
                  ))}
                </select>
                <button
                  type="button"
                  onClick={addInputFrom}
                  disabled={!inputFromKey.trim() || !inputFromValue}
                  className="p-1 rounded hover:bg-muted text-muted-foreground disabled:opacity-30"
                >
                  <Check className="w-3 h-3" />
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Updatable Variables — shown when updatable variables exist in the preset */}
        {variables.filter(v => v.updatable).length > 0 && (
          <div>
            <label className="block text-xs font-medium mb-1">{i18n('Updatable Variables')}</label>
            <p className="text-[10px] text-muted-foreground mb-1.5">
              {i18n('Variables this task\'s agent can update at runtime')}
            </p>
            <div className="flex flex-wrap gap-1.5">
              {variables.filter(v => v.updatable).map((v) => (
                <label
                  key={v.name}
                  className="flex items-center gap-1.5 px-2 py-1 rounded-md border border-border text-xs cursor-pointer hover:bg-muted"
                >
                  <input
                    type="checkbox"
                    checked={(localTask.updatableVariables ?? []).includes(v.name)}
                    onChange={() => {
                      const current = localTask.updatableVariables ?? [];
                      const updated = current.includes(v.name)
                        ? current.filter(n => n !== v.name)
                        : [...current, v.name];
                      update({ updatableVariables: updated });
                    }}
                    className="rounded"
                  />
                  <code className="text-primary">{v.name}</code>
                </label>
              ))}
            </div>
          </div>
        )}

        {/* System Prompt Controls — only for SINGLE tasks */}
        {localTask.type === 'SINGLE' && (
        <div className="space-y-3">
          {/* Switch A: Use Agent System Prompt */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="block text-xs font-medium">{i18n('Use Agent System Prompt')}</label>
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] text-muted-foreground">
                  {agentPromptEnabled ? i18n('Enabled') : i18n('Disabled')}
                </span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={agentPromptEnabled}
                  onClick={() => {
                    const next = !agentPromptEnabled;
                    setAgentPromptEnabled(next);
                    update({ agentPromptEnabled: next });
                  }}
                  className={[
                    'relative inline-flex h-4 w-7 shrink-0 cursor-pointer rounded-full transition-colors',
                    agentPromptEnabled ? 'bg-green-500' : 'bg-muted-foreground/30',
                  ].join(' ')}
                >
                  <span
                    className={[
                      'pointer-events-none inline-block h-3 w-3 rounded-full bg-white shadow-sm transition-transform mt-0.5',
                      agentPromptEnabled ? 'translate-x-3.5' : 'translate-x-0.5',
                    ].join(' ')}
                  />
                </button>
              </div>
            </div>
            <p className="text-[10px] text-muted-foreground/70 italic">
              {agentPromptEnabled
                ? i18n("Using the agent's configured System Prompt.")
                : i18n("Agent's System Prompt is disabled. No system prompt will be used.")}
            </p>
          </div>

          {/* Switch B: Additional System Prompt */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="block text-xs font-medium">{i18n('Additional System Prompt')}</label>
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] text-muted-foreground">
                  {customSystemPromptEnabled ? i18n('Custom') : i18n('Default')}
                </span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={customSystemPromptEnabled}
                  onClick={() => {
                    const next = !customSystemPromptEnabled;
                    setCustomSystemPromptEnabled(next);
                    if (!next) {
                      update({ systemPromptTemplate: '' });
                    }
                  }}
                  className={[
                    'relative inline-flex h-4 w-7 shrink-0 cursor-pointer rounded-full transition-colors',
                    customSystemPromptEnabled ? 'bg-green-500' : 'bg-muted-foreground/30',
                  ].join(' ')}
                >
                  <span
                    className={[
                      'pointer-events-none inline-block h-3 w-3 rounded-full bg-white shadow-sm transition-transform mt-0.5',
                      customSystemPromptEnabled ? 'translate-x-3.5' : 'translate-x-0.5',
                    ].join(' ')}
                  />
                </button>
              </div>
            </div>
            {customSystemPromptEnabled && (
              <>
                <p className="text-[10px] text-muted-foreground/70 italic mb-1">
                  {agentPromptEnabled
                    ? i18n("This prompt is appended after the agent's System Prompt.")
                    : i18n("This prompt is used as the sole System Prompt (agent's is disabled).")}
                </p>
                <JinjaTemplateEditor
                  value={localTask.systemPromptTemplate ?? ''}
                  onChange={(v) => update({ systemPromptTemplate: v })}
                  placeholder="Additional system instructions for this task. Define role, tone, and rules."
                  rows={4}
                  variableGroups={systemPromptVariableGroups}
                  onValidate={() => handleValidateTemplate(localTask.systemPromptTemplate ?? '')}
                  validating={templateValidating}
                  validationErrors={templateValidationErrors}
                  validationPassed={templateValidationPassed}
                />
              </>
            )}
            {!customSystemPromptEnabled && (
              <p className="text-[10px] text-muted-foreground/70 italic">
                {i18n('Toggle to add task-level system instructions.')}
              </p>
            )}
          </div>
        </div>
        )}

        {/* Task Result Report — only for SINGLE tasks */}
        {localTask.type === 'SINGLE' && (
        <div>
          <div className="flex items-center justify-between mb-1">
            <label className="block text-xs font-medium">{i18n('Task Result Report')}</label>
            <div className="flex items-center gap-1.5">
              <span className="text-[10px] text-muted-foreground">
                {localTask.reportEnabled ? i18n('Enabled') : i18n('Disabled')}
              </span>
              <button
                type="button"
                role="switch"
                aria-checked={!!localTask.reportEnabled}
                onClick={() => {
                  const next = !localTask.reportEnabled;
                  update({ reportEnabled: next });
                }}
                className={[
                  'relative inline-flex h-4 w-7 shrink-0 cursor-pointer rounded-full transition-colors',
                  localTask.reportEnabled ? 'bg-green-500' : 'bg-muted-foreground/30',
                ].join(' ')}
              >
                <span
                  className={[
                    'pointer-events-none inline-block h-3 w-3 rounded-full bg-white shadow-sm transition-transform mt-0.5',
                    localTask.reportEnabled ? 'translate-x-3.5' : 'translate-x-0.5',
                  ].join(' ')}
                />
              </button>
            </div>
          </div>
          <p className="text-[10px] text-muted-foreground/70 italic">
            {localTask.reportEnabled
              ? i18n('Agent must explicitly report task success or failure before finishing.')
              : i18n('Toggle to require the agent to report task completion status.')}
          </p>
        </div>
        )}

        {/* Prompt Template — only for SINGLE tasks (TEAM uses contextTemplate, DELIBERATION uses its own prompts) */}
        {localTask.type === 'SINGLE' && (
        <div>
          <div className="flex items-center justify-between mb-1">
            <label className="block text-xs font-medium">{i18n('Prompt Template')}</label>
            {canCustomizePrompt && (
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] text-muted-foreground">
                  {customPromptEnabled ? i18n('Custom') : i18n('Default')}
                </span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={customPromptEnabled}
                  onClick={() => {
                    const next = !customPromptEnabled;
                    setCustomPromptEnabled(next);
                    if (!next) {
                      // Turning off: clear prompt (use agent's default)
                      update({ promptTemplate: '' });
                    }
                  }}
                  className={[
                    'relative inline-flex h-4 w-7 shrink-0 cursor-pointer rounded-full transition-colors',
                    customPromptEnabled ? 'bg-green-500' : 'bg-muted-foreground/30',
                  ].join(' ')}
                >
                  <span
                    className={[
                      'pointer-events-none inline-block h-3 w-3 rounded-full bg-white shadow-sm transition-transform mt-0.5',
                      customPromptEnabled ? 'translate-x-3.5' : 'translate-x-0.5',
                    ].join(' ')}
                  />
                </button>
              </div>
            )}
          </div>

          <p className="text-[10px] text-muted-foreground/70 italic mb-1">
            {i18n('Sent as User Message.')}
          </p>

          {!canCustomizePrompt && (
            <p className="text-xs text-muted-foreground mb-1.5 text-amber-400/80">
              {i18n('This agent is designed for Chat. Its built-in system prompt will be used as-is.')}
            </p>
          )}

          {customPromptEnabled && canCustomizePrompt && (
            <>
              {localTask.agentId && (
                <div className="flex justify-end mb-1">
                  <ToolTooltip
                    name={i18n('Fill from Agent')}
                    description={
                      agentPromptMap[localTask.agentId]
                        ? i18n("Copy the agent's prompt template into this field for quick initialization.")
                        : i18n('This agent has no custom prompt template configured.')
                    }
                  >
                    <button
                      type="button"
                      onClick={() => {
                        const agentPrompt = agentPromptMap[localTask.agentId!];
                        if (agentPrompt) {
                          update({ promptTemplate: agentPrompt });
                        }
                      }}
                      className={[
                        'inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded font-medium cursor-pointer transition-colors',
                        agentPromptMap[localTask.agentId!]
                          ? 'bg-amber-500/10 text-amber-500 hover:bg-amber-500/20'
                          : 'bg-muted text-muted-foreground/50 cursor-not-allowed',
                      ].join(' ')}
                      title={
                        agentPromptMap[localTask.agentId!]
                          ? i18n("Copy agent's prompt template")
                          : i18n('Agent has no custom prompt template')
                      }
                    >
                      <Wand2 className="w-2.5 h-2.5" />
                      {i18n('Fill from Agent')}
                    </button>
                  </ToolTooltip>
                </div>
              )}
              <JinjaTemplateEditor
                ref={promptEditorRef}
                value={localTask.promptTemplate}
                onChange={(v) => {
                  update({ promptTemplate: v });
                  setTemplateValidationErrors(undefined);
                  setTemplateValidationPassed(false);
                }}
                placeholder="Analyze the following code: {{ code_diff }}"
                rows={5}
                variableGroups={allVariableGroups}
                onValidate={() => handleValidateTemplate(localTask.promptTemplate)}
                validating={templateValidating}
                validationErrors={templateValidationErrors}
                validationPassed={templateValidationPassed}
              />
            </>
          )}

          {!customPromptEnabled && canCustomizePrompt && (
            <p className="text-xs text-muted-foreground/70 italic">
              {i18n("Using agent's default prompt. Toggle the switch to customize.")}
            </p>
          )}

          {/* Validation: at least one prompt source must be enabled */}
          {!agentPromptEnabled && !customSystemPromptEnabled && !customPromptEnabled && (
            <p className="text-xs text-destructive mt-2 font-medium">
              {i18n('At least one prompt source must be enabled. Enable Agent System Prompt, Additional System Prompt, or Prompt Template.')}
            </p>
          )}
        </div>
        )}

        {/* DELIBERATION section */}
        {localTask.type === 'DELIBERATION' && renderDeliberationForm()}

        {/* TEAM section */}
        {localTask.type === 'TEAM' && (
          <TeamConfigPanel
            team={{ ...DEFAULT_TEAM, ...localTask.team }}
            agents={agents}
            variables={variables}
            inputFrom={localTask.inputFrom ?? {}}
            onUpdate={(teamUpdate) => update({ team: { ...DEFAULT_TEAM, ...localTask.team, ...teamUpdate } })}
          />
        )}
      </div>

      {/* Footer actions */}
      <div className="px-4 py-3 border-t border-border shrink-0">
        {deleteConfirm ? (
          <div className="flex items-center gap-2">
            <span className="text-xs text-destructive flex-1">
              {i18n('Delete this task?')}
            </span>
            <button
              type="button"
              onClick={() => onDelete(localTask.id)}
              className="px-3 py-1.5 text-xs rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {i18n('Confirm Delete')}
            </button>
            <button
              type="button"
              onClick={() => setDeleteConfirm(false)}
              className="px-3 py-1.5 text-xs rounded-md bg-muted hover:bg-muted/80"
            >
              {i18n('Cancel')}
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setDeleteConfirm(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md text-destructive hover:bg-destructive/10 transition-colors"
          >
            <Trash2 className="w-3.5 h-3.5" />
            {i18n('Delete Task')}
          </button>
        )}
      </div>
    </div>
  );

  function renderDeliberationForm() {
    const delib = localTask.deliberation ?? { ...DEFAULT_DELIBERATION };
    return (
      <div className="space-y-3 p-3 rounded-lg border border-purple-500/30 bg-purple-500/5">
        <h3 className="text-xs font-medium text-purple-400">{i18n('Deliberation Configuration')}</h3>

        {/* Context Template */}
        <div>
          <label className="block text-xs font-medium mb-1">{i18n('Context Template')}</label>
          <p className="text-[10px] text-muted-foreground mb-1.5">
            {i18n('The deliberation topic/context. The Judge orchestrator uses this to generate prompts for participants. Supports Jinja2 variables.')}
          </p>
          <JinjaTemplateEditor
            value={delib.contextTemplate ?? ''}
            onChange={(v) => update({ deliberation: { ...delib, contextTemplate: v } })}
            placeholder="Discuss the following proposal: {{ user_input }}"
            rows={3}
            variableGroups={allVariableGroups}
            onValidate={() => handleValidateTemplate(delib.contextTemplate ?? '')}
            validating={templateValidating}
            validationErrors={templateValidationErrors}
            validationPassed={templateValidationPassed}
          />
        </div>

        {/* Participants */}
        <div>
          <label className="block text-xs font-medium mb-1">{i18n('Participants')} * ({delib.participants.length})</label>
          <p className="text-[10px] text-muted-foreground mb-1.5">
            {i18n('Agents that participate in the deliberation. Each uses their own System Prompt.')}
          </p>
          <div className="flex flex-wrap gap-1.5">
            {agents.map((a) => (
              <label
                key={a.id}
                className="flex items-center gap-1.5 px-2 py-1 rounded-md border border-border text-xs cursor-pointer hover:bg-muted"
              >
                <input
                  type="checkbox"
                  checked={delib.participants.includes(a.id)}
                  onChange={() => toggleParticipant(a.id)}
                  className="rounded"
                />
                <span>{a.id}</span>
                <span className="text-[10px] text-muted-foreground">({a.role})</span>
              </label>
            ))}
          </div>
        </div>

        {/* Judge + MaxRounds + Order */}
        <div className="grid grid-cols-3 gap-3">
          <div>
            <label className="block text-xs font-medium mb-1">{i18n('Judge')} *</label>
            <select
              className="w-full px-2 py-1.5 text-xs rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
              value={delib.judge}
              onChange={(e) => update({ deliberation: { ...delib, judge: e.target.value } })}
            >
              <option value="">{i18n('Select...')}</option>
              {agents.map((a) => (
                <option key={a.id} value={a.id}>{a.id}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium mb-1">{i18n('Max Rounds')}</label>
            <input
              type="number"
              min={1}
              className="w-full px-2 py-1.5 text-xs rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
              value={delib.maxRounds ?? 3}
              onChange={(e) => update({ deliberation: { ...delib, maxRounds: safeParseInt(e.target.value, 3) } })}
            />
          </div>
          <div>
            <label className="block text-xs font-medium mb-1">{i18n('Order')}</label>
            <div className="flex rounded-md border border-border overflow-hidden">
              <button
                type="button"
                onClick={() => update({ deliberation: { ...delib, order: 'SEQUENTIAL' } })}
                className={`flex-1 py-1.5 text-[10px] font-medium transition-colors ${delib.order === 'SEQUENTIAL' ? 'bg-primary text-primary-foreground' : 'bg-background text-muted-foreground hover:bg-muted'}`}
              >
                SEQ
              </button>
              <button
                type="button"
                onClick={() => update({ deliberation: { ...delib, order: 'ROUND_ROBIN' } })}
                className={`flex-1 py-1.5 text-[10px] font-medium transition-colors ${delib.order === 'ROUND_ROBIN' ? 'bg-primary text-primary-foreground' : 'bg-background text-muted-foreground hover:bg-muted'}`}
              >
                RR
              </button>
            </div>
          </div>
        </div>

        {/* Info text */}
        <p className="text-[10px] text-muted-foreground/70 italic">
          {i18n('The Judge orchestrates the deliberation by dynamically generating prompts for participants. No manual prompt configuration needed.')}
        </p>
      </div>
    );
  }
};
