import React, { useState, useEffect, useRef } from 'react';
import { useAgentStore } from '@/services/stores/agent-store';
import type { AgentDto } from '@/types/agent';
import { Check, Plus, Bot, ChevronDown } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

interface AgentSelectorProps {
  onSelect: (agentId: string) => void;
}

export const AgentSelector: React.FC<AgentSelectorProps> = ({ onSelect }) => {
  const navigate = useNavigate();
  const { agents, selectedAgentId, selectAgent } = useAgentStore();
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setDropdownOpen(false);
      }
    };

    if (dropdownOpen) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [dropdownOpen]);

  const allAgents: AgentDto[] = React.useMemo(() => {
    return agents.filter(a =>
      a.agentType !== 'SUBAGENT' &&
      (a.agentContext === 'CHAT' || a.agentContext === 'BOTH') &&
      !a.outputSchema &&
      !a.inputSchema
    );
  }, [agents]);

  const handleSelect = (agentId: string) => {
    selectAgent(agentId);
    onSelect(agentId);
    setDropdownOpen(false);
  };

  const selectedAgent = allAgents.find(a => a.id === selectedAgentId);

  return (
    <div ref={dropdownRef} className="relative">
      <button
        onClick={() => setDropdownOpen(!dropdownOpen)}
        className="flex items-center gap-1 px-2 py-1 text-xs text-muted-foreground hover:text-foreground rounded-md hover:bg-muted transition-colors"
      >
        {selectedAgent && (
          <span className="flex-shrink-0">
            <Bot className="w-3 h-3" />
          </span>
        )}
        {selectedAgent ? selectedAgent.name : 'Code'}
        <ChevronDown className={`w-3 h-3 transition-transform ${dropdownOpen ? 'rotate-180' : ''}`} />
      </button>

      {dropdownOpen && (
        <div className="absolute bottom-full left-0 mb-2 min-w-[200px] bg-popover border border-border rounded-md shadow-lg z-50 p-2">
          <div className="text-xs text-muted-foreground font-medium px-2 py-1">内置智能体</div>
          <div className="flex flex-col gap-1">
            {allAgents.map((agent) => {
              const isSelected = selectedAgentId === agent.id;
              return (
                <button
                  key={agent.id}
                  onClick={() => handleSelect(agent.id)}
                  className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm transition-colors text-left w-full ${
                    isSelected
                      ? 'bg-accent text-accent-foreground'
                      : 'hover:bg-muted'
                  }`}
                >
                  <span className="flex-shrink-0 w-5 h-5 flex items-center justify-center">
                    <Bot className="w-4 h-4" />
                  </span>
                  <span className="flex-1 font-medium">{agent.name}</span>
                  {isSelected && (
                    <Check className="w-4 h-4 text-green-400" />
                  )}
                </button>
              );
            })}
          </div>
          <div className="border-t border-border mt-2 pt-2">
            <button
              onClick={() => {
                navigate('/agents/create');
                setDropdownOpen(false);
              }}
              className="flex items-center justify-center gap-2 w-full px-3 py-2 rounded-lg text-sm border border-dashed border-border hover:border-primary hover:text-primary transition-colors"
            >
              <Plus className="w-4 h-4" />
              <span>创建智能体</span>
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
