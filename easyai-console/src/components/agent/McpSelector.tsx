import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { ChevronDown, ChevronRight, Server, Loader2, Wrench, MessageSquare, Search, X } from 'lucide-react';
import type { McpBindingDto } from '@/types/agent';
import type { McpServerDto, McpToolInfo, McpPromptInfo } from '@/types/mcp';
import { mcpService } from '@/services/mcp-service';
import { i18n } from '@/utils/i18n';
import { ToolTooltip } from './ToolItem';

interface McpSelectorProps {
  selectedConfigs: McpBindingDto[];
  onChange: (configs: McpBindingDto[]) => void;
  disabled?: boolean;
}

export const McpSelector: React.FC<McpSelectorProps> = ({ selectedConfigs, onChange, disabled }) => {
  const [servers, setServers] = useState<McpServerDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [toolsByServer, setToolsByServer] = useState<Record<string, McpToolInfo[]>>({});
  const [promptsByServer, setPromptsByServer] = useState<Record<string, McpPromptInfo[]>>({});
  const [expandedServer, setExpandedServer] = useState<string | null>(null);
  const [loadingTools, setLoadingTools] = useState<string | null>(null);
  const [serverSearch, setServerSearch] = useState('');
  const [toolSearch, setToolSearch] = useState('');

  const loadServers = useCallback(async () => {
    try {
      setLoading(true);
      const data = await mcpService.listServers();
      setServers(data.filter(s => s.enabled));
    } catch {
      // silently ignore - show empty list
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadServers();
  }, [loadServers]);

  const loadServerTools = async (serverName: string) => {
    if (toolsByServer[serverName]) return;
    setLoadingTools(serverName);
    try {
      const [tools, prompts] = await Promise.all([
        mcpService.getServerTools(serverName),
        mcpService.getServerPrompts(serverName).catch(() => [] as McpPromptInfo[]),
      ]);
      setToolsByServer(prev => ({ ...prev, [serverName]: tools }));
      setPromptsByServer(prev => ({ ...prev, [serverName]: prompts }));
    } catch {
      setToolsByServer(prev => ({ ...prev, [serverName]: [] }));
      setPromptsByServer(prev => ({ ...prev, [serverName]: [] }));
    } finally {
      setLoadingTools(null);
    }
  };

  const isServerSelected = (serverName: string) =>
    selectedConfigs.some(c => c.serverName === serverName);

  const getServerConfig = (serverName: string): McpBindingDto | undefined =>
    selectedConfigs.find(c => c.serverName === serverName);

  const toggleServer = (serverName: string) => {
    if (isServerSelected(serverName)) {
      onChange(selectedConfigs.filter(c => c.serverName !== serverName));
    } else {
      onChange([...selectedConfigs, { serverName, toolNames: [], promptNames: [] }]);
    }
  };

  const toggleTool = (serverName: string, toolName: string) => {
    const config = getServerConfig(serverName);

    // Server not yet selected: auto-select it with just this tool
    if (!config) {
      onChange([...selectedConfigs, { serverName, toolNames: [toolName], promptNames: [] }]);
      return;
    }

    const allToolNames = (toolsByServer[serverName] ?? []).map(t => t.name);
    const isAllMode = config.toolNames.length === 0;
    const currentTools = isAllMode ? [...allToolNames] : config.toolNames;

    let newToolNames: string[];
    if (isAllMode) {
      // In "all tools" mode, clicking a tool selects only that one
      newToolNames = [toolName];
    } else if (currentTools.includes(toolName)) {
      newToolNames = currentTools.filter(t => t !== toolName);
    } else {
      newToolNames = [...currentTools, toolName];
    }

    onChange(selectedConfigs.map(c =>
      c.serverName === serverName ? { ...c, toolNames: newToolNames } : c
    ));
  };

  const filteredServers = useMemo(() => {
    if (!serverSearch.trim()) return servers;
    const q = serverSearch.toLowerCase();
    return servers.filter(s => s.name.toLowerCase().includes(q));
  }, [servers, serverSearch]);

  const togglePrompt = (serverName: string, promptName: string) => {
    const config = getServerConfig(serverName);

    // Server not yet selected: auto-select it with just this prompt
    if (!config) {
      onChange([...selectedConfigs, { serverName, toolNames: [], promptNames: [promptName] }]);
      return;
    }

    const allPromptNames = (promptsByServer[serverName] ?? []).map(p => p.name);
    const currentPrompts = (config.promptNames ?? []).length === 0 ? [...allPromptNames] : config.promptNames;

    const newPromptNames = currentPrompts.includes(promptName)
      ? currentPrompts.filter(p => p !== promptName)
      : [...currentPrompts, promptName];

    onChange(selectedConfigs.map(c =>
      c.serverName === serverName ? { ...c, promptNames: newPromptNames } : c
    ));
  };

  const handleExpand = (serverName: string) => {
    if (expandedServer === serverName) {
      setExpandedServer(null);
    } else {
      setExpandedServer(serverName);
      setToolSearch('');
      loadServerTools(serverName);
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'connected': return 'bg-green-500';
      case 'failed': return 'bg-red-500';
      case 'connecting': return 'bg-blue-500';
      default: return 'bg-muted-foreground';
    }
  };

  return (
    <div className="space-y-3">
      <div>
        <label className="text-sm font-medium">{i18n('MCP Servers')}</label>
        <p className="text-xs text-muted-foreground mt-1">
          {i18n('Select MCP servers this agent can use.')}
        </p>
      </div>

      {loading ? (
        <div className="flex items-center gap-2 text-xs text-muted-foreground py-3">
          <Loader2 className="w-3.5 h-3.5 animate-spin" />
          {i18n('Loading MCP servers...')}
        </div>
      ) : servers.length === 0 ? (
        <div className="text-xs text-muted-foreground py-3">
          {i18n('No connected MCP servers. Add servers in the MCP page first.')}
        </div>
      ) : (
        <div className="space-y-1 border border-border rounded-lg">
          {/* Server search */}
          {servers.length > 1 && (
            <div className="flex items-center gap-2 px-3 py-2 border-b border-border">
              <Search className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
              <input
                type="text"
                value={serverSearch}
                onChange={e => setServerSearch(e.target.value)}
                placeholder={i18n('Search servers...')}
                className="flex-1 min-w-0 text-xs bg-transparent outline-none placeholder:text-muted-foreground"
              />
              {serverSearch && (
                <button
                  type="button"
                  onClick={() => setServerSearch('')}
                  className="text-muted-foreground hover:text-foreground"
                >
                  <X className="w-3 h-3" />
                </button>
              )}
            </div>
          )}
          <div className="divide-y divide-border">
          {filteredServers.map(server => {
            const selected = isServerSelected(server.name);
            const config = getServerConfig(server.name);
            const isExpanded = expandedServer === server.name;
            const tools = toolsByServer[server.name];
            const prompts = promptsByServer[server.name];
            const toolCount = tools?.length ?? 0;
            const selectedToolCount = config?.toolNames.length ?? 0;

            return (
              <div key={server.name}>
                {/* Server row */}
                <div className="flex items-center gap-2 px-3 py-2">
                  {/* Status dot */}
                  <span className={`w-2 h-2 rounded-full shrink-0 ${getStatusColor(server.status)}`} />

                  {/* Server checkbox */}
                  {!disabled && (
                    <input
                      type="checkbox"
                      checked={selected}
                      onChange={() => toggleServer(server.name)}
                      className="w-3.5 h-3.5 rounded border-border accent-primary shrink-0"
                    />
                  )}

                  {/* Server name - click to expand/collapse */}
                  <button
                    type="button"
                    onClick={() => handleExpand(server.name)}
                    className="flex items-center gap-1.5 flex-1 min-w-0 text-left"
                  >
                    {isExpanded
                      ? <ChevronDown className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                      : <ChevronRight className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                    }
                    <Server className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                    <span className="text-sm truncate">{server.name}</span>
                  </button>

                  {/* Tool count badge */}
                  {selected && (
                    <span className="text-xs text-muted-foreground shrink-0">
                      {selectedToolCount > 0 ? `${selectedToolCount}/${toolCount} tools` : i18n('All tools')}
                    </span>
                  )}
                </div>

                {/* Expanded tools list */}
                {isExpanded && (
                  <div className="px-8 pb-2">
                    {loadingTools === server.name ? (
                      <div className="flex items-center gap-2 text-xs text-muted-foreground py-2">
                        <Loader2 className="w-3 h-3 animate-spin" />
                        {i18n('Loading tools...')}
                      </div>
                    ) : tools && tools.length > 0 ? (
                      <div className="space-y-0.5">
                        {/* Tool search bar */}
                        {tools.length > 5 && (
                          <div className="flex items-center gap-1 py-1 px-2 border-b border-border/50 mb-1.5 pb-2">
                            <Search className="w-3 h-3 text-muted-foreground shrink-0" />
                            <input
                              type="text"
                              value={toolSearch}
                              onChange={e => setToolSearch(e.target.value)}
                              placeholder={i18n('Filter tools...')}
                              className="flex-1 min-w-0 text-xs bg-transparent outline-none placeholder:text-muted-foreground"
                            />
                            {toolSearch && (
                              <button
                                type="button"
                                onClick={() => setToolSearch('')}
                                className="text-muted-foreground hover:text-foreground shrink-0"
                              >
                                <X className="w-3 h-3" />
                              </button>
                            )}
                          </div>
                        )}
                        <p className="text-xs text-muted-foreground mb-1.5">
                          {i18n('Select tools to enable from this server.')}
                        </p>
                        {tools
                          .filter(tool => !toolSearch.trim() || tool.name.toLowerCase().includes(toolSearch.toLowerCase()))
                          .map(tool => (
                          <ToolTooltip key={tool.name} name={tool.name} description={tool.description}>
                            <label
                              className="flex items-center gap-2 py-1 px-2 rounded hover:bg-muted/50 cursor-pointer"
                            >
                              <input
                                type="checkbox"
                                checked={config?.toolNames.length === 0 || (config?.toolNames.includes(tool.name) ?? false)}
                                onChange={() => !disabled && toggleTool(server.name, tool.name)}
                                disabled={disabled}
                                className="w-3 h-3 rounded border-border accent-primary shrink-0"
                              />
                              <Wrench className="w-3 h-3 text-muted-foreground shrink-0" />
                              <span className="text-xs truncate shrink-0" style={{ maxWidth: '30ch' }}>{tool.name}</span>
                              {tool.description && (
                                <span className="text-xs text-muted-foreground truncate ml-auto" style={{ maxWidth: '40%' }}>
                                  {tool.description}
                                </span>
                              )}
                            </label>
                          </ToolTooltip>
                        ))}
                      </div>
                    ) : (
                      <p className="text-xs text-muted-foreground py-2">
                        {i18n('No tools available from this server.')}
                      </p>
                    )}
                    {/* Prompts section */}
                    {prompts && prompts.length > 0 && (
                      <div className="space-y-0.5 mt-3">
                        <p className="text-xs text-muted-foreground mb-1.5">
                          {i18n('Select prompts to enable from this server.')}
                        </p>
                        {prompts.map(prompt => (
                          <label
                            key={prompt.name}
                            className="flex items-center gap-2 py-1 px-2 rounded hover:bg-muted/50 cursor-pointer"
                          >
                            <input
                              type="checkbox"
                              checked={(config?.promptNames ?? []).length === 0 || ((config?.promptNames ?? []).includes(prompt.name))}
                              onChange={() => !disabled && togglePrompt(server.name, prompt.name)}
                              disabled={disabled}
                              className="w-3 h-3 rounded border-border accent-primary shrink-0"
                            />
                            <MessageSquare className="w-3 h-3 text-muted-foreground shrink-0" />
                            <span className="text-xs truncate" title={prompt.name}>{prompt.name}</span>
                            {prompt.description && (
                              <span className="text-xs text-muted-foreground truncate ml-auto" title={prompt.description}>
                                {prompt.description}
                              </span>
                            )}
                          </label>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
          </div>
        </div>
      )}
    </div>
  );
};
