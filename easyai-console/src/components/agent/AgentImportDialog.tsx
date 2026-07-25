import React, { useEffect, useState } from 'react';
import type { AgentDto, AgentCreateRequest, ResourceItem } from '@/types/agent';
import { agentService } from '@/services/agent-service';
import { mcpService } from '@/services/mcp-service';
import { CommandService } from '@/services/command-service';
import { useAgentStore } from '@/services/stores/agent-store';
import { i18n } from '@/utils/i18n';
import { X, Loader2, AlertTriangle } from 'lucide-react';

interface AgentImportDialogProps {
  agent: AgentDto;
  onClose: () => void;
  onImported: () => void;
}

export const AgentImportDialog: React.FC<AgentImportDialogProps> = ({ agent, onClose, onImported }) => {
  const { agents } = useAgentStore();
  const [loading, setLoading] = useState(true);
  const [importing, setImporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [agentId, setAgentId] = useState(agent.id);

  const [tools, setTools] = useState<ResourceItem[]>([]);
  const [mcpServers, setMcpServers] = useState<ResourceItem[]>([]);
  const [skills, setSkills] = useState<ResourceItem[]>([]);
  const [subAgents, setSubAgents] = useState<ResourceItem[]>([]);
  const [commands, setCommands] = useState<ResourceItem[]>([]);

  const idConflict = agents.some(a => a.id === agentId);

  useEffect(() => {
    const loadResources = async () => {
      try {
        const [availableTools, availableSkills, availableSubAgents, availableMcpServers, availableCommands] =
          await Promise.all([
            agentService.listTools().catch(() => []),
            agentService.listSkills().catch(() => []),
            agentService.listSubAgents().catch(() => []),
            mcpService.listServers().catch(() => []),
            CommandService.fetchCommands().catch(() => []),
          ]);

        const toolNames = new Set(availableTools.map(t => t.name));
        const skillNames = new Set(availableSkills.map(s => s.name));
        const subAgentIds = new Set(availableSubAgents.map(a => a.id));
        const mcpNames = new Set(availableMcpServers.map(s => s.name));
        const commandNames = new Set(availableCommands.map(c => c.name));

        setTools((agent.toolNames ?? []).map(name => ({
          name,
          available: toolNames.has(name),
          checked: toolNames.has(name),
        })));

        setMcpServers((agent.mcpConfigs ?? []).map(cfg => ({
          name: cfg.serverName,
          available: mcpNames.has(cfg.serverName),
          checked: mcpNames.has(cfg.serverName),
        })));

        setSkills((agent.skillNames ?? []).map(name => ({
          name,
          available: skillNames.has(name),
          checked: skillNames.has(name),
        })));

        setSubAgents((agent.subAgentIds ?? []).map(id => ({
          name: id,
          available: subAgentIds.has(id),
          checked: subAgentIds.has(id),
        })));

        setCommands((agent.commandNames ?? []).map(name => ({
          name,
          available: commandNames.has(name),
          checked: commandNames.has(name),
        })));
      } finally {
        setLoading(false);
      }
    };
    loadResources();
  }, [agent]);

  const toggleItem = (list: ResourceItem[], setList: (v: ResourceItem[]) => void, name: string) => {
    setList(list.map(item => item.name === name ? { ...item, checked: !item.checked } : item));
  };

  const handleImport = async () => {
    if (idConflict) return;
    setImporting(true);
    setError(null);
    try {
      const request: AgentCreateRequest = {
        id: agentId,
        name: agent.name,
        agentType: agent.agentType,
        agentContext: agent.agentContext,
        description: agent.description ?? undefined,
        customInstructions: agent.customInstructions ?? undefined,
        promptTemplate: agent.promptTemplate ?? undefined,
        toolNames: tools.filter(t => t.checked).map(t => t.name),
        subAgentIds: subAgents.filter(s => s.checked).map(s => s.name),
        skillNames: skills.filter(s => s.checked).map(s => s.name),
        mcpConfigs: (agent.mcpConfigs ?? []).filter(cfg =>
          mcpServers.some(s => s.name === cfg.serverName && s.checked)
        ),
        commandNames: commands.filter(c => c.checked).map(c => c.name),
        maxIterations: agent.maxIterations,
        maxSubAgentDepth: agent.maxSubAgentDepth,
        color: agent.color ?? undefined,
        enabled: agent.enabled,
        instructionsEnabled: agent.instructionsEnabled,
        inputSchema: agent.inputSchema ?? undefined,
        outputSchema: agent.outputSchema ?? undefined,
      };
      await agentService.createAgent(request);
      onImported();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Import failed');
    } finally {
      setImporting(false);
    }
  };

  const renderResourceSection = (
    title: string,
    items: ResourceItem[],
    list: ResourceItem[],
    setList: (v: ResourceItem[]) => void,
  ) => {
    if (items.length === 0) return null;
    const availableCount = items.filter(i => i.available).length;
    return (
      <div className="border-t border-border pt-3 mt-3">
        <div className="text-xs font-medium text-muted-foreground mb-2">
          {title} ({availableCount}/{items.length} {i18n('available')})
        </div>
        <div className="flex flex-wrap gap-2">
          {items.map(item => (
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
                onChange={() => toggleItem(list, setList, item.name)}
                className="w-3 h-3 rounded border-border"
              />
              <span className={item.available ? '' : 'line-through'}>{item.name}</span>
              {!item.available && (
                <span className="text-destructive text-[10px]">(N/A)</span>
              )}
            </label>
          ))}
        </div>
      </div>
    );
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-background border border-border rounded-lg shadow-xl w-full max-w-lg max-h-[80vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-border shrink-0">
          <h2 className="text-sm font-semibold">{i18n('Import Agent')}</h2>
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
                  <span className="text-sm font-medium">{agent.name}</span>
                </div>
                <div className="flex items-center gap-3">
                  <label className="text-xs text-muted-foreground w-16 shrink-0">ID</label>
                  <input
                    type="text"
                    value={agentId}
                    onChange={e => setAgentId(e.target.value)}
                    className={`flex-1 px-2 py-1 text-sm rounded border ${
                      idConflict ? 'border-destructive bg-destructive/5' : 'border-input'
                    } bg-background focus:outline-none focus:ring-2 focus:ring-ring`}
                  />
                </div>
                {idConflict && (
                  <div className="flex items-center gap-1.5 text-xs text-destructive">
                    <AlertTriangle className="w-3 h-3" />
                    {i18n('An agent with this ID already exists. Please choose a different ID.')}
                  </div>
                )}
                <div className="flex items-center gap-3">
                  <label className="text-xs text-muted-foreground w-16 shrink-0">{i18n('Type')}</label>
                  <span className="text-xs px-1.5 py-0.5 rounded bg-muted">{agent.agentType}</span>
                  <span className="text-xs px-1.5 py-0.5 rounded bg-muted">{agent.agentContext}</span>
                </div>
                {agent.description && (
                  <div className="flex items-start gap-3">
                    <label className="text-xs text-muted-foreground w-16 shrink-0">{i18n('Description')}</label>
                    <span className="text-xs text-muted-foreground line-clamp-2">{agent.description}</span>
                  </div>
                )}
              </div>

              {/* Resource sections */}
              {renderResourceSection(i18n('Tools'), tools, tools, setTools)}
              {renderResourceSection(i18n('MCP Servers'), mcpServers, mcpServers, setMcpServers)}
              {renderResourceSection(i18n('Skills'), skills, skills, setSkills)}
              {renderResourceSection(i18n('Sub-Agents'), subAgents, subAgents, setSubAgents)}
              {renderResourceSection(i18n('Commands'), commands, commands, setCommands)}
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
            disabled={loading || importing || idConflict || !agentId.trim()}
            className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {importing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : i18n('Import')}
          </button>
        </div>
      </div>
    </div>
  );
};
