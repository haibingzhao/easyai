import React, { useMemo, useRef } from 'react';
import type { TeamSpecDto, SwarmAgentSpecDto, SwarmVariableDto } from '@/services/swarm-service';
import { JinjaTemplateEditor, type JinjaTemplateEditorHandle } from '@/components/agent/JinjaTemplateEditor';
import { type VariableGroup } from '@/components/agent/VariableDropdown';
import { i18n } from '@/utils/i18n';
import { safeParseInt } from '@/utils/format';
import { SWARM_PROMPT_VARIABLES } from '@/constants/swarm-variables';

/** Team-specific built-in variables for the context template. */
const TEAM_BUILTIN_VARS: { name: string; description: string }[] = [
  { name: 'user_input', description: 'User input variable from workflow.' },
];

export interface TeamConfigPanelProps {
  team: TeamSpecDto;
  agents: SwarmAgentSpecDto[];
  variables: SwarmVariableDto[];
  inputFrom: Record<string, string>;
  onUpdate: (team: Partial<TeamSpecDto>) => void;
}

export const TeamConfigPanel: React.FC<TeamConfigPanelProps> = ({
  team,
  agents,
  variables,
  inputFrom,
  onUpdate,
}) => {
  const contextEditorRef = useRef<JinjaTemplateEditorHandle>(null);

  const inputFromKeys = Object.keys(inputFrom);

  /** Categorized variable groups for the dropdown picker. */
  const allVariableGroups: VariableGroup[] = useMemo(() => [
    {
      label: i18n('Agent Context'),
      vars: SWARM_PROMPT_VARIABLES,
      dotColor: 'bg-primary',
    },
    {
      label: i18n('Team'),
      vars: TEAM_BUILTIN_VARS,
      dotColor: 'bg-teal-400',
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
        description: i18n('Upstream task result from') + ` "${inputFrom[k]}"`,
      })),
      dotColor: 'bg-amber-400',
    },
  ], [variables, inputFromKeys, inputFrom]);

  // Toggle team member
  const toggleTeamMember = (agentId: string) => {
    if (team.members.includes(agentId)) {
      onUpdate({ members: team.members.filter((m) => m !== agentId) });
    } else {
      onUpdate({ members: [...team.members, agentId] });
    }
  };

  return (
    <div className="space-y-3 p-3 rounded-lg border border-teal-500/30 bg-teal-500/5">
      <h3 className="text-xs font-medium text-teal-400">{i18n('Team Configuration')}</h3>

      {/* Leader */}
      <div>
        <label className="block text-xs font-medium mb-1">{i18n('Leader')} *</label>
        <select
          className="w-full px-2 py-1.5 text-xs rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
          value={team.leader}
          onChange={(e) => onUpdate({ leader: e.target.value })}
        >
          <option value="">{i18n('Select leader...')}</option>
          {agents.map((a) => (
            <option key={a.id} value={a.id}>{a.id} ({a.role})</option>
          ))}
        </select>
      </div>

      {/* Members */}
      <div>
        <label className="block text-xs font-medium mb-1">{i18n('Members')} * ({team.members.length})</label>
        <div className="flex flex-wrap gap-1.5">
          {agents.map((a) => (
            <label
              key={a.id}
              className="flex items-center gap-1.5 px-2 py-1 rounded-md border border-border text-xs cursor-pointer hover:bg-muted"
            >
              <input
                type="checkbox"
                checked={team.members.includes(a.id)}
                onChange={() => toggleTeamMember(a.id)}
                className="rounded"
              />
              <span>{a.id}</span>
              <span className="text-[10px] text-muted-foreground">({a.role})</span>
            </label>
          ))}
        </div>
      </div>

      {/* Max Rounds + Max Dynamic Tasks + Round Timeout */}
      <div className="grid grid-cols-3 gap-3">
        <div>
          <label className="block text-xs font-medium mb-1">{i18n('Max Iterations')}</label>
          <input
            type="number"
            min={1}
            className="w-full px-2 py-1.5 text-xs rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
            value={team.maxIterations}
            onChange={(e) => onUpdate({ maxIterations: safeParseInt(e.target.value, 5) })}
          />
        </div>
        <div>
          <label className="block text-xs font-medium mb-1">{i18n('Max Tasks')}</label>
          <input
            type="number"
            min={1}
            className="w-full px-2 py-1.5 text-xs rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
            value={team.maxDynamicTasks}
            onChange={(e) => onUpdate({ maxDynamicTasks: safeParseInt(e.target.value, 10) })}
          />
        </div>
        <div>
          <label className="block text-xs font-medium mb-1">{i18n('Timeout (s)')}</label>
          <input
            type="number"
            min={1}
            className="w-full px-2 py-1.5 text-xs rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
            value={team.roundTimeoutSeconds}
            onChange={(e) => onUpdate({ roundTimeoutSeconds: safeParseInt(e.target.value, 600) })}
          />
        </div>
      </div>

      {/* Context Template */}
      <div>
        <label className="block text-xs font-medium mb-1">{i18n('Context Template')}</label>
        <p className="text-[10px] text-muted-foreground mb-1.5">
          {i18n('Describe the team objective and context. The Leader auto-generates planning and coordination prompts from this template. Supports Jinja2 variables.')}
        </p>
        <JinjaTemplateEditor
          ref={contextEditorRef}
          value={team.contextTemplate}
          onChange={(v) => onUpdate({ contextTemplate: v })}
          placeholder="Coordinate the team to analyze and resolve: {{ user_input }}"
          rows={3}
          variableGroups={allVariableGroups}
        />
      </div>
    </div>
  );
};
