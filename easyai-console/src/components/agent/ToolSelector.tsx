import React from 'react';
import { useAgentStore } from '@/services/stores/agent-store';
import { Bot, Terminal, FileText, Search, FolderSearch, List, MessageSquare, CheckSquare } from 'lucide-react';
import { selectableTools } from '@/constants/tools';
import { i18n } from '@/utils/i18n';

interface ToolSelectorProps {
  selectedTools: string[];
  onChange: (tools: string[]) => void;
  disabled?: boolean;
  /** Tool names to hide from the list (e.g., tools unavailable in swarm context). */
  excludeTools?: string[];
  /**
   * Tool names that stay selectable but are blocked at runtime for the current
   * agent type (e.g. task/run_swarm for SUBAGENT). Rendered de-emphasized with a
   * "runtime unavailable" hint instead of being hidden.
   */
  unavailableTools?: string[];
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

export const ToolSelector: React.FC<ToolSelectorProps> = ({ selectedTools, onChange, disabled, excludeTools, unavailableTools }) => {
  const { tools } = useAgentStore();

  // Auto-injected tools (alwaysInclude) are never manually selectable;
  // excludeTools removes context-specific tools (e.g. swarm-unsupported).
  const visibleTools = selectableTools(tools).filter(
    (tool) => !excludeTools || !excludeTools.includes(tool.name)
  );
  const unavailableSet = new Set(unavailableTools ?? []);

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
          const isUnavailable = unavailableSet.has(tool.name);
          const row = (
            <label
              key={tool.name}
              title={isUnavailable ? i18n('Blocked at runtime for Sub-Agent type') : undefined}
              className={`flex items-center gap-3 p-3 rounded-lg border transition-colors ${
                disabled ? 'cursor-not-allowed opacity-60' : isUnavailable ? 'cursor-pointer opacity-60' : 'cursor-pointer'
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
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">{tool.name}</span>
                  {isUnavailable && (
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground border border-border shrink-0">
                      {i18n('Runtime unavailable')}
                    </span>
                  )}
                </div>
                <div className="text-xs text-muted-foreground">{tool.description}</div>
              </div>
            </label>
          );
          return row;
        })}
      </div>
    </div>
  );
};