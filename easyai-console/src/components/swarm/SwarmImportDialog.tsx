import React, { useEffect, useState } from 'react';
import type { PresetRequest, SwarmAgentSpecDto } from '@/services/swarm-service';
import type { ResourceItem } from '@/types/agent';
import { swarmService } from '@/services/swarm-service';
import { agentService } from '@/services/agent-service';
import { mcpService } from '@/services/mcp-service';
import { useSwarmStore } from '@/services/stores/swarm-store';
import { i18n } from '@/utils/i18n';
import { X, Loader2, AlertTriangle } from 'lucide-react';

interface SwarmImportDialogProps {
  preset: PresetRequest;
  onClose: () => void;
  onImported: () => void;
}

interface AgentResourceState {
  tools: ResourceItem[];
  mcpServers: ResourceItem[];
}

export const SwarmImportDialog: React.FC<SwarmImportDialogProps> = ({ preset, onClose, onImported }) => {
  const { presets } = useSwarmStore();
  const [loading, setLoading] = useState(true);
  const [importing, setImporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [presetName, setPresetName] = useState(preset.name);
  const [agentResources, setAgentResources] = useState<Record<string, AgentResourceState>>({});

  const nameConflict = presets.some(p => p.name === presetName);
  const inlineAgents = preset.agents.filter(a => !a.agentDefinitionId);

  useEffect(() => {
    const loadResources = async () => {
      try {
        const [availableTools, availableMcpServers] = await Promise.all([
          agentService.listTools().catch(() => []),
          mcpService.listServers().catch(() => []),
        ]);

        const toolNames = new Set(availableTools.map(t => t.name));
        const mcpNames = new Set(availableMcpServers.map(s => s.name));

        const state: Record<string, AgentResourceState> = {};
        for (const agent of inlineAgents) {
          state[agent.id] = {
            tools: (agent.toolNames ?? []).map(name => ({
              name,
              available: toolNames.has(name),
              checked: toolNames.has(name),
            })),
            mcpServers: (agent.mcpConfigs ?? []).map(cfg => ({
              name: cfg.serverName,
              available: mcpNames.has(cfg.serverName),
              checked: mcpNames.has(cfg.serverName),
            })),
          };
        }
        setAgentResources(state);
      } finally {
        setLoading(false);
      }
    };
    loadResources();
  }, [preset]);

  const toggleTool = (agentId: string, toolName: string) => {
    setAgentResources(prev => ({
      ...prev,
      [agentId]: {
        ...prev[agentId],
        tools: prev[agentId].tools.map(t =>
          t.name === toolName ? { ...t, checked: !t.checked } : t
        ),
      },
    }));
  };

  const toggleMcp = (agentId: string, serverName: string) => {
    setAgentResources(prev => ({
      ...prev,
      [agentId]: {
        ...prev[agentId],
        mcpServers: prev[agentId].mcpServers.map(s =>
          s.name === serverName ? { ...s, checked: !s.checked } : s
        ),
      },
    }));
  };

  const handleImport = async () => {
    if (nameConflict) return;
    setImporting(true);
    setError(null);
    try {
      // Build filtered agents: only include checked tools/mcpConfigs
      const filteredAgents: SwarmAgentSpecDto[] = preset.agents.map(agent => {
        if (agent.agentDefinitionId) return agent; // global agent ref, keep as-is
        const res = agentResources[agent.id];
        if (!res) return agent;
        return {
          ...agent,
          toolNames: res.tools.filter(t => t.checked).map(t => t.name),
          mcpConfigs: (agent.mcpConfigs ?? []).filter(cfg =>
            res.mcpServers.some(s => s.name === cfg.serverName && s.checked)
          ),
        };
      });

      await swarmService.importPresetData({
        ...preset,
        name: presetName,
        agents: filteredAgents,
      });
      onImported();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Import failed');
    } finally {
      setImporting(false);
    }
  };

  const renderResourceCheckbox = (
    item: ResourceItem,
    onToggle: () => void,
  ) => (
    <label
      key={item.name}
      className={`flex items-center gap-1.5 px-2 py-1 rounded text-xs border ${
        item.available
          ? 'border-border cursor-pointer hover:bg-muted'
          : 'border-destructive/30 bg-destructive/5 text-muted-foreground cursor-not-allowed'
      }`}
    >
      <input
        type="checkbox"
        checked={item.checked}
        disabled={!item.available}
        onChange={onToggle}
        className="w-3 h-3 rounded border-border"
      />
      <span className={item.available ? '' : 'line-through'}>{item.name}</span>
      {!item.available && <span className="text-destructive text-[10px]">(N/A)</span>}
    </label>
  );

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-background border border-border rounded-lg shadow-xl w-full max-w-lg max-h-[80vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-border shrink-0">
          <h2 className="text-sm font-semibold">{i18n('Import Workflow Preset')}</h2>
          <button onClick={onClose} className="p-1 rounded hover:bg-muted text-muted-foreground">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="w-5 h-5 animate-spin text-muted-foreground" />
            </div>
          ) : (
            <>
              {/* Basic info */}
              <div className="space-y-2">
                <div className="flex items-center gap-3">
                  <label className="text-xs text-muted-foreground w-16 shrink-0">{i18n('Name')}</label>
                  <input
                    type="text"
                    value={presetName}
                    onChange={e => setPresetName(e.target.value)}
                    className={`flex-1 px-2 py-1 text-sm rounded border ${
                      nameConflict ? 'border-destructive bg-destructive/5' : 'border-input'
                    } bg-background focus:outline-none focus:ring-2 focus:ring-ring`}
                  />
                </div>
                {nameConflict && (
                  <div className="flex items-center gap-1.5 text-xs text-destructive">
                    <AlertTriangle className="w-3 h-3" />
                    {i18n('A preset with this name already exists. Please choose a different name.')}
                  </div>
                )}
                <div className="flex items-center gap-3">
                  <label className="text-xs text-muted-foreground w-16 shrink-0">{i18n('Title')}</label>
                  <span className="text-sm font-medium">{preset.title}</span>
                </div>
                <div className="flex items-center gap-4 text-xs text-muted-foreground">
                  <span>{preset.tasks.length} tasks</span>
                  <span>{preset.agents.length} agents</span>
                  {(preset.variables?.length ?? 0) > 0 && <span>{preset.variables!.length} variables</span>}
                </div>
              </div>

              {/* Per-agent resource sections */}
              {inlineAgents.map(agent => {
                const res = agentResources[agent.id];
                if (!res) return null;
                const hasResources = res.tools.length > 0 || res.mcpServers.length > 0;
                if (!hasResources) return null;
                const toolsAvailable = res.tools.filter(t => t.available).length;
                const mcpAvailable = res.mcpServers.filter(s => s.available).length;
                return (
                  <div key={agent.id} className="border-t border-border pt-3 mt-3">
                    <div className="text-xs font-medium mb-2">
                      {i18n('Agent')}: "{agent.name || agent.id}"
                    </div>
                    {res.tools.length > 0 && (
                      <div className="mb-2">
                        <div className="text-xs text-muted-foreground mb-1">
                          {i18n('Tools')} ({toolsAvailable}/{res.tools.length} {i18n('available')})
                        </div>
                        <div className="flex flex-wrap gap-2">
                          {res.tools.map(item =>
                            renderResourceCheckbox(item, () => toggleTool(agent.id, item.name))
                          )}
                        </div>
                      </div>
                    )}
                    {res.mcpServers.length > 0 && (
                      <div>
                        <div className="text-xs text-muted-foreground mb-1">
                          {i18n('MCP Servers')} ({mcpAvailable}/{res.mcpServers.length} {i18n('available')})
                        </div>
                        <div className="flex flex-wrap gap-2">
                          {res.mcpServers.map(item =>
                            renderResourceCheckbox(item, () => toggleMcp(agent.id, item.name))
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-4 border-t border-border shrink-0">
          {error && <span className="text-xs text-destructive mr-auto">{error}</span>}
          <button
            onClick={onClose}
            className="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-muted transition-colors"
          >
            {i18n('Cancel')}
          </button>
          <button
            onClick={handleImport}
            disabled={loading || importing || nameConflict || !presetName.trim()}
            className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {importing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : i18n('Import')}
          </button>
        </div>
      </div>
    </div>
  );
};
