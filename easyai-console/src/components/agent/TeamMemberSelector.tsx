import React, { useState } from 'react';
import type { AgentDto, InlineAgentSpec, ToolInfo } from '@/types/agent';
import { Bot, ExternalLink } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { InlineAgentList } from './InlineAgentEditor';

interface TeamMemberSelectorProps {
  selectedMembers: string[];
  onChange: (ids: string[]) => void;
  /** All agents; only ALL/SUBAGENT agents are shown (members cannot be TEAM or PRIMARY). */
  availableAgents: AgentDto[];
  customMembers: InlineAgentSpec[];
  onCustomChange: (specs: InlineAgentSpec[]) => void;
  availableTools: ToolInfo[];
  disabled?: boolean;
}

/**
 * Multi-select for Team Agent members with Global/Custom dual mode.
 * Only ALL / SUBAGENT agents can be selected as global members.
 */
export const TeamMemberSelector: React.FC<TeamMemberSelectorProps> = ({
  selectedMembers,
  onChange,
  availableAgents,
  customMembers,
  onCustomChange,
  availableTools,
  disabled,
}) => {
  const navigate = useNavigate();
  const [mode, setMode] = useState<'global' | 'custom'>(
    customMembers.length > 0 ? 'custom' : 'global'
  );

  // Members must be ALL or SUBAGENT agents
  const candidates = availableAgents.filter((a) => a.agentType === 'ALL' || a.agentType === 'SUBAGENT');

  const handleToggle = (agentId: string) => {
    if (selectedMembers.includes(agentId)) {
      onChange(selectedMembers.filter((id) => id !== agentId));
    } else {
      onChange([...selectedMembers, agentId]);
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium">Team Members</label>
        <div className="flex gap-1">
          <button
            type="button"
            onClick={() => setMode('global')}
            className={`px-2.5 py-1 text-xs rounded-md border transition-colors ${
              mode === 'global'
                ? 'bg-primary text-primary-foreground border-primary'
                : 'border-border text-muted-foreground hover:text-foreground'
            }`}
          >
            Global
          </button>
          <button
            type="button"
            onClick={() => setMode('custom')}
            className={`px-2.5 py-1 text-xs rounded-md border transition-colors ${
              mode === 'custom'
                ? 'bg-primary text-primary-foreground border-primary'
                : 'border-border text-muted-foreground hover:text-foreground'
            }`}
          >
            Custom
          </button>
        </div>
      </div>

      {mode === 'global' ? (
        <>
          <p className="text-xs text-muted-foreground mb-2">
            Select the agents this team leader coordinates. Members must be ALL or Sub-Agent type.
          </p>
          {candidates.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No agents available as members. Create agents first, then add them to this team.
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              {candidates.map((agent) => {
                const isSelected = selectedMembers.includes(agent.id);
                return (
                  <div
                    key={agent.id}
                    className={`flex items-center gap-3 p-3 rounded-lg border transition-colors ${
                      isSelected
                        ? 'border-primary bg-primary/5'
                        : 'border-border hover:border-muted-foreground/50'
                    } ${disabled ? 'opacity-60' : ''}`}
                  >
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => handleToggle(agent.id)}
                      disabled={disabled}
                      className="w-4 h-4 rounded border-border"
                    />
                    <Bot className="w-5 h-5 text-muted-foreground flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium truncate">{agent.name}</span>
                        <span className="text-xs px-1.5 py-0.5 rounded bg-muted text-muted-foreground">
                          {agent.agentType}
                        </span>
                      </div>
                      {agent.description && (
                        <p className="text-xs text-muted-foreground truncate">
                          {agent.description}
                        </p>
                      )}
                    </div>
                    <button
                      type="button"
                      onClick={() => navigate(`/agents/edit/${agent.id}`)}
                      className="p-1 rounded hover:bg-muted transition-colors"
                      title="Edit member agent"
                      disabled={disabled}
                    >
                      <ExternalLink className="w-4 h-4 text-muted-foreground" />
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </>
      ) : (
        <>
          <p className="text-xs text-muted-foreground mb-2">
            Define custom team members inline. They are embedded in this team agent and don&apos;t need separate creation.
          </p>
          <InlineAgentList
            specs={customMembers}
            onChange={onCustomChange}
            availableTools={availableTools}
            disabled={disabled}
          />
        </>
      )}
    </div>
  );
};
