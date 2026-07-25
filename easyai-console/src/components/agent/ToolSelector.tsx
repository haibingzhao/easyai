import React from 'react';
import { useAgentStore } from '@/services/stores/agent-store';
import { Bot, Terminal, FileText, Search, FolderSearch, List, MessageSquare, CheckSquare } from 'lucide-react';

interface ToolSelectorProps {
  selectedTools: string[];
  onChange: (tools: string[]) => void;
  disabled?: boolean;
  /** Tool names to hide from the list (e.g., tools unavailable in swarm context). */
  excludeTools?: string[];
}

/** Icon mapping by permissionCategory */
const CATEGORY_ICONS: Record<string, React.ReactNode> = {
  shell: <Terminal className="w-4 h-4" />,
  file: <FileText className="w-4 h-4" />,
  interaction: <MessageSquare className="w-4 h-4" />,
  todo: <CheckSquare className="w-4 h-4" />,
  skill: <Bot className="w-4 h-4" />,
};

/** Fallback icon mapping by tool name (for tools without permissionCategory) */
const NAME_ICONS: Record<string, React.ReactNode> = {
  grep: <Search className="w-4 h-4" />,
  glob: <FolderSearch className="w-4 h-4" />,
  ls: <List className="w-4 h-4" />,
};

function getToolIcon(name: string, category?: string): React.ReactNode {
  if (category && CATEGORY_ICONS[category]) {
    return CATEGORY_ICONS[category];
  }
  return NAME_ICONS[name] || <Bot className="w-4 h-4" />;
}

export const ToolSelector: React.FC<ToolSelectorProps> = ({ selectedTools, onChange, disabled, excludeTools }) => {
  const { tools } = useAgentStore();

  const visibleTools = excludeTools && excludeTools.length > 0
    ? tools.filter((tool) => !excludeTools.includes(tool.name))
    : tools;

  const toggleTool = (toolName: string) => {
    if (selectedTools.includes(toolName)) {
      onChange(selectedTools.filter(t => t !== toolName));
    } else {
      onChange([...selectedTools, toolName]);
    }
  };

  return (
    <div className="space-y-3">
      <div>
        <label className="text-sm font-medium">Tools</label>
        <p className="text-xs text-muted-foreground mt-1">Select tools this agent can use</p>
      </div>

      <div className="space-y-2">
        {visibleTools.map((tool) => {
          const isSelected = selectedTools.includes(tool.name);
          return (
            <label
              key={tool.name}
              className={`flex items-center gap-3 p-3 rounded-lg border transition-colors ${
                disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'
              } ${
                isSelected
                  ? 'border-primary bg-primary/5'
                  : disabled ? 'border-border' : 'border-border hover:border-muted-foreground'
              }`}
            >
              <input
                type="checkbox"
                checked={isSelected}
                onChange={() => toggleTool(tool.name)}
                disabled={disabled}
                className="w-4 h-4 rounded border-input text-primary focus:ring-primary"
              />
              <span className="flex-shrink-0 text-muted-foreground">
                {getToolIcon(tool.name, tool.permissionCategory)}
              </span>
              <div className="flex-1 min-w-0">
                <div className="text-sm font-medium">{tool.name}</div>
                <div className="text-xs text-muted-foreground">{tool.description}</div>
              </div>
            </label>
          );
        })}
      </div>
    </div>
  );
};