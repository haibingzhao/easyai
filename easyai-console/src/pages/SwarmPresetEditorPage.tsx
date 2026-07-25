import React, { useState, useCallback, useEffect, useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Save, Loader2, GitBranch, Sparkles, Users, ListChecks, Variable, Globe } from 'lucide-react';
import { AiConfigPanel } from '@/components/ai/AiConfigPanel';
import { SwarmAgentEditor } from '@/components/swarm/SwarmAgentEditor';
import { SwarmTaskEditor } from '@/components/swarm/SwarmTaskEditor';
import { SwarmVariableEditor } from '@/components/swarm/SwarmVariableEditor';
import { swarmService } from '@/services/swarm-service';
import type { PresetRequest, SwarmAgentSpecDto, SwarmTaskDto, SwarmVariableDto } from '@/services/swarm-service';
import { useSwarmStore } from '@/services/stores/swarm-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { agentService } from '@/services/agent-service';
import { modelConfigService } from '@/services/model-config-service';
import type { ModelProviderConfig } from '@/types/settings';
import type { ToolInfo } from '@/types/agent';
import { validateDag } from '@/utils/dag-validator';
import { SWARM_EXCLUDED_TOOLS } from '@/constants/tools';
import { aiConfigService } from '@/services/ai-config-service';
import { i18n } from '@/utils/i18n';
import { useResizable } from '@/hooks/useResizable';

type SectionId = 'basic' | 'agents' | 'tasks' | 'variables';

/**
 * Normalize an AI-generated task: fill in missing fields with safe defaults.
 * LLM output may omit optional-looking fields (e.g. team.contextTemplate),
 * which would crash downstream editors that assume them present.
 */
function normalizeAiTask(raw: Record<string, unknown>): SwarmTaskDto {
  const t = raw as unknown as SwarmTaskDto;
  const type = (['SINGLE', 'DELIBERATION', 'TEAM'].includes(t.type) ? t.type : 'SINGLE') as SwarmTaskDto['type'];
  const task: SwarmTaskDto = {
    ...t,
    id: t.id ?? '',
    type,
    promptTemplate: t.promptTemplate ?? '',
    dependsOn: t.dependsOn ?? [],
    inputFrom: t.inputFrom ?? {},
  };
  if (type === 'TEAM') {
    const team = (t.team ?? {}) as Record<string, unknown>;
    task.team = {
      leader: typeof team.leader === 'string' ? team.leader : '',
      members: Array.isArray(team.members) ? (team.members as string[]) : [],
      maxIterations: typeof team.maxIterations === 'number' ? team.maxIterations : 5,
      maxDynamicTasks: typeof team.maxDynamicTasks === 'number' ? team.maxDynamicTasks : 10,
      roundTimeoutSeconds: typeof team.roundTimeoutSeconds === 'number' ? team.roundTimeoutSeconds : 600,
      memberTimeoutSeconds: typeof team.memberTimeoutSeconds === 'number' ? team.memberTimeoutSeconds : 0,
      consultationTimeoutSeconds: typeof team.consultationTimeoutSeconds === 'number' ? team.consultationTimeoutSeconds : undefined,
      contextTemplate: typeof team.contextTemplate === 'string' ? team.contextTemplate : '',
    };
    task.deliberation = undefined;
  } else if (type === 'DELIBERATION') {
    const d = (t.deliberation ?? {}) as Record<string, unknown>;
    task.deliberation = {
      participants: Array.isArray(d.participants) ? (d.participants as string[]) : [],
      judge: typeof d.judge === 'string' ? d.judge : '',
      maxRounds: typeof d.maxRounds === 'number' ? d.maxRounds : 3,
      order: d.order === 'ROUND_ROBIN' ? 'ROUND_ROBIN' : 'SEQUENTIAL',
      contextTemplate: typeof d.contextTemplate === 'string' ? d.contextTemplate : '',
    };
    task.team = undefined;
  } else {
    task.team = undefined;
    task.deliberation = undefined;
  }
  return task;
}

interface NavSection {
  id: SectionId;
  label: string;
  icon: React.ReactNode;
}

