import React from 'react';
import type { AgentDto } from '@/types/agent';
import { Bot, ExternalLink } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

interface TeamMemberSelectorProps {
  selectedMembers: string[];
  onChange: (ids: string[]) => void;
  /** All agents; TEAM agents are filtered out internally (members cannot be teams). */
  availableAgents: AgentDto[];
  disabled?: boolean;
}

/**
 * Multi-select for Team Agent members.
 * Only non-TEAM agents (PRIMARY / SUBAGENT / ALL) can be selected as members.
 */
export const TeamMemberSelector: React.FC<TeamMemberSelectorProps> = ({
  selectedMembers,
  onChange,
  availableAgents,
  disabled,
}) => {
  const navigate = useNavigate();

  // Members must be existing non-TEAM agents
  const candidates = availableAgents.filter((a) => a.agentType !== 'TEAM');

  const handleToggle = (agentId: string) => {
    if (selectedMembers.includes(agentId)) {
      onChange(selectedMembers.filter((id) => id !== agentId));
    } else {
      onChange([...selectedMembers, agentId]);
    }
  };

  if (candidates.length === 0) {
    return (
      <div className="space-y-2">
        <label className="text-sm font-medium">Team Members</label>
        <p className="text-sm text-muted-foreground">
          No agents available as members. Create agents first, then add them to this team.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <label className="text-sm font-medium">Team Members</label>
      <p className="text-xs text-muted-foreground mb-2">
        Select the agents this team leader coordinates. The leader delegates tasks to members
        via <code className="text-xs">delegate_to_member</code> and receives events via{' '}
        <code className="text-xs">wait_for_member_events</code>. Members cannot be TEAM agents.
      </p>
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
    </div>
  );
};
