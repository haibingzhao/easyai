import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useAgentStore } from '@/services/stores/agent-store';
import { ToolSelector } from '@/components/agent/ToolSelector';
import { SubAgentSelector } from '@/components/agent/SubAgentSelector';
import { TeamMemberSelector } from '@/components/agent/TeamMemberSelector';
import { SkillSelector } from '@/components/agent/SkillSelector';
import { McpSelector } from '@/components/agent/McpSelector';
import { CommandSelector } from '@/components/agent/CommandSelector';
import { SchemaEditor } from '@/components/agent/SchemaEditor';
import { ArrowLeft, Save, Loader2, Bot, Shield, Settings, Terminal, BookOpen, Users, Server, Zap, Braces, Sparkles, Network } from 'lucide-react';
import { JinjaTemplateEditor, type JinjaTemplateEditorHandle } from '@/components/agent/JinjaTemplateEditor';
import { type VariableGroup } from '@/components/agent/VariableDropdown';
import { AiConfigPanel } from '@/components/ai/AiConfigPanel';
import type { AgentCreateRequest, AgentType, AgentEnv, McpBindingDto, InlineAgentSpec, TemplateValidationError } from '@/types/agent';
import { agentService } from '@/services/agent-service';
import { SWARM_EXCLUDED_TOOLS } from '@/constants/tools';
import { i18n } from '@/utils/i18n';
import { useResizable } from '@/hooks/useResizable';

const MAX_NAME_LENGTH = 20;
const MAX_CALLSIGN_LENGTH = 50;
const MAX_DESCRIPTION_LENGTH = 200;
const MAX_INSTRUCTIONS_LENGTH = 10000;

/** PromptContext variables available for Jinja2 agent prompt templates. */
const PROMPT_CONTEXT_VARIABLES: { name: string; description: string }[] = [
  { name: 'tools', description: 'Available tools (list of {name, description}).' },
  { name: 'skills', description: 'Available skills (list of {name, description}).' },
  { name: 'sub_agents', description: 'Delegatable sub-agents (list of {name, description, inputSchema}).' },
  { name: 'instructions', description: 'Project instructions from AGENTS.md (list of {name, content, source}).' },
  { name: 'custom_instructions', description: 'Free-text instructions from the Custom Instructions field.' },
  { name: 'memory', description: 'Persistent cross-session memory content.' },
  { name: 'model_id', description: 'Active model identifier (e.g. gpt-4o, claude-sonnet-4).' },
  { name: 'protocol', description: 'Model protocol (e.g. openai, anthropic).' },
  { name: 'os', description: 'Operating system name (e.g. Mac OS X, Linux).' },
  { name: 'cwd', description: 'Current working directory absolute path.' },
  { name: 'input', description: 'Structured input from API request (access via {{ input.field_name }}).' },
  { name: 'current_date_time', description: 'Current date/time (yyyy-MM-dd HH:mm:ss z).' },
];

type SectionId = 'basic' | 'tools' | 'skills' | 'subagents' | 'members' | 'mcp' | 'commands' | 'schema';

interface NavSection {
  id: SectionId;
  label: string;
  icon: React.ReactNode;
  hidden?: boolean;
}

