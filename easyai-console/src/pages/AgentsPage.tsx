import React, { useEffect, useState, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAgentStore } from '@/services/stores/agent-store';
import type { AgentDto, AgentType, AgentEnv } from '@/types/agent';
import { Plus, Search, Pencil, Trash2, Bot, Loader2, Eye, Download, Upload } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { agentService } from '@/services/agent-service';
import { AgentImportDialog } from '@/components/agent/AgentImportDialog';

const AGENT_TYPE_OPTIONS: { value: AgentType | 'ALL_TYPES'; label: string }[] = [
  { value: 'ALL_TYPES', label: 'All Types' },
  { value: 'PRIMARY', label: 'Primary' },
  { value: 'SUBAGENT', label: 'Sub-Agent' },
  { value: 'ALL', label: 'ALL' },
];

const AGENT_CONTEXT_OPTIONS: { value: AgentEnv | 'ALL_CONTEXTS'; label: string }[] = [
  { value: 'ALL_CONTEXTS', label: 'All Contexts' },
  { value: 'CHAT', label: 'Chat' },
  { value: 'SWARM', label: 'Swarm' },
];

export const AgentsPage: React.FC = () => {
  const navigate = useNavigate();
  const { agents, loading, loadAgents, deleteAgent } = useAgentStore();

  const [searchQuery, setSearchQuery] = useState('');
  const [typeFilter, setTypeFilter] = useState<AgentType | 'ALL_TYPES'>('ALL_TYPES');
  const [contextFilter, setContextFilter] = useState<AgentEnv | 'ALL_CONTEXTS'>('ALL_CONTEXTS');
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [importDialogAgent, setImportDialogAgent] = useState<AgentDto | null>(null);
  const [importError, setImportError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    loadAgents();
  }, [loadAgents]);

  const allAgents: AgentDto[] = useMemo(() => agents, [agents]);

  const filteredAgents = useMemo(() => {
    return allAgents.filter(agent => {
      const matchesSearch = !searchQuery.trim() ||
        agent.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (agent.description?.toLowerCase().includes(searchQuery.toLowerCase()));
      const matchesType = typeFilter === 'ALL_TYPES' || agent.agentType === typeFilter;
      const matchesContext = contextFilter === 'ALL_CONTEXTS' || agent.agentContext === contextFilter;
      return matchesSearch && matchesType && matchesContext;
    });
  }, [allAgents, searchQuery, typeFilter, contextFilter]);

  const handleDelete = async (id: string) => {
    setDeletingId(id);
    setDeleteError(null);
    try {
      await deleteAgent(id);
      setConfirmDeleteId(null);
    } catch (err) {
      console.error('Failed to delete agent:', err);
      setDeleteError(id);
    } finally {
      setDeletingId(null);
    }
  };

  const handleExport = async (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    try {
      await agentService.exportAgent(id);
    } catch {
      // ignore
    }
  };

  const handleImportClick = () => {
    fileInputRef.current?.click();
  };

  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImportError(null);
    try {
      const agent = await agentService.parseAgentFile(file);
      setImportDialogAgent(agent);
    } catch (err) {
      setImportError(err instanceof Error ? err.message : 'Import failed');
    }
    e.target.value = '';
  };

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
        <h1 className="text-xl font-semibold">{i18n('Agents')}</h1>
        <div className="flex items-center gap-2">
          <button
            onClick={handleImportClick}
            className="flex items-center gap-2 px-4 py-2 rounded-md border border-border text-foreground text-sm font-medium hover:bg-muted transition-colors"
          >
            <Upload className="w-4 h-4" />
            {i18n('Import')}
          </button>
          <button
            onClick={() => navigate('/agents/create')}
            className="flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors"
          >
            <Plus className="w-4 h-4" />
            {i18n('Create Agent')}
          </button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json,.agent.json"
            className="hidden"
            onChange={handleImportFile}
          />
        </div>
      </div>

      {/* Toolbar: search + filter */}
      <div className="flex items-center gap-3 px-6 py-3 border-b border-border shrink-0">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={i18n('Search agents...')}
            className="w-full pl-9 pr-3 py-2 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <select
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value as AgentType | 'ALL_TYPES')}
          className="h-9 rounded-md border border-input bg-background px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
        >
          {AGENT_TYPE_OPTIONS.map(opt => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
        <select
          value={contextFilter}
          onChange={(e) => setContextFilter(e.target.value as AgentEnv | 'ALL_CONTEXTS')}
          className="h-9 rounded-md border border-input bg-background px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
        >
          {AGENT_CONTEXT_OPTIONS.map(opt => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
      </div>

      {/* Import error banner */}
      {importError && (
        <div className="mx-6 mt-3 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
          {importError}
        </div>
      )}

      {/* Agent list */}
      <div className="flex-1 overflow-y-auto p-6">
        {loading && agents.length === 0 ? (
          <div className="flex items-center justify-center h-48">
            <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
          </div>
        ) : filteredAgents.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-48 gap-3 text-muted-foreground">
            <Bot className="w-10 h-10" />
            <p className="text-sm">
              {agents.length === 0
                ? i18n('No agents yet. Create your first agent to get started.')
                : i18n('No agents match your search.')}
            </p>
            {agents.length === 0 && (
              <button
                onClick={() => navigate('/agents/create')}
                className="flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors mt-2"
              >
                <Plus className="w-4 h-4" />
                {i18n('Create Agent')}
              </button>
            )}
          </div>
        ) : (
          <div className="grid gap-3 grid-cols-1 lg:grid-cols-2 xl:grid-cols-3">
            {filteredAgents.map(agent => (
              <div
                key={agent.id}
                className="flex flex-col gap-3 p-4 rounded-lg border border-border bg-background hover:border-muted-foreground/50 transition-colors"
              >
                {/* Card header */}
                <div className="flex items-center gap-3">
                  <div className="flex-shrink-0 w-9 h-9 rounded-lg bg-muted flex items-center justify-center">
                    <Bot className="w-5 h-5 text-muted-foreground" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold truncate">{agent.name}</span>
                      {agent.builtin && (
                        <span className="text-xs text-blue-400 bg-blue-400/10 px-1.5 py-0.5 rounded shrink-0">
                          {i18n('Built-in')}
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="text-xs text-muted-foreground">{agent.agentType}</span>
                      <span className="text-xs text-muted-foreground">·</span>
                      <span className={`text-xs px-1.5 py-0.5 rounded ${
                        agent.agentContext === 'SWARM' ? 'text-orange-400 bg-orange-400/10' :
                        'text-green-400 bg-green-400/10'
                      }`}>
                        {agent.agentContext}
                      </span>
                    </div>
                  </div>
                </div>

                {/* Description */}
                {agent.description && (
                  <p className="text-xs text-muted-foreground line-clamp-2">{agent.description}</p>
                )}

                {/* Actions */}
                <div className="flex items-center gap-2 pt-1 border-t border-border mt-auto">
                  <button
                    onClick={() => navigate(`/agents/edit/${agent.id}`)}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md hover:bg-muted transition-colors"
                  >
                    {agent.builtin ? (
                      <><Eye className="w-3 h-3" />{i18n('View')}</>
                    ) : (
                      <><Pencil className="w-3 h-3" />{i18n('Edit')}</>
                    )}
                  </button>
                  <button
                    onClick={(e) => handleExport(e, agent.id)}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md hover:bg-muted transition-colors"
                    title={i18n('Export')}
                  >
                    <Download className="w-3 h-3" />
                    {i18n('Export')}
                  </button>
                  {!agent.builtin && (
                    <>
                      {confirmDeleteId === agent.id ? (
                        <div className="flex items-center gap-1.5 flex-wrap">
                          <button
                            onClick={() => handleDelete(agent.id)}
                            disabled={deletingId === agent.id}
                            className="flex items-center gap-1 px-2 py-1 text-xs rounded-md bg-destructive/10 text-destructive hover:bg-destructive/20 transition-colors disabled:opacity-50"
                          >
                            {deletingId === agent.id ? (
                              <Loader2 className="w-3 h-3 animate-spin" />
                            ) : null}
                            {i18n('Confirm')}
                          </button>
                          <button
                            onClick={() => { setConfirmDeleteId(null); setDeleteError(null); }}
                            className="px-2 py-1 text-xs rounded-md hover:bg-muted transition-colors"
                          >
                            {i18n('Cancel')}
                          </button>
                          {deleteError === agent.id && (
                            <span className="text-xs text-destructive">{i18n('Failed to delete agent')}</span>
                          )}
                        </div>
                      ) : (
                        <button
                          onClick={() => setConfirmDeleteId(agent.id)}
                          className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
                        >
                          <Trash2 className="w-3 h-3" />
                          {i18n('Delete')}
                        </button>
                      )}
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Import dialog */}
      {importDialogAgent && (
        <AgentImportDialog
          agent={importDialogAgent}
          onClose={() => setImportDialogAgent(null)}
          onImported={() => {
            setImportDialogAgent(null);
            loadAgents();
          }}
        />
      )}
    </div>
  );
};
