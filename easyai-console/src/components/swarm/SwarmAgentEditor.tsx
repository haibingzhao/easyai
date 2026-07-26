import React, { useState, useEffect, useMemo } from 'react';
import { Plus, Pencil, Trash2, Copy, ChevronDown, ChevronRight, X, Check } from 'lucide-react';
import type { SwarmAgentSpecDto, SwarmVariableDto } from '@/services/swarm-service';
import type { AgentDto, ToolInfo } from '@/types/agent';
import type { ModelProviderConfig } from '@/types/settings';
import { JinjaTemplateEditor } from '@/components/agent/JinjaTemplateEditor';
import { ToolTooltip } from '@/components/agent/ToolItem';
import { McpSelector } from '@/components/agent/McpSelector';
import { type VariableGroup } from '@/components/agent/VariableDropdown';
import { SWARM_PROMPT_VARIABLES } from '@/constants/swarm-variables';
import { SWARM_EXCLUDED_TOOLS, selectableTools } from '@/constants/tools';
import { i18n } from '@/utils/i18n';
import { safeParseInt } from '@/utils/format';

interface SwarmAgentEditorProps {
  agents: SwarmAgentSpecDto[];
  onChange: (agents: SwarmAgentSpecDto[]) => void;
  availableAgents: AgentDto[];
  availableModels: ModelProviderConfig[];
  availableTools: ToolInfo[];
  taskAgentIds: Set<string>;
  variables?: SwarmVariableDto[];
}

type AgentSource = 'global' | 'custom';

const DEFAULT_AGENT: SwarmAgentSpecDto = {
  id: '',
  agentDefinitionId: '',
  role: '',
  maxIterations: 50,
  timeoutSeconds: 300,
  modelName: undefined,
  maxRetries: 2,
  name: '',
  description: '',
  systemPrompt: '',
  toolNames: [],
  mcpConfigs: [],
};

