import React from 'react';
import type { InlineAgentSpec, ToolInfo, McpBindingDto } from '@/types/agent';
import { JinjaTemplateEditor } from './JinjaTemplateEditor';
import { type VariableGroup } from './VariableDropdown';
import { McpSelector } from './McpSelector';
import { SkillSelector } from './SkillSelector';
import { ToolTooltip } from './ToolItem';
import { Trash2, Plus, ChevronDown, ChevronRight, AlertTriangle } from 'lucide-react';
import { selectableTools } from '@/constants/tools';
import { useWebSearchStatus } from '@/hooks/useWebSearchStatus';
import { i18n } from '@/utils/i18n';

/**
 * PromptContext variables available for inline custom member/sub-agent system prompts.
 * These agents are rendered via PromptTemplateService with a restricted context:
 * sub_agents and team_members are always empty (recursion guard) and thus excluded.
 * custom_instructions contains the auto-generated role definition + delegated task.
 */
const INLINE_AGENT_PROMPT_VARIABLES: { name: string; description: string }[] = [
  { name: 'custom_instructions', description: 'Auto-generated role definition + delegated task + response requirements.' },
  { name: 'tools', description: 'Available tools (list of {name, description}).' },
  { name: 'skills', description: 'Available skills (list of {name, description}).' },
  { name: 'instructions', description: 'Project instructions from AGENTS.md (list of {name, content, source}).' },
  { name: 'memory', description: 'Persistent cross-session memory content.' },
  { name: 'model_id', description: 'Active model identifier (e.g. gpt-4o, claude-sonnet-4).' },
  { name: 'protocol', description: 'Model protocol (e.g. openai, anthropic).' },
  { name: 'os', description: 'Operating system name (e.g. Mac OS X, Linux).' },
  { name: 'cwd', description: 'Current working directory absolute path.' },
  { name: 'input', description: 'Structured input data passed from the parent agent.' },
  { name: 'current_date_time', description: 'Current date/time (yyyy-MM-dd HH:mm:ss z).' },
];

const inlineAgentVariableGroups: VariableGroup[] = [
  {
    label: i18n('Agent Context'),
    vars: INLINE_AGENT_PROMPT_VARIABLES,
    dotColor: 'bg-primary',
  },
];

interface InlineAgentEditorProps {
  value: InlineAgentSpec;
  onChange: (spec: InlineAgentSpec) => void;
  onRemove?: () => void;
  availableTools: ToolInfo[];
  disabled?: boolean;
  index: number;
}

/**
 * Editor for a single inline custom agent specification.
 * Supports configuring name, description, system prompt, tools, skills, and MCP servers.
 */
