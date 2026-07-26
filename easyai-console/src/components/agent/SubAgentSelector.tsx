import React, { useState } from 'react';
import type { AgentDto, InlineAgentSpec, ToolInfo } from '@/types/agent';
import { Bot, ExternalLink } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { InlineAgentList } from './InlineAgentEditor';

interface SubAgentSelectorProps {
  selectedSubAgents: string[];
  onChange: (ids: string[]) => void;
  availableSubAgents: AgentDto[];
  customSubAgents: InlineAgentSpec[];
  onCustomChange: (specs: InlineAgentSpec[]) => void;
  availableTools: ToolInfo[];
  disabled?: boolean;
}

export const SubAgentSelector: React.FC<SubAgentSelectorProps> = ({
  selectedSubAgents,
  onChange,
  availableSubAgents,
  customSubAgents,
  onCustomChange,
  availableTools,
  disabled,
}) => {
  const navigate = useNavigate();
  const [mode, setMode] = useState<'global' | 'custom'>(
    customSubAgents.length > 0 ? 'custom' : 'global'
  );

  const handleToggle = (agentId: string) => {
    if (selectedSubAgents.includes(agentId)) {
      onChange(selectedSubAgents.filter((id) => id !== agentId));
    } else {
      onChange([...selectedSubAgents, agentId]);
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium">Sub-Agents</label>
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
            Select which sub-agents this agent can delegate tasks to.
          </p>
          {availableSubAgents.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No sub-agents available. Create sub-agents first to configure them here.
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              {availableSubAgents.map((agent) => {
                const isSelected = selectedSubAgents.includes(agent.id);
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
                      title="Edit sub-agent tools"
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
            Define custom sub-agents inline. They are embedded in this agent and don&apos;t need separate creation.
          </p>
          <InlineAgentList
            specs={customSubAgents}
            onChange={onCustomChange}
            availableTools={availableTools}
            disabled={disabled}
          />
        </>
      )}
    </div>
  );
};