export const SwarmPresetEditorPage: React.FC = () => {
  const navigate = useNavigate();
  const { name: editName } = useParams<{ name: string }>();
  const isEdit = !!editName;

  const { loadPresets } = useSwarmStore();
  const { agents: availableAgents, loadAgents } = useAgentStore();

  const [activeSection, setActiveSection] = useState<SectionId>('basic');
  const [name, setName] = useState('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [agents, setAgents] = useState<SwarmAgentSpecDto[]>([]);
  const [tasks, setTasks] = useState<SwarmTaskDto[]>([]);
  const [variables, setVariables] = useState<SwarmVariableDto[]>([
    { name: 'user_input', description: 'The user query or task description', required: true, defaultValue: null, updatable: false },
  ]);
  const [language, setLanguage] = useState('');
  const [aiPanelOpen, setAiPanelOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const errorRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to error banner when error changes so user always sees it
  useEffect(() => {
    if (error && errorRef.current) {
      errorRef.current.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [error]);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [navWidth, setNavWidth] = useState(192);
  const [navResizing, setNavResizing] = useState(false);
  const [availableModels, setAvailableModels] = useState<ModelProviderConfig[]>([]);
  const [availableTools, setAvailableTools] = useState<ToolInfo[]>([]);

  const navResizer = useResizable({
    minWidth: 120,
    maxWidth: 320,
    onResize: (w) => setNavWidth(Math.round(w)),
    direction: 'right',
    onResizeStart: () => setNavResizing(true),
    onResizeEnd: () => setNavResizing(false),
  });

  // Load dependencies
  useEffect(() => {
    loadAgents();
    modelConfigService.getUserConfigurations().then(setAvailableModels).catch(() => {/* ignore */});
    agentService.listTools().then(setAvailableTools).catch(() => {/* ignore */});
  }, []);

  // Load existing preset for edit mode
  useEffect(() => {
    if (isEdit && editName) {
      setLoadingDetail(true);
      swarmService.getPresetDetail(editName).then((detail) => {
        if (detail) {
          setName(detail.name);
          setTitle(detail.title);
          setDescription(detail.description);
          setAgents(detail.agents);
          setTasks(detail.tasks.map(t => ({
            ...t,
            // Ensure runtime fields are reset to defaults
            dependsOn: t.dependsOn ?? [],
            inputFrom: t.inputFrom ?? {},
          })));
          setVariables(detail.variables);
          setLanguage(detail.language || '');
        }
        setLoadingDetail(false);
      }).catch(() => {
        setError('Failed to load preset details');
        setLoadingDetail(false);
      });
    }
  }, [isEdit, editName]);

  const sections: NavSection[] = [
    { id: 'basic', label: i18n('Basic'), icon: <GitBranch className="w-4 h-4" /> },
    { id: 'agents', label: i18n('Agents'), icon: <Users className="w-4 h-4" /> },
    { id: 'tasks', label: i18n('Tasks'), icon: <ListChecks className="w-4 h-4" /> },
    { id: 'variables', label: i18n('Variables'), icon: <Variable className="w-4 h-4" /> },
  ];

  const handleAiApply = useCallback((config: Record<string, unknown>) => {
    const hasExisting = agents.length > 0 || tasks.length > 0;
    if (hasExisting) {
      const proceed = window.confirm(
        i18n('AI generated config will be applied to your current configuration. Continue?')
      );
      if (!proceed) return;
    }
    if (typeof config.name === 'string') setName(config.name);
    if (typeof config.title === 'string') setTitle(config.title);
    if (typeof config.description === 'string') setDescription(config.description);
    if (Array.isArray(config.agents)) {
      // Strip tools unsupported by the swarm runtime from inline agents
      setAgents((config.agents as SwarmAgentSpecDto[]).map(a =>
        a.toolNames && a.toolNames.length > 0
          ? { ...a, toolNames: a.toolNames.filter(t => !SWARM_EXCLUDED_TOOLS.includes(t)) }
          : a
      ));
    }
    if (Array.isArray(config.tasks)) setTasks((config.tasks as Record<string, unknown>[]).map(normalizeAiTask));
    if (Array.isArray(config.variables)) setVariables(config.variables as SwarmVariableDto[]);
  }, [agents.length, tasks.length]);

  const buildCurrentConfig = useCallback(() => ({
    name, title, description, agents, tasks, variables, language,
  }), [name, title, description, agents, tasks, variables, language]);

  // Compute task agent IDs for agent delete confirmation
  const taskAgentIds = new Set<string>(
    tasks.flatMap(t => {
      if (t.type === 'SINGLE' && t.agentId) return [t.agentId];
      if (t.type === 'TEAM' && t.team) return [t.team.leader, ...t.team.members].filter(Boolean);
      return [];
    })
  );

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!name.trim()) {
      setError('Name is required');
      setActiveSection('basic');
      return;
    }
    if (!title.trim()) {
      setError('Title is required');
      setActiveSection('basic');
      return;
    }
    if (agents.length === 0) {
      setError('At least one agent is required');
      setActiveSection('agents');
      return;
    }
    if (tasks.length === 0) {
      setError('At least one task is required');
      setActiveSection('tasks');
      return;
    }

    // Validate individual agents
    for (const agent of agents) {
      if (!agent.id.trim() || !agent.role.trim()) {
        setError(`Agent '${agent.id || '(unnamed)'}' has incomplete fields`);
        setActiveSection('agents');
        return;
      }
      if (!agent.agentDefinitionId && !agent.name?.trim()) {
        setError(`Agent '${agent.id}' needs either a global agent or a custom name`);
        setActiveSection('agents');
        return;
      }
    }

    // Validate individual tasks
    for (const task of tasks) {
      if (!task.id.trim()) {
        setError(`Task '${task.id || '(unnamed)'}' has incomplete fields`);
        setActiveSection('tasks');
        return;
      }
      if (task.type === 'SINGLE' && !task.agentId) {
        setError(`Task '${task.id}' requires an agent`);
        setActiveSection('tasks');
        return;
      }
      if (task.type === 'DELIBERATION') {
        const d = task.deliberation;
        if (!d || d.participants.length < 2 || !d.judge) {
          setError(`Task '${task.id}' has incomplete deliberation configuration`);
          setActiveSection('tasks');
          return;
        }
      }
      if (task.type === 'TEAM') {
        const t = task.team;
        if (!t || !t.leader || t.members.length === 0) {
          setError(`Task '${task.id}' has incomplete team configuration`);
          setActiveSection('tasks');
          return;
        }
      }
    }

    // Validate DAG
    const dagResult = validateDag(tasks);
    if (!dagResult.valid) {
      setError(dagResult.error);
      setActiveSection('tasks');
      return;
    }

    const request: PresetRequest = {
      name: name.trim(),
      title: title.trim(),
      description: description.trim(),
      agents,
      tasks,
      variables,
      language,
    };

    setSaving(true);
    try {
      // Backend validation (7-layer: JSON structure, fields, resource existence, templates, DAG)
      try {
        const validation = await aiConfigService.validateConfig('swarm', request as unknown as Record<string, unknown>);
        if (!validation.valid) {
          const errorMsgs = validation.errors
            .filter(e => e.severity === 'error')
            .map(e => `[${e.field}] ${e.message}`);
          if (errorMsgs.length > 0) {
            setError(errorMsgs.join('; '));
            // Navigate to the relevant section based on first error field
            const firstField = validation.errors.find(e => e.severity === 'error')?.field ?? '';
            if (firstField.startsWith('tasks') || firstField === 'tasks') setActiveSection('tasks');
            else if (firstField.startsWith('agents') || firstField === 'agents') setActiveSection('agents');
            else if (firstField === 'name' || firstField === 'title') setActiveSection('basic');
            setSaving(false);
            return;
          }
        }
      } catch {
        // If validation endpoint fails (e.g. server unavailable), proceed with save
        // and let the server-side save validation catch errors
      }

      if (isEdit && editName) {
        await swarmService.updatePreset(editName, request);
      } else {
        await swarmService.createPreset(request);
      }
      await loadPresets();
      navigate('/workflow');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const canSubmit = !saving;

  if (loadingDetail) {
    return (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="flex items-center gap-4 px-6 py-4 border-b border-border shrink-0">
        <button
          onClick={() => navigate('/workflow')}
          className="p-2 rounded-md hover:bg-muted transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="text-xl font-semibold">
          {isEdit ? i18n('Edit Workflow Preset') : i18n('Create Workflow Preset')}
        </h1>
        <div className="flex-1" />
        <button
          type="button"
          onClick={() => setAiPanelOpen(prev => !prev)}
          className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md border border-primary/40 text-primary hover:bg-primary/10 transition-colors"
        >
          <Sparkles className="w-3.5 h-3.5" />
          {i18n('AI Generate Workflow')}
        </button>
      </div>

      {/* Body: sidebar + content */}
      <div className={`flex-1 flex overflow-hidden ${navResizing ? 'resizing' : ''}`}>
        {/* Left Navigation */}
        <nav className="shrink-0 border-r border-border overflow-y-auto py-4" style={{ width: navWidth }}>
          {sections.map(section => (
            <button
              key={section.id}
              type="button"
              onClick={() => setActiveSection(section.id)}
              className={`flex items-center gap-2.5 w-full px-4 py-2.5 text-sm transition-colors ${
                activeSection === section.id
                  ? 'bg-accent text-accent-foreground font-medium'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted'
              }`}
            >
              {section.icon}
              {section.label}
            </button>
          ))}
        </nav>

        {/* Resize handle for nav */}
        <div
          className={`resize-handle ${navResizing ? 'active' : ''}`}
          onMouseDown={(e) => {
            navResizer.setCurrentWidth(navWidth);
            navResizer.onMouseDown(e);
          }}
          onTouchStart={(e) => {
            navResizer.setCurrentWidth(navWidth);
            navResizer.onTouchStart(e);
          }}
        />

        {/* Main Content */}
        <div className="flex-1 overflow-y-auto">
          <form onSubmit={handleSubmit} className={`mx-auto p-6 ${activeSection === 'tasks' ? 'max-w-none' : 'max-w-3xl'}`}>
            {error && (
              <div ref={errorRef} className="p-4 mb-6 rounded-lg bg-destructive/10 text-destructive text-sm border border-destructive/20">
                {error}
              </div>
            )}

            {/* === Basic Section === */}
            {activeSection === 'basic' && (
              <div className="space-y-6">
                <h2 className="text-sm font-medium flex items-center gap-2 text-muted-foreground uppercase tracking-wide">
                  <GitBranch className="w-4 h-4" />
                  {i18n('Basic Information')}
                </h2>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium mb-1">{i18n('Name')} *</label>
                    <input
                      className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary disabled:opacity-50"
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      placeholder="code-review-pipeline"
                      disabled={isEdit}
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1">{i18n('Title')} *</label>
                    <input
                      className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                      value={title}
                      onChange={(e) => setTitle(e.target.value)}
                      placeholder="Code Review Pipeline"
                    />
                  </div>
                </div>
                <div>
                  <label className="block text-sm font-medium mb-1">{i18n('Description')}</label>
                  <textarea
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary resize-y min-h-16"
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder="A multi-agent workflow for code review..."
                  />
                </div>
                <div>
                  <label className="flex items-center gap-2 text-sm font-medium mb-1">
                    <Globe className="w-4 h-4" />
                    {i18n('Response Language')}
                  </label>
                  <select
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    value={language}
                    onChange={(e) => setLanguage(e.target.value)}
                  >
                    <option value="">{i18n('Auto (follow prompt language)')}</option>
                    <option value="zh-CN">中文 (Chinese)</option>
                    <option value="en-US">English</option>
                    <option value="ja-JP">日本語 (Japanese)</option>
                    <option value="ko-KR">한국어 (Korean)</option>
                    <option value="fr-FR">Français (French)</option>
                    <option value="de-DE">Deutsch (German)</option>
                    <option value="es-ES">Español (Spanish)</option>
                  </select>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {i18n('Force all agent responses to use the selected language regardless of prompt language')}
                  </p>
                </div>
              </div>
            )}

            {/* === Agents Section === */}
            {activeSection === 'agents' && (
              <div className="space-y-3">
                <h2 className="text-sm font-medium flex items-center gap-2 text-muted-foreground uppercase tracking-wide">
                  <Users className="w-4 h-4" />
                  {i18n('Agents')} ({agents.length})
                </h2>
                <SwarmAgentEditor
                  agents={agents}
                  onChange={setAgents}
                  availableAgents={availableAgents}
                  availableModels={availableModels}
                  availableTools={availableTools}
                  taskAgentIds={taskAgentIds}
                  variables={variables}
                />
              </div>
            )}

            {/* === Tasks Section === */}
            {activeSection === 'tasks' && (
              <div className="space-y-3">
                <h2 className="text-sm font-medium flex items-center gap-2 text-muted-foreground uppercase tracking-wide">
                  <ListChecks className="w-4 h-4" />
                  {i18n('Tasks')} ({tasks.length})
                </h2>
                <SwarmTaskEditor
                  tasks={tasks}
                  onChange={setTasks}
                  agents={agents}
                  variables={variables}
                  availableAgents={availableAgents}
                />
              </div>
            )}

            {/* === Variables Section === */}
            {activeSection === 'variables' && (
              <div className="space-y-3">
                <h2 className="text-sm font-medium flex items-center gap-2 text-muted-foreground uppercase tracking-wide">
                  <Variable className="w-4 h-4" />
                  {i18n('Variables')} ({variables.length})
                </h2>
                <SwarmVariableEditor
                  variables={variables}
                  onChange={setVariables}
                  taskPrompts={tasks.map(t => t.promptTemplate)}
                />
              </div>
            )}

            {/* Actions */}
            <div className="flex items-center gap-3 pt-6 mt-8 border-t border-border">
              <button
                type="submit"
                disabled={!canSubmit}
                className="flex items-center gap-2 px-6 py-2 rounded-md bg-primary text-primary-foreground font-medium hover:bg-primary/90 disabled:opacity-50 transition-colors"
              >
                {saving ? (
                  <Loader2 className="w-4 h-4 animate-spin" />
                ) : (
                  <Save className="w-4 h-4" />
                )}
                {isEdit ? i18n('Update Preset') : i18n('Create Preset')}
              </button>
              <button
                type="button"
                onClick={() => navigate('/workflow')}
                className="px-6 py-2 rounded-md bg-muted hover:bg-muted/80 font-medium"
              >
                {i18n('Cancel')}
              </button>
            </div>
          </form>
        </div>

        {/* AI side panel */}
        {aiPanelOpen && (
          <AiConfigPanel
            configType="swarm"
            existingConfig={buildCurrentConfig()}
            onApply={handleAiApply}
            onClose={() => setAiPanelOpen(false)}
          />
        )}
      </div>
    </div>
  );
};
