import React, { useEffect, useState, useMemo, useCallback } from 'react';
import { useMemoryStore } from '@/services/stores/memory-store';
import { useProjectStore } from '@/services/stores/project-store';
import { memoryService } from '@/services/memory-service';
import { MemorySidebar, type SelectedMemory } from '@/components/memories/MemorySidebar';
import { MemoryDocumentViewer } from '@/components/memories/MemoryDocumentViewer';
import { EditMemoryDialog } from '@/components/memories/EditMemoryDialog';
import { Plus, Brain, X, AlertCircle } from 'lucide-react';
import type { MemoryEntry, CreateMemoryRequest, UpdateMemoryRequest } from '@/types/memory';
import { useResizable } from '@/hooks/useResizable';
import { i18n } from '@/utils/i18n';

const SIDEBAR_MIN = 240;
const SIDEBAR_MAX = 480;

export const MemoriesPage: React.FC = () => {
  const { entries, loading, error, clearError, loadAll, loadConfig, config, createOrUpdate, updateMemory, deleteMemory, deleteAll } = useMemoryStore();
  const { currentProject, projects, updateProject } = useProjectStore();
  const [selected, setSelected] = useState<SelectedMemory | null>(null);
  const [fetchedEntry, setFetchedEntry] = useState<MemoryEntry | null>(null);
  const [editingMemory, setEditingMemory] = useState<MemoryEntry | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [sidebarWidth, setSidebarWidth] = useState(288);

  const resizable = useResizable({
    minWidth: SIDEBAR_MIN,
    maxWidth: SIDEBAR_MAX,
    direction: 'right',
    onResize: setSidebarWidth,
  });

  useEffect(() => {
    loadAll();
    loadConfig();
  }, [loadAll, loadConfig]);

  // Prefer locally loaded entry; fall back to a fetched one when missing (e.g. filters hide it)
  const localEntry = useMemo(() => {
    if (!selected) return null;
    return (
      entries.find(
        (e) =>
          e.name === selected.name &&
          e.scope === selected.scope &&
          (e.projectPath ?? null) === selected.projectPath
      ) ?? null
    );
  }, [entries, selected]);

  const displayEntry = localEntry ?? fetchedEntry;

  const handleSelect = useCallback(
    async (sel: SelectedMemory) => {
      setSelected(sel);
      setFetchedEntry(null);
      const found = entries.find(
        (e) =>
          e.name === sel.name &&
          e.scope === sel.scope &&
          (e.projectPath ?? null) === sel.projectPath
      );
      if (!found) {
        try {
          const entry = await memoryService.getMemory(sel.name, sel.scope as 'global' | 'project', sel.projectPath);
          setFetchedEntry(entry);
        } catch {
          setFetchedEntry(null);
        }
      }
    },
    [entries]
  );

  const scopeLabel = useMemo(() => {
    if (!selected) return i18n('Global');
    if (selected.projectPath == null) return i18n('Global');
    return projects.find((p) => p.path === selected.projectPath)?.name ?? selected.projectPath;
  }, [selected, projects]);

  const handleCreate = () => {
    setEditingMemory(null);
    setDialogOpen(true);
  };

  const handleEdit = (entry: MemoryEntry) => {
    setEditingMemory(entry);
    setDialogOpen(true);
  };

  const handleSaveCreate = async (request: CreateMemoryRequest) => {
    await createOrUpdate(request);
  };

  const handleSaveUpdate = async (
    name: string,
    scope: 'global' | 'project',
    projectPath: string | null,
    request: UpdateMemoryRequest
  ) => {
    await updateMemory(name, scope, projectPath, request);
  };

  const handleDelete = async (entry: MemoryEntry) => {
    try {
      await deleteMemory(entry.name, entry.scope as 'global' | 'project', entry.projectPath ?? null);
      setSelected(null);
      setFetchedEntry(null);
    } catch {
      // error already set in store
    }
  };

  const handleClearScope = async (entry: MemoryEntry) => {
    try {
      await deleteAll(entry.scope as 'global' | 'project', entry.projectPath ?? null);
      setSelected(null);
      setFetchedEntry(null);
    } catch {
      // error already set in store
    }
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
    <div className="h-full flex bg-background min-h-0">
      {/* Left sidebar */}
      <div style={{ width: sidebarWidth }} className="flex flex-col min-h-0 border-r border-border shrink-0">
        <MemorySidebar
          entries={entries}
          projects={projects}
          currentProject={currentProject}
          selected={selected}
          autoGeneration={currentProject?.memoryAutoGeneration ?? false}
          onToggleAutoGeneration={handleToggleAutoGen}
          onSelect={handleSelect}
        />
      </div>

      {/* Resize handle */}
      <div
        onMouseDown={(e) => {
          resizable.setCurrentWidth(sidebarWidth);
          resizable.onMouseDown(e);
        }}
        onTouchStart={(e) => {
          resizable.setCurrentWidth(sidebarWidth);
          resizable.onTouchStart(e);
        }}
        className="w-1 cursor-col-resize bg-border hover:bg-primary/60 transition-colors shrink-0"
        title={i18n('Collapse')}
      />

      {/* Right: viewer */}
      <div className="flex-1 min-w-0 flex flex-col min-h-0">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-2 border-b border-border shrink-0">
          <div className="flex items-center gap-2">
            <Brain className="w-4 h-4 text-primary" />
            <h1 className="text-sm font-semibold">{i18n('Memories')}</h1>
          </div>
          <button
            onClick={handleCreate}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <Plus className="w-3.5 h-3.5" />
            {i18n('Create Memory')}
          </button>
        </div>

        {/* Error banner */}
        {error && (
          <div className="flex items-center justify-between px-4 py-2 border-b border-destructive/50 bg-destructive/5 text-xs text-destructive shrink-0">
            <span className="truncate">{error}</span>
            <button onClick={clearError} className="text-destructive hover:opacity-70 shrink-0">
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        {/* Memory disabled banner */}
        {config && !config.enabled && (
          <div className="flex items-center gap-2 px-4 py-2 border-b border-amber-500/50 bg-amber-500/5 text-xs text-amber-600 shrink-0">
            <AlertCircle className="w-3.5 h-3.5 shrink-0" />
            <span>{i18n('Memory is disabled. Configure RAG in Settings to enable it.')}</span>
          </div>
        )}

        {/* Document viewer */}
        <div className="flex-1 min-h-0">
          <MemoryDocumentViewer
            entry={displayEntry}
            scopeLabel={scopeLabel}
            loading={loading}
            onEdit={() => displayEntry && handleEdit(displayEntry)}
            onDelete={() => displayEntry && handleDelete(displayEntry)}
            onClearScope={() => displayEntry && handleClearScope(displayEntry)}
          />
        </div>
      </div>

      {/* Edit dialog */}
      <EditMemoryDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onSave={handleSaveCreate}
        onUpdate={handleSaveUpdate}
        memory={editingMemory}
        currentProjectPath={currentProject?.path ?? null}
      />
    </div>
  );
};
