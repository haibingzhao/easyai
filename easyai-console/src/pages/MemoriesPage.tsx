import React, { useEffect, useState, useMemo, useCallback } from 'react';
import { useMemoryStore } from '@/services/stores/memory-store';
import { useProjectStore } from '@/services/stores/project-store';
import { EditMemoryDialog } from '@/components/memories/EditMemoryDialog';
import { ChevronDown, ChevronRight, Trash2, Copy, Pencil, Plus, Brain, X } from 'lucide-react';
import type { MemoryEntry, CreateMemoryRequest } from '@/types/memory';
import { i18n } from '@/utils/i18n';

type ScopeFilter = 'all' | 'global' | 'project';

const MEMORY_TYPE_ORDER = ['user', 'feedback', 'project', 'reference'] as const;

const TYPE_LABEL_KEYS: Record<string, string> = {
  user: 'User',
  feedback: 'Feedback',
  project: 'Project',
  reference: 'Reference',
};

export const MemoriesPage: React.FC = () => {
  const { memories, loading, error, clearError, loadMemories, createOrUpdate, deleteMemory, deleteAll } = useMemoryStore();
  const { currentProject, updateProject } = useProjectStore();
  const [scopeFilter, setScopeFilter] = useState<ScopeFilter>('all');
  const [editingMemory, setEditingMemory] = useState<MemoryEntry | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({
    user: true,
    feedback: true,
    project: true,
    reference: true,
  });

  useEffect(() => {
    loadMemories();
  }, [loadMemories]);

  // Filter memories by scope
  const filteredMemories = useMemo(() => {
    if (scopeFilter === 'all') return memories;
    return memories.filter((m) => m.scope === scopeFilter);
  }, [memories, scopeFilter]);

  // Group by type
  const groupedByType = useMemo(() => {
    const groups: Record<string, MemoryEntry[]> = {};
    for (const type of MEMORY_TYPE_ORDER) {
      groups[type] = [];
    }
    for (const mem of filteredMemories) {
      const type = mem.type.toLowerCase();
      if (!groups[type]) groups[type] = [];
      groups[type].push(mem);
    }
    return groups;
  }, [filteredMemories]);

  const toggleGroup = useCallback((type: string) => {
    setExpandedGroups((prev) => ({ ...prev, [type]: !prev[type] }));
  }, []);

  const handleClearAll = async (scope: 'global' | 'project') => {
    if (!window.confirm(i18n('Are you sure to clear all memories?'))) return;
    try {
      await deleteAll(scope);
    } catch {
      // error already set in store
    }
  };

  const handleDelete = async (mem: MemoryEntry) => {
    if (!window.confirm(i18n('Are you sure to delete this memory?'))) return;
    try {
      await deleteMemory(mem.name, mem.scope as 'global' | 'project');
    } catch {
      // error already set in store
    }
  };

  const handleCopy = async (mem: MemoryEntry) => {
    await navigator.clipboard.writeText(mem.content);
    // Brief visual feedback could be added here
  };

  const handleEdit = (mem: MemoryEntry) => {
    setEditingMemory(mem);
    setDialogOpen(true);
  };

  const handleCreate = () => {
    setEditingMemory(null);
    setDialogOpen(true);
  };

  const handleSave = async (request: CreateMemoryRequest) => {
    await createOrUpdate(request);
  };

  const handleToggleAutoGen = async (enabled: boolean) => {
    if (!currentProject) return;
    try {
      await updateProject(currentProject.id, { memoryAutoGeneration: enabled });
    } catch {
      // error already set in store
    }
  };

  return (
    <div className="h-full overflow-y-auto bg-background">
      <div className="max-w-4xl mx-auto p-6 space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Brain className="w-6 h-6 text-primary" />
            <h1 className="text-xl font-semibold">{i18n('Memories')}</h1>
          </div>
          <button
            onClick={handleCreate}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <Plus className="w-4 h-4" />
            {i18n('Create Memory')}
          </button>
        </div>

        {/* Error banner */}
        {error && (
          <div className="flex items-center justify-between p-3 rounded-lg border border-destructive/50 bg-destructive/5 text-sm text-destructive">
            <span>{error}</span>
            <button onClick={clearError} className="text-destructive hover:opacity-70">
              <X className="w-4 h-4" />
            </button>
          </div>
        )}

        {/* Auto-generation toggle */}
        {currentProject && (
          <div className="flex items-center justify-between p-4 rounded-lg border border-border bg-muted/30">
            <div>
              <div className="font-medium text-sm">{i18n('Enable Automatic Generation')}</div>
              <div className="text-xs text-muted-foreground mt-0.5">
                {i18n('Automatically build memories from conversations')}
              </div>
            </div>
            <button
              onClick={() => handleToggleAutoGen(!currentProject.memoryAutoGeneration)}
              className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                currentProject.memoryAutoGeneration ? 'bg-green-500' : 'bg-muted-foreground/30'
              }`}
            >
              <span
                className={`inline-block h-4 w-4 rounded-full bg-white transition-transform ${
                  currentProject.memoryAutoGeneration ? 'translate-x-6' : 'translate-x-1'
                }`}
              />
            </button>
          </div>
        )}

        {/* Scope filter tabs */}
        <div className="flex items-center justify-between">
          <div className="flex gap-1">
            {(['all', 'global', 'project'] as const).map((scope) => (
              <button
                key={scope}
                onClick={() => setScopeFilter(scope)}
                className={`px-3 py-1.5 rounded-md text-sm transition-colors ${
                  scopeFilter === scope
                    ? 'bg-primary text-primary-foreground'
                    : 'hover:bg-muted text-muted-foreground'
                }`}
              >
                {scope === 'all'
                  ? i18n('All')
                  : scope === 'global'
                    ? i18n('Global')
                    : i18n('Current Project')}
              </button>
            ))}
          </div>

          {/* Clear All buttons */}
          <div className="flex gap-2">
            {(scopeFilter === 'all' || scopeFilter === 'global') && (
              <button
                onClick={() => handleClearAll('global')}
                className="flex items-center gap-1 px-2 py-1 rounded text-xs text-destructive hover:bg-destructive/10 transition-colors"
              >
                <Trash2 className="w-3 h-3" />
                {i18n('Clear All')} ({i18n('Global')})
              </button>
            )}
            {(scopeFilter === 'all' || scopeFilter === 'project') && (
              <button
                onClick={() => handleClearAll('project')}
                className="flex items-center gap-1 px-2 py-1 rounded text-xs text-destructive hover:bg-destructive/10 transition-colors"
              >
                <Trash2 className="w-3 h-3" />
                {i18n('Clear All')} ({i18n('Current Project')})
              </button>
            )}
          </div>
        </div>

        {/* Loading state */}
        {loading && (
          <div className="text-center text-sm text-muted-foreground py-8">
            {i18n('Loading...')}
          </div>
        )}

        {/* Memory groups by type */}
        {!loading && (
          <div className="space-y-4">
            {MEMORY_TYPE_ORDER.map((type) => {
              const items = groupedByType[type] || [];
              const isExpanded = expandedGroups[type] ?? true;

              return (
                <div key={type} className="rounded-lg border border-border overflow-hidden">
                  {/* Group header */}
                  <button
                    onClick={() => toggleGroup(type)}
                    className="w-full flex items-center justify-between px-4 py-3 bg-muted/50 hover:bg-muted transition-colors"
                  >
                    <div className="flex items-center gap-2">
                      {isExpanded ? (
                        <ChevronDown className="w-4 h-4 text-muted-foreground" />
                      ) : (
                        <ChevronRight className="w-4 h-4 text-muted-foreground" />
                      )}
                      <span className="font-medium text-sm">
                        {i18n(TYPE_LABEL_KEYS[type])}
                      </span>
                      <span className="text-xs text-muted-foreground bg-muted rounded-full px-2 py-0.5">
                        {items.length}
                      </span>
                    </div>
                  </button>

                  {/* Group items */}
                  {isExpanded && (
                    <div className="divide-y divide-border">
                      {items.length === 0 ? (
                        <div className="px-4 py-3 text-sm text-muted-foreground">
                          {i18n('No memories yet')}
                        </div>
                      ) : (
                        items.map((mem) => (
                          <MemoryItem
                            key={`${mem.scope}-${mem.name}`}
                            memory={mem}
                            onEdit={() => handleEdit(mem)}
                            onDelete={() => handleDelete(mem)}
                            onCopy={() => handleCopy(mem)}
                          />
                        ))
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* Empty state */}
        {!loading && filteredMemories.length === 0 && (
          <div className="text-center py-12 text-muted-foreground">
            <Brain className="w-12 h-12 mx-auto mb-3 opacity-30" />
            <p className="text-sm">{i18n('No memories yet')}</p>
          </div>
        )}
      </div>

      {/* Edit dialog */}
      <EditMemoryDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onSave={handleSave}
        memory={editingMemory}
      />
    </div>
  );
};

/** Individual memory item with expand/collapse */
const MemoryItem: React.FC<{
  memory: MemoryEntry;
  onEdit: () => void;
  onDelete: () => void;
  onCopy: () => void;
}> = ({ memory, onEdit, onDelete, onCopy }) => {
  const [expanded, setExpanded] = useState(false);
  const isGlobal = memory.scope === 'global';

  return (
    <div className="px-4 py-2.5">
      <div className="flex items-center justify-between">
        <button
          onClick={() => setExpanded(!expanded)}
          className="flex items-center gap-2 flex-1 min-w-0 text-left"
        >
          {expanded ? (
            <ChevronDown className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          ) : (
            <ChevronRight className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          )}
          <span className="text-sm font-medium truncate">{memory.name}</span>
          <span
            className={`shrink-0 px-1.5 py-0.5 rounded text-[10px] leading-none font-medium ${
              isGlobal
                ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                : 'bg-blue-500/10 text-blue-600 dark:text-blue-400'
            }`}
          >
            {isGlobal ? i18n('Global') : i18n('Current Project')}
          </span>
        </button>
        <div className="flex items-center gap-1 shrink-0 ml-2">
          <button
            onClick={onEdit}
            className="p-1 rounded hover:bg-muted transition-colors"
            title={i18n('Edit')}
          >
            <Pencil className="w-3.5 h-3.5 text-muted-foreground" />
          </button>
          <button
            onClick={onCopy}
            className="p-1 rounded hover:bg-muted transition-colors"
            title={i18n('Copy')}
          >
            <Copy className="w-3.5 h-3.5 text-muted-foreground" />
          </button>
          <button
            onClick={onDelete}
            className="p-1 rounded hover:bg-destructive/10 transition-colors"
            title={i18n('Delete')}
          >
            <Trash2 className="w-3.5 h-3.5 text-destructive" />
          </button>
        </div>
      </div>

      {/* Expanded details */}
      {expanded && (
        <div className="mt-2 ml-5.5 space-y-1.5 text-sm">
          {memory.description && (
            <div className="text-muted-foreground text-xs">{memory.description}</div>
          )}
          {memory.keywords.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {memory.keywords.map((kw) => (
                <span
                  key={kw}
                  className="px-1.5 py-0.5 rounded text-[10px] bg-muted border border-border"
                >
                  {kw}
                </span>
              ))}
            </div>
          )}
          <pre className="whitespace-pre-wrap text-xs bg-muted/50 rounded p-2 border border-border overflow-x-auto">
            {memory.content}
          </pre>
        </div>
      )}
    </div>
  );
};