export const InlineAgentEditor: React.FC<InlineAgentEditorProps> = ({
  value,
  onChange,
  onRemove,
  availableTools,
  disabled,
  index,
}) => {
  const [expanded, setExpanded] = React.useState(true);
  const { configured: webSearchConfigured } = useWebSearchStatus();
  const showWebSearchWarning = value.toolNames.includes('websearch') && webSearchConfigured === false;

  return (
    <div className="border border-border rounded-lg overflow-hidden">
      {/* Header bar */}
      <div
        className="flex items-center gap-2 px-3 py-2 bg-muted/30 cursor-pointer select-none"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? (
          <ChevronDown className="w-4 h-4 text-muted-foreground" />
        ) : (
          <ChevronRight className="w-4 h-4 text-muted-foreground" />
        )}
        <span className="text-sm font-medium flex-1">
          {value.name || `Custom Agent ${index + 1}`}
        </span>
        {onRemove && !disabled && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onRemove();
            }}
            className="p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-colors"
            title="Remove"
          >
            <Trash2 className="w-4 h-4" />
          </button>
        )}
      </div>

      {/* Body */}
      {expanded && (
        <div className="p-3 space-y-3">
          {/* Name + Description */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium mb-1">Name *</label>
              <input
                className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                value={value.name}
                onChange={(e) => onChange({ ...value, name: e.target.value })}
                placeholder="e.g. Code Reviewer"
                disabled={disabled}
              />
            </div>
            <div>
              <label className="block text-xs font-medium mb-1">Description</label>
              <input
                className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                value={value.description}
                onChange={(e) => onChange({ ...value, description: e.target.value })}
                placeholder="Reviews code changes"
                disabled={disabled}
              />
            </div>
          </div>

          {/* System Prompt */}
          <div>
            <label className="block text-xs font-medium mb-1">System Prompt</label>
            <JinjaTemplateEditor
              value={value.systemPrompt}
              onChange={(val) => onChange({ ...value, systemPrompt: val })}
              placeholder="You are a specialized agent..."
              rows={4}
              disabled={disabled}
              variableGroups={inlineAgentVariableGroups}
            />
          </div>

          {/* Tools */}
          <div>
            <label className="block text-xs font-medium mb-1">Tools</label>
            {showWebSearchWarning && (
              <div className="flex items-start gap-1.5 p-2 mb-1.5 rounded-md bg-amber-500/10 text-amber-600 dark:text-amber-400 text-[11px]">
                <AlertTriangle className="w-3.5 h-3.5 mt-0.5 shrink-0" />
                <span>
                  {i18n('websearch requires an API key. Configure in Settings → Integrations.')}
                </span>
              </div>
            )}
            <div className="max-h-[120px] overflow-y-auto rounded-md border border-border p-2 space-y-1">
              {selectableTools(availableTools).map((tool) => (
                <ToolTooltip key={tool.name} name={tool.name} description={tool.description}>
                  <label className="flex items-center gap-2 text-xs cursor-pointer hover:bg-muted/50 rounded px-1 py-0.5">
                    <input
                      type="checkbox"
                      checked={value.toolNames.includes(tool.name)}
                      onChange={(e) => {
                        const next = e.target.checked
                          ? [...value.toolNames, tool.name]
                          : value.toolNames.filter((t) => t !== tool.name);
                        onChange({ ...value, toolNames: next });
                      }}
                      disabled={disabled}
                      className="rounded border-border"
                    />
                    <span className="font-medium">{tool.name}</span>
                    <span className="text-muted-foreground truncate">{tool.description}</span>
                  </label>
                </ToolTooltip>
              ))}
            </div>
          </div>

          {/* Skills */}
          <SkillSelector
            selectedSkills={value.skillNames}
            onChange={(skills) => onChange({ ...value, skillNames: skills })}
            disabled={disabled}
          />

          {/* MCP */}
          <McpSelector
            selectedConfigs={value.mcpConfigs}
            onChange={(configs: McpBindingDto[]) => onChange({ ...value, mcpConfigs: configs })}
            disabled={disabled}
          />
        </div>
      )}
    </div>
  );
};

interface InlineAgentListProps {
  specs: InlineAgentSpec[];
  onChange: (specs: InlineAgentSpec[]) => void;
  availableTools: ToolInfo[];
  disabled?: boolean;
}

/**
 * List editor for multiple inline custom agents with add/remove capability.
 */
export const InlineAgentList: React.FC<InlineAgentListProps> = ({
  specs,
  onChange,
  availableTools,
  disabled,
}) => {
  const handleAdd = () => {
    onChange([
      ...specs,
      {
        name: '',
        description: '',
        systemPrompt: '',
        toolNames: [],
        skillNames: [],
        mcpConfigs: [],
      },
    ]);
  };

  const handleUpdate = (index: number, spec: InlineAgentSpec) => {
    const next = [...specs];
    next[index] = spec;
    onChange(next);
  };

  const handleRemove = (index: number) => {
    onChange(specs.filter((_, i) => i !== index));
  };

  return (
    <div className="space-y-2">
      {specs.map((spec, i) => (
        <InlineAgentEditor
          key={i}
          index={i}
          value={spec}
          onChange={(s) => handleUpdate(i, s)}
          onRemove={() => handleRemove(i)}
          availableTools={availableTools}
          disabled={disabled}
        />
      ))}
      {!disabled && (
        <button
          type="button"
          onClick={handleAdd}
          className="flex items-center gap-1.5 text-xs text-primary hover:text-primary/80 transition-colors px-1 py-1"
        >
          <Plus className="w-3.5 h-3.5" />
          Add Custom Agent
        </button>
      )}
    </div>
  );
};