export const SwarmAgentEditor: React.FC<SwarmAgentEditorProps> = ({
  agents,
  onChange,
  availableAgents,
  availableModels,
  availableTools,
  taskAgentIds,
  variables = [],
}) => {
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [editForm, setEditForm] = useState<SwarmAgentSpecDto>({ ...DEFAULT_AGENT });
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null);
  const [deleteConfirmIndex, setDeleteConfirmIndex] = useState<number | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [agentSource, setAgentSource] = useState<AgentSource>('global');

  /** Tools selectable for inline custom agents (auto-injected and swarm-unsupported tools excluded). */
  const selectableAgentTools = useMemo(
    () => selectableTools(availableTools).filter((t) => !SWARM_EXCLUDED_TOOLS.includes(t.name)),
    [availableTools]
  );

  /** Variable groups for the custom agent System Prompt editor. */
  const systemPromptVariableGroups: VariableGroup[] = useMemo(() => [
    { label: i18n('Agent Context'), vars: SWARM_PROMPT_VARIABLES, dotColor: 'bg-primary' },
    { label: i18n('Workflow Variables'), vars: variables.map((v) => ({ name: v.name, description: v.description || i18n('Workflow variable') })), dotColor: 'bg-emerald-400' },
  ], [variables]);

  // Reset editing state when agents change externally (e.g., AI apply)
  useEffect(() => {
    setEditingIndex(null);
    setEditForm({ ...DEFAULT_AGENT });
  }, [agents]);

  const startAdd = () => {
    const newAgent = { ...DEFAULT_AGENT };
    setEditForm(newAgent);
    setEditingIndex(agents.length);
    setAgentSource('global');
    setFieldError(null);
  };

  const startEdit = (index: number) => {
    const agent = agents[index];
    setEditForm({ ...agent });
    setEditingIndex(index);
    setExpandedIndex(null);
    setFieldError(null);
    setAgentSource(agent.agentDefinitionId ? 'global' : 'custom');
  };

  const validateForm = (): boolean => {
    if (!editForm.id.trim()) {
      setFieldError('ID is required');
      return false;
    }
    // Check uniqueness
    const existingIds = new Set(agents.map((a, i) => (i !== editingIndex ? a.id : null)));
    if (existingIds.has(editForm.id.trim())) {
      setFieldError(`ID '${editForm.id}' already exists`);
      return false;
    }
    if (agentSource === 'global' && !editForm.agentDefinitionId) {
      setFieldError('Agent Definition is required');
      return false;
    }
    if (agentSource === 'custom' && !editForm.name?.trim()) {
      setFieldError('Name is required for custom agent');
      return false;
    }
    if (!editForm.role.trim()) {
      setFieldError('Role is required');
      return false;
    }
    return true;
  };

  const confirmEdit = () => {
    if (!validateForm()) return;
    const updated = [...agents];
    const cleaned: SwarmAgentSpecDto = {
      id: editForm.id.trim(),
      role: editForm.role.trim(),
      maxIterations: editForm.maxIterations ?? 50,
      timeoutSeconds: editForm.timeoutSeconds ?? 300,
      modelName: editForm.modelName || undefined,
      maxRetries: editForm.maxRetries ?? 2,
      ...(agentSource === 'global'
        ? { agentDefinitionId: editForm.agentDefinitionId }
        : {
            agentDefinitionId: '',
            name: editForm.name?.trim() || '',
            description: editForm.description?.trim() || '',
            systemPrompt: editForm.systemPrompt || '',
            toolNames: editForm.toolNames || [],
            mcpConfigs: editForm.mcpConfigs || [],
          }),
    };
    if (editingIndex === agents.length) {
      updated.push(cleaned);
    } else if (editingIndex !== null) {
      updated[editingIndex] = cleaned;
    }
    onChange(updated);
    setEditingIndex(null);
    setFieldError(null);
  };

  const cancelEdit = () => {
    setEditingIndex(null);
    setFieldError(null);
  };

  const deleteAgent = (index: number) => {
    const agent = agents[index];
    if (deleteConfirmIndex === index) {
      onChange(agents.filter((_, i) => i !== index));
      setDeleteConfirmIndex(null);
      if (editingIndex === index) {
        setEditingIndex(null);
      }
    } else if (taskAgentIds.has(agent.id)) {
      setDeleteConfirmIndex(index);
    } else {
      onChange(agents.filter((_, i) => i !== index));
      if (editingIndex === index) {
        setEditingIndex(null);
      }
    }
  };

  const duplicateAgent = (index: number) => {
    const source = agents[index];
    // Generate a unique ID by appending -copy, -copy-2, etc.
    const existingIds = new Set(agents.map((a) => a.id));
    let newId = `${source.id}-copy`;
    let suffix = 2;
    while (existingIds.has(newId)) {
      newId = `${source.id}-copy-${suffix}`;
      suffix++;
    }
    setEditForm({ ...source, id: newId });
    setEditingIndex(agents.length);
    setExpandedIndex(null);
    setFieldError(null);
    setAgentSource(source.agentDefinitionId ? 'global' : 'custom');
  };

  const toggleExpand = (index: number) => {
    if (editingIndex === index) return;
    setExpandedIndex(expandedIndex === index ? null : index);
  };

  return (
    <div className="space-y-3">
      {agents.map((agent, index) => {
        const isEditing = editingIndex === index;
        const isExpanded = expandedIndex === index;
        const isReferenced = taskAgentIds.has(agent.id);

        if (isEditing) {
          return (
            <div key={index} className="p-4 rounded-lg border-2 border-primary bg-card space-y-4">
              {fieldError && (
                <div className="p-2 rounded bg-destructive/10 text-destructive text-sm">{fieldError}</div>
              )}
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium mb-1">{i18n('ID')} *</label>
                  <input
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    value={editForm.id}
                    onChange={(e) => setEditForm({ ...editForm, id: e.target.value })}
                    placeholder="reviewer"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium mb-1">{i18n('Role')} *</label>
                  <input
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    value={editForm.role}
                    onChange={(e) => setEditForm({ ...editForm, role: e.target.value })}
                    placeholder="Code Reviewer"
                  />
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">{i18n('Agent Source')}</label>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setAgentSource('global')}
                    className={`px-3 py-1.5 text-xs rounded-md border transition-colors ${agentSource === 'global' ? 'bg-primary text-primary-foreground border-primary' : 'border-border text-muted-foreground hover:text-foreground'}`}
                  >
                    {i18n('Global Agent')}
                  </button>
                  <button
                    type="button"
                    onClick={() => setAgentSource('custom')}
                    className={`px-3 py-1.5 text-xs rounded-md border transition-colors ${agentSource === 'custom' ? 'bg-primary text-primary-foreground border-primary' : 'border-border text-muted-foreground hover:text-foreground'}`}
                  >
                    {i18n('Custom Agent')}
                  </button>
                </div>
              </div>
              {agentSource === 'global' ? (
                <div>
                  <label className="block text-sm font-medium mb-1">{i18n('Agent Definition')} *</label>
                  <select
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    value={editForm.agentDefinitionId ?? ''}
                    onChange={(e) => setEditForm({ ...editForm, agentDefinitionId: e.target.value })}
                  >
                    <option value="">{i18n('Select an agent...')}</option>
                    {availableAgents.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.name} ({a.id})
                      </option>
                    ))}
                  </select>
                </div>
              ) : (
                <div className="space-y-3">
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-medium mb-1">{i18n('Name')} *</label>
                      <input
                        className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                        value={editForm.name ?? ''}
                        onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                        placeholder="Review Agent"
                      />
                    </div>
                    <div>
                      <label className="block text-sm font-medium mb-1">{i18n('Description')}</label>
                      <input
                        className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                        value={editForm.description ?? ''}
                        onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
                        placeholder="Reviews code changes"
                      />
                    </div>
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1">{i18n('System Prompt')}</label>
                    <JinjaTemplateEditor
                      value={editForm.systemPrompt ?? ''}
                      onChange={(val) => setEditForm({ ...editForm, systemPrompt: val })}
                      placeholder="You are a code reviewer..."
                      rows={5}
                      variableGroups={systemPromptVariableGroups}
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1">{i18n('Tools')}</label>
                    <div className="max-h-[120px] overflow-y-auto rounded-md border border-border p-2 space-y-1">
                      {selectableAgentTools.map((tool) => (
                        <ToolTooltip key={tool.name} name={tool.name} description={tool.description}>
                          <label className="flex items-center gap-2 text-xs cursor-pointer hover:bg-muted/50 rounded px-1 py-0.5">
                            <input
                              type="checkbox"
                              checked={(editForm.toolNames ?? []).includes(tool.name)}
                              onChange={(e) => {
                                const current = editForm.toolNames ?? [];
                                const next = e.target.checked
                                  ? [...current, tool.name]
                                  : current.filter((t) => t !== tool.name);
                                setEditForm({ ...editForm, toolNames: next });
                              }}
                              className="rounded border-border"
                            />
                            <span className="font-medium">{tool.name}</span>
                            <span className="text-muted-foreground truncate">{tool.description}</span>
                          </label>
                        </ToolTooltip>
                      ))}
                    </div>
                  </div>
                  <McpSelector
                    selectedConfigs={editForm.mcpConfigs ?? []}
                    onChange={(configs) => setEditForm({ ...editForm, mcpConfigs: configs })}
                  />
                </div>
              )}
              <div className="grid grid-cols-3 gap-4">
                <div>
                  <label className="block text-sm font-medium mb-1">{i18n('Max Iterations')}</label>
                  <input
                    type="number"
                    min={1}
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    value={editForm.maxIterations ?? 50}
                    onChange={(e) => setEditForm({ ...editForm, maxIterations: safeParseInt(e.target.value, 50) })}
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium mb-1">{i18n('Timeout (s)')}</label>
                  <input
                    type="number"
                    min={1}
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    value={editForm.timeoutSeconds ?? 300}
                    onChange={(e) => setEditForm({ ...editForm, timeoutSeconds: safeParseInt(e.target.value, 300) })}
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium mb-1">{i18n('Max Retries')}</label>
                  <input
                    type="number"
                    min={0}
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    value={editForm.maxRetries ?? 2}
                    onChange={(e) => setEditForm({ ...editForm, maxRetries: safeParseInt(e.target.value, 0) })}
                  />
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">{i18n('Model Override')}</label>
                <select
                  className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                  value={editForm.modelName ?? ''}
                  onChange={(e) => setEditForm({ ...editForm, modelName: e.target.value || undefined })}
                >
                  <option value="">{i18n('Default (inherit)')}</option>
                  {availableModels.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.modelName || m.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={confirmEdit}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90"
                >
                  <Check className="w-3.5 h-3.5" />
                  {editingIndex === agents.length ? i18n('Add') : i18n('Save')}
                </button>
                <button
                  type="button"
                  onClick={cancelEdit}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-muted hover:bg-muted/80"
                >
                  <X className="w-3.5 h-3.5" />
                  {i18n('Cancel')}
                </button>
              </div>
            </div>
          );
        }

        return (
          <div key={index} className="rounded-lg border border-border bg-card">
            <div className="flex items-center gap-2 p-3 cursor-pointer" onClick={() => toggleExpand(index)}>
              {isExpanded ? <ChevronDown className="w-4 h-4 shrink-0" /> : <ChevronRight className="w-4 h-4 shrink-0" />}
              <span className="font-medium text-sm">{agent.id}</span>
              <span className="text-xs text-muted-foreground px-1.5 py-0.5 rounded bg-muted">{agent.role}</span>
              <div className="flex-1" />
              <button
                type="button"
                onClick={(e) => { e.stopPropagation(); startEdit(index); }}
                className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
              >
                <Pencil className="w-3.5 h-3.5" />
              </button>
              <button
                type="button"
                onClick={(e) => { e.stopPropagation(); duplicateAgent(index); }}
                className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
                title={i18n('Duplicate')}
              >
                <Copy className="w-3.5 h-3.5" />
              </button>
              <button
                type="button"
                onClick={(e) => { e.stopPropagation(); deleteAgent(index); }}
                className={`p-1.5 rounded hover:bg-destructive/10 ${deleteConfirmIndex === index ? 'text-destructive' : 'text-muted-foreground hover:text-destructive'}`}
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>
            </div>
            {deleteConfirmIndex === index && isReferenced && (
              <div className="px-3 pb-2 text-xs text-destructive">
                {i18n('This agent is referenced by tasks. Click delete again to confirm.')}
              </div>
            )}
            {isExpanded && (
              <div className="px-4 pb-3 pt-1 border-t border-border text-sm space-y-1.5">
                <div className="text-xs text-muted-foreground">
                  {agent.agentDefinitionId ? (
                    <>{i18n('Agent')}: <code className="text-foreground/80">{agent.agentDefinitionId}</code></>
                  ) : (
                    <>{i18n('Custom')}: <code className="text-foreground/80">{agent.name || agent.id}</code>
                      {agent.toolNames && agent.toolNames.length > 0 && <span className="ml-2">({agent.toolNames.length} tools)</span>}
                      {agent.mcpConfigs && agent.mcpConfigs.length > 0 && <span className="ml-2">({agent.mcpConfigs.length} MCP)</span>}
                    </>
                  )}
                </div>
                <div className="flex gap-4 text-xs text-muted-foreground">
                  <span>maxIter={agent.maxIterations ?? 50}</span>
                  <span>timeout={agent.timeoutSeconds ?? 300}s</span>
                  <span>maxRetries={agent.maxRetries ?? 2}</span>
                  {agent.modelName && <span>model={agent.modelName}</span>}
                </div>
              </div>
            )}
          </div>
        );
      })}

      {/* Adding new agent (editingIndex === agents.length) */}
      {editingIndex === agents.length && (
        <div className="p-4 rounded-lg border-2 border-primary bg-card space-y-4">
          {fieldError && (
            <div className="p-2 rounded bg-destructive/10 text-destructive text-sm">{fieldError}</div>
          )}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium mb-1">{i18n('ID')} *</label>
              <input
                className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                value={editForm.id}
                onChange={(e) => setEditForm({ ...editForm, id: e.target.value })}
                placeholder="reviewer"
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">{i18n('Role')} *</label>
              <input
                className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                value={editForm.role}
                onChange={(e) => setEditForm({ ...editForm, role: e.target.value })}
                placeholder="Code Reviewer"
              />
            </div>
          </div>
          <div>
            <label className="block text-sm font-medium mb-1">{i18n('Agent Source')}</label>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setAgentSource('global')}
                className={`px-3 py-1.5 text-xs rounded-md border transition-colors ${agentSource === 'global' ? 'bg-primary text-primary-foreground border-primary' : 'border-border text-muted-foreground hover:text-foreground'}`}
              >
                {i18n('Global Agent')}
              </button>
              <button
                type="button"
                onClick={() => setAgentSource('custom')}
                className={`px-3 py-1.5 text-xs rounded-md border transition-colors ${agentSource === 'custom' ? 'bg-primary text-primary-foreground border-primary' : 'border-border text-muted-foreground hover:text-foreground'}`}
              >
                {i18n('Custom Agent')}
              </button>
            </div>
          </div>
          {agentSource === 'global' ? (
            <div>
              <label className="block text-sm font-medium mb-1">{i18n('Agent Definition')} *</label>
              <select
                className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                value={editForm.agentDefinitionId ?? ''}
                onChange={(e) => setEditForm({ ...editForm, agentDefinitionId: e.target.value })}
              >
                <option value="">{i18n('Select an agent...')}</option>
                {availableAgents.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name} ({a.id})
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium mb-1">{i18n('Name')} *</label>
                  <input
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    value={editForm.name ?? ''}
                    onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                    placeholder="Review Agent"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium mb-1">{i18n('Description')}</label>
                  <input
                    className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    value={editForm.description ?? ''}
                    onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
                    placeholder="Reviews code changes"
                  />
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">{i18n('System Prompt')}</label>
                <JinjaTemplateEditor
                  value={editForm.systemPrompt ?? ''}
                  onChange={(val) => setEditForm({ ...editForm, systemPrompt: val })}
                  placeholder="You are a code reviewer..."
                  rows={5}
                  variableGroups={systemPromptVariableGroups}
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">{i18n('Tools')}</label>
                <div className="max-h-[120px] overflow-y-auto rounded-md border border-border p-2 space-y-1">
                  {selectableAgentTools.map((tool) => (
                    <ToolTooltip key={tool.name} name={tool.name} description={tool.description}>
                      <label className="flex items-center gap-2 text-xs cursor-pointer hover:bg-muted/50 rounded px-1 py-0.5">
                        <input
                          type="checkbox"
                          checked={(editForm.toolNames ?? []).includes(tool.name)}
                          onChange={(e) => {
                            const current = editForm.toolNames ?? [];
                            const next = e.target.checked
                              ? [...current, tool.name]
                              : current.filter((t) => t !== tool.name);
                            setEditForm({ ...editForm, toolNames: next });
                          }}
                          className="rounded border-border"
                        />
                        <span className="font-medium">{tool.name}</span>
                        <span className="text-muted-foreground truncate">{tool.description}</span>
                      </label>
                    </ToolTooltip>
                  ))}
                </div>
              </div>
              <McpSelector
                selectedConfigs={editForm.mcpConfigs ?? []}
                onChange={(configs) => setEditForm({ ...editForm, mcpConfigs: configs })}
              />
            </div>
          )}
          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="block text-sm font-medium mb-1">{i18n('Max Iterations')}</label>
              <input
                type="number"
                min={1}
                className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                value={editForm.maxIterations ?? 50}
                onChange={(e) => setEditForm({ ...editForm, maxIterations: safeParseInt(e.target.value, 50) })}
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">{i18n('Timeout (s)')}</label>
              <input
                type="number"
                min={1}
                className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                value={editForm.timeoutSeconds ?? 300}
                onChange={(e) => setEditForm({ ...editForm, timeoutSeconds: safeParseInt(e.target.value, 300) })}
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">{i18n('Max Retries')}</label>
              <input
                type="number"
                min={0}
                className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                value={editForm.maxRetries ?? 2}
                onChange={(e) => setEditForm({ ...editForm, maxRetries: safeParseInt(e.target.value, 0) })}
              />
            </div>
          </div>
          <div>
            <label className="block text-sm font-medium mb-1">{i18n('Model Override')}</label>
            <select
              className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
              value={editForm.modelName ?? ''}
              onChange={(e) => setEditForm({ ...editForm, modelName: e.target.value || undefined })}
            >
              <option value="">{i18n('Default (inherit)')}</option>
              {availableModels.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.modelName || m.name}
                </option>
              ))}
            </select>
          </div>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={confirmEdit}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90"
            >
              <Check className="w-3.5 h-3.5" />
              {i18n('Add')}
            </button>
            <button
              type="button"
              onClick={cancelEdit}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-muted hover:bg-muted/80"
            >
              <X className="w-3.5 h-3.5" />
              {i18n('Cancel')}
            </button>
          </div>
        </div>
      )}

      {/* Add Agent button */}
      {editingIndex === null && (
        <button
          type="button"
          onClick={startAdd}
          className="flex items-center gap-2 w-full py-2.5 px-4 rounded-lg border border-dashed border-border text-sm text-muted-foreground hover:text-foreground hover:border-primary/50 transition-colors"
        >
          <Plus className="w-4 h-4" />
          {i18n('Add Agent')}
        </button>
      )}
    </div>
  );
};