export const AgentCreatePage: React.FC = () => {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const isEdit = !!id;

  const { agents, subAgents, tools, selectedAgentId, createAgent, updateAgent, loadTools, loadSubAgents, loadSkills, fetchAgent } = useAgentStore();

  // Use ref to avoid re-triggering the fetchAgent useEffect when agents list changes
  const agentsRef = useRef(agents);
  agentsRef.current = agents;
  const selectedAgentIdRef = useRef(selectedAgentId);
  selectedAgentIdRef.current = selectedAgentId;

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [callsign, setCallsign] = useState('');
  const [agentType, setAgentType] = useState<AgentType>('PRIMARY');
  const [agentContext, setAgentContext] = useState<AgentEnv>('CHAT');
  const [promptTemplate, setPromptTemplate] = useState('');
  const [selectedTools, setSelectedTools] = useState<string[]>([]);
  const [selectedSubAgents, setSelectedSubAgents] = useState<string[]>([]);
  const [selectedMembers, setSelectedMembers] = useState<string[]>([]);
  const [customSubAgents, setCustomSubAgents] = useState<InlineAgentSpec[]>([]);
  const [customMembers, setCustomMembers] = useState<InlineAgentSpec[]>([]);
  const [selectedSkills, setSelectedSkills] = useState<string[]>([]);
  const [selectedMcpConfigs, setSelectedMcpConfigs] = useState<McpBindingDto[]>([]);
  const [selectedCommands, setSelectedCommands] = useState<string[]>([]);
  const [maxIterations, setMaxIterations] = useState(50);
  const [instructionsEnabled, setInstructionsEnabled] = useState(true);
  const [inputSchemaEnabled, setInputSchemaEnabled] = useState(false);
  const [inputSchema, setInputSchema] = useState('');
  const [outputSchemaEnabled, setOutputSchemaEnabled] = useState(false);
  const [outputSchema, setOutputSchema] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [readOnly, setReadOnly] = useState(false);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [activeSection, setActiveSection] = useState<SectionId>('basic');
  const [templateValidating, setTemplateValidating] = useState(false);
  const [templateValidationErrors, setTemplateValidationErrors] = useState<TemplateValidationError[] | undefined>(undefined);
  const [templateValidationPassed, setTemplateValidationPassed] = useState(false);
  const [aiPanelOpen, setAiPanelOpen] = useState(false);
  const [navWidth, setNavWidth] = useState(192);
  const [navResizing, setNavResizing] = useState(false);
  const promptEditorRef = useRef<JinjaTemplateEditorHandle>(null);

  /** Categorized variable groups for the prompt editor dropdown picker. */
  const promptVariableGroups: VariableGroup[] = useMemo(() => {
    const groups: VariableGroup[] = [
      {
        label: i18n('Agent Context'),
        vars: PROMPT_CONTEXT_VARIABLES,
        dotColor: 'bg-primary',
      },
    ];

    // Parse input schema properties into input.xxx variables
    if (inputSchemaEnabled && inputSchema.trim()) {
      try {
        const parsed = JSON.parse(inputSchema);
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
              dotColor: 'bg-emerald-400',
            });
          }
        }
      } catch {
        // Invalid JSON — silently skip input schema variables
      }
    }

    return groups;
  }, [inputSchemaEnabled, inputSchema]);

  const navResizer = useResizable({
    minWidth: 120,
    maxWidth: 320,
    onResize: (w) => setNavWidth(Math.round(w)),
    direction: 'right',
    onResizeStart: () => setNavResizing(true),
    onResizeEnd: () => setNavResizing(false),
  });

  useEffect(() => {
    loadTools();
    loadSubAgents();
    loadSkills();
  }, [loadTools, loadSubAgents, loadSkills]);

  useEffect(() => {
    if (isEdit && id) {
      setLoadingDetail(true);
      fetchAgent(id).then((agent) => {
        setName(agent.name);
        setDescription(agent.description || '');
        setCallsign(agent.id);
        setAgentType(agent.agentType);
        setAgentContext(agent.agentContext ?? 'CHAT');
        setPromptTemplate(agent.promptTemplate || '');
        setSelectedTools(agent.toolNames);
        setSelectedSubAgents(agent.subAgentIds || []);
        setSelectedMembers(agent.memberIds || []);
        setCustomSubAgents(agent.customSubAgents || []);
        setCustomMembers(agent.customMembers || []);
        setSelectedSkills(agent.skillNames || []);
        setSelectedMcpConfigs(agent.mcpConfigs || []);
        setSelectedCommands(agent.commandNames || []);
        setMaxIterations(agent.maxIterations);
        setInstructionsEnabled(agent.instructionsEnabled);
        setInputSchemaEnabled(!!agent.inputSchema);
        setInputSchema(agent.inputSchema || '');
        setOutputSchemaEnabled(!!agent.outputSchema);
        setOutputSchema(agent.outputSchema || '');
        setReadOnly(agent.builtin);
      }).finally(() => setLoadingDetail(false));
    } else {
      // For new agents, set defaults based on selected agent (read from ref to avoid loop)
      const selected = agentsRef.current.find(a => a.id === selectedAgentIdRef.current);
      if (selected) {
        setName('');
        setDescription('');
        setCallsign('');
        setAgentType('PRIMARY');
        setAgentContext('CHAT');
        setPromptTemplate('');
        setSelectedTools(['glob', 'grep', 'ls', 'read']);
        setSelectedSubAgents([]);
        setSelectedMembers([]);
        setCustomSubAgents([]);
        setCustomMembers([]);
        setSelectedSkills([]);
        setSelectedMcpConfigs([]);
        setSelectedCommands([]);
        setMaxIterations(50);
        setInstructionsEnabled(true);
        setInputSchemaEnabled(false);
        setInputSchema('');
        setOutputSchemaEnabled(false);
        setOutputSchema('');
      }
    }
  }, [isEdit, id, fetchAgent]);

  const isSwarmContext = agentContext === 'SWARM';
  const isChatContext = agentContext === 'CHAT';

  const sections: NavSection[] = [
    { id: 'basic', label: i18n('Basic'), icon: <Settings className="w-4 h-4" /> },
    { id: 'tools', label: i18n('Tools'), icon: <Terminal className="w-4 h-4" /> },
    { id: 'skills', label: i18n('Skills'), icon: <BookOpen className="w-4 h-4" />, hidden: isSwarmContext },
    { id: 'subagents', label: i18n('Sub Agents'), icon: <Users className="w-4 h-4" />, hidden: agentType === 'SUBAGENT' || agentType === 'TEAM' || isSwarmContext },
    { id: 'members', label: i18n('Members'), icon: <Network className="w-4 h-4" />, hidden: agentType !== 'TEAM' || isSwarmContext },
    { id: 'mcp', label: i18n('MCP'), icon: <Server className="w-4 h-4" /> },
    { id: 'commands', label: i18n('Commands'), icon: <Zap className="w-4 h-4" />, hidden: isSwarmContext },
    { id: 'schema', label: i18n('Schema'), icon: <Braces className="w-4 h-4" />, hidden: isChatContext },
  ];

  // Reset active section when it becomes hidden due to context/type change
  useEffect(() => {
    const current = sections.find(s => s.id === activeSection);
    if (current?.hidden) {
      setActiveSection('basic');
    }
  }, [agentContext, agentType]);

  // Swarm agents are always PRIMARY — the PRIMARY/SUBAGENT distinction only applies to Chat
  useEffect(() => {
    if (agentContext === 'SWARM' && agentType !== 'PRIMARY') {
      setAgentType('PRIMARY');
    }
  }, [agentContext, agentType]);

  const handleAiApply = useCallback((config: Record<string, unknown>) => {
    if (typeof config.name === 'string') setName(config.name);
    if (typeof config.description === 'string') setDescription(config.description);
    if (typeof config.id === 'string') setCallsign(config.id);
    if (config.agentType === 'PRIMARY' || config.agentType === 'SUBAGENT' || config.agentType === 'TEAM') setAgentType(config.agentType);
    if (config.agentContext === 'CHAT' || config.agentContext === 'SWARM') setAgentContext(config.agentContext);
    if (typeof config.promptTemplate === 'string') setPromptTemplate(config.promptTemplate);
    if (Array.isArray(config.toolNames)) {
      const effectiveContext = config.agentContext === 'SWARM' || config.agentContext === 'CHAT'
        ? config.agentContext : agentContext;
      const rawTools = config.toolNames as string[];
      setSelectedTools(effectiveContext === 'SWARM'
        ? rawTools.filter(t => !SWARM_EXCLUDED_TOOLS.includes(t))
        : rawTools);
    }
    if (Array.isArray(config.subAgentIds)) setSelectedSubAgents(config.subAgentIds as string[]);
    if (Array.isArray(config.memberIds)) setSelectedMembers(config.memberIds as string[]);
    if (Array.isArray(config.customSubAgents)) setCustomSubAgents(config.customSubAgents as InlineAgentSpec[]);
    if (Array.isArray(config.customMembers)) setCustomMembers(config.customMembers as InlineAgentSpec[]);
    if (Array.isArray(config.skillNames)) setSelectedSkills(config.skillNames as string[]);
    if (Array.isArray(config.mcpConfigs)) setSelectedMcpConfigs(config.mcpConfigs as McpBindingDto[]);
    if (Array.isArray(config.commandNames)) setSelectedCommands(config.commandNames as string[]);
    if (typeof config.maxIterations === 'number') setMaxIterations(config.maxIterations);
    if (typeof config.instructionsEnabled === 'boolean') setInstructionsEnabled(config.instructionsEnabled);
    if (typeof config.inputSchema === 'string') { setInputSchemaEnabled(true); setInputSchema(config.inputSchema); }
    if (typeof config.outputSchema === 'string') { setOutputSchemaEnabled(true); setOutputSchema(config.outputSchema); }
  }, [agentContext]);

  const buildCurrentConfig = useCallback(() => ({
    id: callsign, name, description, agentType, agentContext,
    toolNames: selectedTools, subAgentIds: selectedSubAgents,
    skillNames: selectedSkills, mcpConfigs: selectedMcpConfigs,
    commandNames: selectedCommands, memberIds: selectedMembers, maxIterations,
    promptTemplate, customSubAgents, customMembers,
  }), [callsign, name, description, agentType, agentContext, selectedTools, selectedSubAgents,
    selectedSkills, selectedMcpConfigs, selectedCommands, selectedMembers, maxIterations,
    promptTemplate, customSubAgents, customMembers]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!name.trim()) {
      setError('Name is required');
      return;
    }

    if (!callsign.trim()) {
      setError('Call sign is required');
      return;
    }

    if (name.length > MAX_NAME_LENGTH) {
      setError(`Name must be ${MAX_NAME_LENGTH} characters or less`);
      return;
    }

    if (callsign.length > MAX_CALLSIGN_LENGTH) {
      setError(`Call sign must be ${MAX_CALLSIGN_LENGTH} characters or less`);
      return;
    }

    if (!readOnly && !promptTemplate.trim()) {
      setError('System Prompt is required');
      return;
    }

    if (agentType === 'TEAM' && selectedMembers.length === 0 && customMembers.length === 0) {
      setError('TEAM agent requires at least one member');
      return;
    }

    const request: AgentCreateRequest = {
      id: isEdit ? callsign : callsign.toLowerCase().replace(/\s+/g, '-'),
      name: name.trim(),
      description: description.trim() || undefined,
      agentType,
      agentContext,
      promptTemplate: promptTemplate.trim() || undefined,
      toolNames: selectedTools,
      subAgentIds: selectedSubAgents,
      skillNames: selectedSkills,
      mcpConfigs: selectedMcpConfigs,
      commandNames: selectedCommands,
      memberIds: agentType === 'TEAM' ? selectedMembers : [],
      customSubAgents: customSubAgents.length > 0 ? customSubAgents : undefined,
      customMembers: customMembers.length > 0 ? customMembers : undefined,
      maxIterations,
      maxSubAgentDepth: 1,
      enabled: true,
      instructionsEnabled,
      inputSchema: inputSchemaEnabled && inputSchema.trim() ? inputSchema.trim() : undefined,
      outputSchema: outputSchemaEnabled && outputSchema.trim() ? outputSchema.trim() : undefined,
    };

    setSaving(true);
    try {
      if (isEdit && id) {
        await updateAgent(id, request);
      } else {
        await createAgent(request);
      }
      navigate('/agents');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="flex items-center gap-4 px-6 py-4 border-b border-border shrink-0">
        <button
          onClick={() => navigate('/agents')}
          className="p-2 rounded-md hover:bg-muted transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="text-xl font-semibold">
          {isEdit ? (readOnly ? i18n('View Agent') : i18n('Edit Agent')) : i18n('Create Agent')}
        </h1>
        {readOnly && (
          <span className="flex items-center gap-1.5 text-xs text-blue-400 bg-blue-400/10 px-2 py-1 rounded">
            <Shield className="w-3 h-3" />
            {i18n('Built-in Agent (Read-only)')}
          </span>
        )}
        <div className="flex-1" />
        {!readOnly && (
          <button
            type="button"
            onClick={() => setAiPanelOpen(prev => !prev)}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md border border-primary/40 text-primary hover:bg-primary/10 transition-colors"
          >
            <Sparkles className="w-3.5 h-3.5" />
            {i18n('AI Generate')}
          </button>
        )}
      </div>

      {/* Body: sidebar + content */}
      <div className={`flex-1 flex overflow-hidden ${navResizing ? 'resizing' : ''}`}>
        {/* Left Navigation */}
        <nav className="shrink-0 border-r border-border overflow-y-auto py-4" style={{ width: navWidth }}>
          {sections.filter(s => !s.hidden).map(section => (
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
          {loadingDetail ? (
            <div className="flex items-center justify-center h-48">
              <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
            </div>
          ) : (
          <form onSubmit={handleSubmit} className="max-w-3xl mx-auto p-6">
            {error && (
              <div className="p-4 mb-6 rounded-lg bg-destructive/10 text-destructive text-sm">
                {error}
              </div>
            )}

            {/* === Basic Section === */}
            {activeSection === 'basic' && (
              <div className="space-y-8">
                {/* Name and Icon */}
                <div className="flex items-start gap-6">
                  <div className="flex-shrink-0 w-20 h-20 rounded-xl bg-muted flex items-center justify-center">
                    <Bot className="w-10 h-10 text-muted-foreground" />
                  </div>
                  <div className="flex-1 space-y-1">
                    <label className="text-sm font-medium">
                      Name <span className="text-destructive">*</span>
                    </label>
                    <div className="relative">
                      <input
                        type="text"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="Enter agent name"
                        maxLength={MAX_NAME_LENGTH}
                        disabled={readOnly}
                        className="w-full px-3 py-2 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed"
                      />
                      <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">
                        {name.length}/{MAX_NAME_LENGTH}
                      </span>
                    </div>
                  </div>
                </div>

                {/* Description */}
                <div className="space-y-2">
                  <label className="text-sm font-medium">Description</label>
                  <div className="relative">
                    <textarea
                      value={description}
                      onChange={(e) => setDescription(e.target.value)}
                      placeholder="Describe what this agent specializes in..."
                      maxLength={MAX_DESCRIPTION_LENGTH}
                      rows={3}
                      disabled={readOnly}
                      className="w-full px-3 py-2 rounded-md border border-input bg-background text-sm resize-none focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed"
                    />
                    <div className="absolute bottom-2 right-2 text-xs text-muted-foreground">
                      <span>{description.length}/{MAX_DESCRIPTION_LENGTH}</span>
                    </div>
                  </div>
                </div>

                {/* Call Sign */}
                <div className="space-y-2">
                  <label className="text-sm font-medium">
                    Call Sign <span className="text-destructive">*</span>
                  </label>
                  <div className="relative">
                    <input
                      type="text"
                      value={callsign}
                      onChange={(e) => setCallsign(e.target.value)}
                      placeholder="Unique identifier, e.g.: project-analyzer"
                      maxLength={MAX_CALLSIGN_LENGTH}
                      disabled={isEdit || readOnly}
                      className="w-full px-3 py-2 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed"
                    />
                    <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">
                      {callsign.length}/{MAX_CALLSIGN_LENGTH}
                    </span>
                  </div>
                </div>

                {/* Agent Type — hidden for Swarm agents (always PRIMARY) */}
                {!isSwarmContext && (
                <div className="space-y-2">
                  <label className="text-sm font-medium">Agent Type</label>
                  <p className="text-xs text-muted-foreground mb-2">
                    Controls how this agent can be used.
                  </p>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => setAgentType('PRIMARY')}
                      disabled={readOnly}
                      className={`flex-1 px-4 py-2 rounded-md text-sm font-medium transition-colors ${
                        agentType === 'PRIMARY'
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted hover:bg-muted/80'
                      } ${readOnly ? 'opacity-60 cursor-not-allowed' : ''}`}
                    >
                      Primary
                    </button>
                    <button
                      type="button"
                      onClick={() => setAgentType('SUBAGENT')}
                      disabled={readOnly}
                      className={`flex-1 px-4 py-2 rounded-md text-sm font-medium transition-colors ${
                        agentType === 'SUBAGENT'
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted hover:bg-muted/80'
                      } ${readOnly ? 'opacity-60 cursor-not-allowed' : ''}`}
                    >
                      Sub-Agent
                    </button>
                    <button
                      type="button"
                      onClick={() => setAgentType('TEAM')}
                      disabled={readOnly}
                      className={`flex-1 px-4 py-2 rounded-md text-sm font-medium transition-colors ${
                        agentType === 'TEAM'
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted hover:bg-muted/80'
                      } ${readOnly ? 'opacity-60 cursor-not-allowed' : ''}`}
                    >
                      Team
                    </button>
                    <button
                      type="button"
                      onClick={() => setAgentType('ALL')}
                      disabled={readOnly}
                      className={`flex-1 px-4 py-2 rounded-md text-sm font-medium transition-colors ${
                        agentType === 'ALL'
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted hover:bg-muted/80'
                      } ${readOnly ? 'opacity-60 cursor-not-allowed' : ''}`}
                    >
                      ALL
                    </button>
                  </div>
                </div>
                )}

                {/* Agent Context */}
                <div className="space-y-2">
                  <label className="text-sm font-medium">Agent Context</label>
                  <p className="text-xs text-muted-foreground mb-2">
                    Controls where this agent's prompt can be correctly rendered.
                  </p>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => setAgentContext('CHAT')}
                      disabled={readOnly}
                      className={`flex-1 px-4 py-2 rounded-md text-sm font-medium transition-colors ${
                        agentContext === 'CHAT'
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted hover:bg-muted/80'
                      } ${readOnly ? 'opacity-60 cursor-not-allowed' : ''}`}
                    >
                      Chat
                    </button>
                    <button
                      type="button"
                      onClick={() => setAgentContext('SWARM')}
                      disabled={readOnly}
                      className={`flex-1 px-4 py-2 rounded-md text-sm font-medium transition-colors ${
                        agentContext === 'SWARM'
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted hover:bg-muted/80'
                      } ${readOnly ? 'opacity-60 cursor-not-allowed' : ''}`}
                    >
                      Swarm
                    </button>
                  </div>
                </div>

                {/* System Prompt */}
                <div className="space-y-2">
                  <label className="text-sm font-medium">System Prompt {!readOnly && <span className="text-destructive">*</span>}</label>
                  <JinjaTemplateEditor
                    ref={promptEditorRef}
                    value={promptTemplate}
                    onChange={(v) => {
                      setPromptTemplate(v);
                      // Clear validation state when content changes
                      setTemplateValidationErrors(undefined);
                      setTemplateValidationPassed(false);
                    }}
                    placeholder="Define the agent's role, tone, workflow, tool preferences, and rules. Supports Jinja2 syntax: {{ tools }}, {{ skills }}, {{ memory }}, etc."
                    maxLength={MAX_INSTRUCTIONS_LENGTH}
                    rows={8}
                    disabled={readOnly}
                    variableGroups={promptVariableGroups}
                    onValidate={async () => {
                      setTemplateValidating(true);
                      setTemplateValidationErrors(undefined);
                      setTemplateValidationPassed(false);
                      try {
                        const result = await agentService.validateTemplate(promptTemplate);
                        if (result.valid) {
                          setTemplateValidationErrors([]);
                          setTemplateValidationPassed(true);
                          // Auto-dismiss success indicator after 3 seconds
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
                    }}
                    validating={templateValidating}
                    validationErrors={templateValidationErrors}
                    validationPassed={templateValidationPassed}
                  />
                </div>

                {/* Project Instructions */}
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <div>
                      <label className="text-sm font-medium">Project Instructions</label>
                      <p className="text-xs text-muted-foreground">
                        Automatically load AGENTS.md from the project workspace into the system prompt.
                      </p>
                    </div>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={instructionsEnabled}
                      onClick={() => setInstructionsEnabled(!instructionsEnabled)}
                      disabled={readOnly}
                      className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 ${
                        instructionsEnabled ? '' : 'bg-muted'
                      } ${readOnly ? 'opacity-60 cursor-not-allowed' : ''}`}
                      style={instructionsEnabled ? { backgroundColor: '#22c55e' } : undefined}
                    >
                      <span
                        className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-background shadow ring-0 transition duration-200 ease-in-out ${
                          instructionsEnabled ? 'translate-x-5' : 'translate-x-0'
                        }`}
                      />
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* === Tools Section === */}
            {activeSection === 'tools' && (
              <ToolSelector
                selectedTools={selectedTools}
                onChange={setSelectedTools}
                disabled={readOnly}
                excludeTools={isSwarmContext ? SWARM_EXCLUDED_TOOLS : undefined}
              />
            )}

            {/* === Skills Section === */}
            {activeSection === 'skills' && (
              <SkillSelector
                selectedSkills={selectedSkills}
                onChange={setSelectedSkills}
                disabled={readOnly}
              />
            )}

            {/* === Sub Agents Section === */}
            {activeSection === 'subagents' && agentType !== 'SUBAGENT' && agentType !== 'TEAM' && (
              <SubAgentSelector
                selectedSubAgents={selectedSubAgents}
                onChange={setSelectedSubAgents}
                availableSubAgents={subAgents}
                customSubAgents={customSubAgents}
                onCustomChange={setCustomSubAgents}
                availableTools={tools}
                disabled={readOnly}
              />
            )}

            {/* === Team Members Section === */}
            {activeSection === 'members' && agentType === 'TEAM' && (
              <TeamMemberSelector
                selectedMembers={selectedMembers}
                onChange={setSelectedMembers}
                availableAgents={agents}
                customMembers={customMembers}
                onCustomChange={setCustomMembers}
                availableTools={tools}
                disabled={readOnly}
              />
            )}

            {/* === MCP Section === */}
            {activeSection === 'mcp' && (
              <McpSelector
                selectedConfigs={selectedMcpConfigs}
                onChange={setSelectedMcpConfigs}
                disabled={readOnly}
              />
            )}

            {/* === Commands Section === */}
            {activeSection === 'commands' && (
              <CommandSelector
                selectedCommands={selectedCommands}
                onChange={setSelectedCommands}
                disabled={readOnly}
              />
            )}

            {/* === Schema Section === */}
            {activeSection === 'schema' && (
              <div className="space-y-8">
                <SchemaEditor
                  enabled={inputSchemaEnabled}
                  onEnabledChange={setInputSchemaEnabled}
                  schema={inputSchema}
                  onSchemaChange={setInputSchema}
                  disabled={readOnly}
                  title="Input Schema"
                  description="Define a JSON Schema to validate and structure user input for this agent."
                />
                <SchemaEditor
                  enabled={outputSchemaEnabled}
                  onEnabledChange={setOutputSchemaEnabled}
                  schema={outputSchema}
                  onSchemaChange={setOutputSchema}
                  disabled={readOnly}
                  title="Output Schema"
                  description="Define a JSON Schema to enforce structured output from the agent."
                />
              </div>
            )}

            {/* Actions */}
            <div className="flex items-center gap-3 pt-6 mt-8 border-t border-border">
              {!readOnly && (
                <button
                  type="submit"
                  disabled={saving}
                  className="flex items-center gap-2 px-6 py-2 rounded-md bg-primary text-primary-foreground font-medium hover:bg-primary/90 disabled:opacity-50"
                >
                  {saving ? (
                    <Loader2 className="w-4 h-4 animate-spin" />
                  ) : (
                    <Save className="w-4 h-4" />
                  )}
                  {isEdit ? 'Update' : 'Create'}
                </button>
              )}
              <button
                type="button"
                onClick={() => navigate('/agents')}
                className="px-6 py-2 rounded-md bg-muted hover:bg-muted/80 font-medium"
              >
                {readOnly ? i18n('Back') : i18n('Cancel')}
              </button>
            </div>
          </form>
          )}
        </div>

        {/* Side panel triggered by header button — inside body flex row to appear on the right */}
        {aiPanelOpen && (
          <AiConfigPanel
            configType="agent"
            existingConfig={buildCurrentConfig()}
            onApply={handleAiApply}
            onClose={() => setAiPanelOpen(false)}
          />
        )}
      </div>
    </div>
  );
};