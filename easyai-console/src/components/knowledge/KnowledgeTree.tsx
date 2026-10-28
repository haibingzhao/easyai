import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ChevronRight, ChevronDown, Folder as FolderIcon, FolderPlus, Upload,
  Pencil, Trash2, Plus, Loader2, X, Files,
} from 'lucide-react';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { useCapabilityStore, selectCanManage } from '@/services/stores/capability-store';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { FolderDialog } from '@/components/knowledge/FolderDialog';
import { UploadKnowledgeDialog } from '@/components/knowledge/UploadKnowledgeDialog';
import { readErrorMessage } from '@/services/api-client';
import { buildFolderTree, collectSubtreeIds, folderLabel, type FolderTreeNode } from '@/utils/knowledge-tree';
import { i18n } from '@/utils/i18n';
import type { KnowledgeFolderDto } from '@/services/knowledge-service';

interface KnowledgeTreeProps {
  selectedFolderId: string | null;
  onSelect: (folderId: string | null) => void;
}

export const KnowledgeTree: React.FC<KnowledgeTreeProps> = ({ selectedFolderId, onSelect }) => {
  const folders = useKnowledgeStore((s) => s.folders);
  const articles = useKnowledgeStore((s) => s.articles);
  const folderLoading = useKnowledgeStore((s) => s.folderLoading);
  const folderError = useKnowledgeStore((s) => s.folderError);
  const clearFolderError = useKnowledgeStore((s) => s.clearFolderError);
  const loadFolders = useKnowledgeStore((s) => s.loadFolders);
  const deleteFolder = useKnowledgeStore((s) => s.deleteFolder);
  const indexingIds = useKnowledgeStore((s) => s.indexingIds);
  const indexingProgress = useKnowledgeStore((s) => s.indexingProgress);
  const stopIndexingPoll = useKnowledgeStore((s) => s.stopIndexingPoll);
  const canManage = useCapabilityStore(selectCanManage('knowledge'));

  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [uploadOpen, setUploadOpen] = useState(false);
  const [folderDialog, setFolderDialog] = useState<{
    mode: 'create' | 'rename';
    parentId: string | null;
    folder: KnowledgeFolderDto | null;
  } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<KnowledgeFolderDto | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    void loadFolders().catch(() => undefined);
  }, [loadFolders]);

  const tree = useMemo(() => buildFolderTree(folders), [folders]);

  // Articles per folder subtree, so a parent shows the roll-up of everything beneath it.
  const counts = useMemo(() => {
    const map = new Map<string, number>();
    for (const folder of folders) {
      const subtree = new Set(collectSubtreeIds(folder.id, folders));
      let n = 0;
      for (const a of articles) if (subtree.has(a.folderId)) n++;
      map.set(folder.id, n);
    }
    return map;
  }, [folders, articles]);

  const toggleExpand = useCallback((id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const handleDelete = useCallback(async () => {
    if (!deleteTarget) return;
    setActionError(null);
    try {
      await deleteFolder(deleteTarget.id);
      if (selectedFolderId === deleteTarget.id) onSelect(null);
    } catch (e) {
      setActionError(readErrorMessage(e, i18n('Delete failed')));
    } finally {
      setDeleteTarget(null);
    }
  }, [deleteTarget, deleteFolder, selectedFolderId, onSelect]);

  const renderNode = (node: FolderTreeNode, depth: number): React.ReactNode => {
    const { folder } = node;
    const hasChildren = node.children.length > 0;
    const isOpen = expanded.has(folder.id);
    const selected = selectedFolderId === folder.id;
    const count = counts.get(folder.id) ?? 0;

    return (
      <div key={folder.id}>
        <div
          className={`group flex items-center gap-1 pr-1 rounded-md transition-colors ${
            selected ? 'bg-primary/10 text-primary' : 'hover:bg-muted/60 text-foreground'
          }`}
          style={{ paddingLeft: `${depth * 12 + 4}px` }}
        >
          <button
            onClick={() => (hasChildren ? toggleExpand(folder.id) : onSelect(folder.id))}
            className="p-0.5 shrink-0 text-muted-foreground hover:text-foreground"
          >
            {hasChildren
              ? (isOpen ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />)
              : <span className="size-3.5" />}
          </button>
          <button
            onClick={() => onSelect(folder.id)}
            className="flex-1 min-w-0 flex items-center gap-1.5 py-1 text-left"
          >
            <FolderIcon className="size-3.5 shrink-0 text-muted-foreground" />
            <span className="text-xs truncate flex-1">{folderLabel(folder, i18n)}</span>
            {count > 0 && (
              <span className="text-[10px] text-muted-foreground shrink-0">{count}</span>
            )}
          </button>
          {canManage && (
            <div className="flex items-center gap-0.5 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 transition-opacity shrink-0">
              <button
                onClick={() => setFolderDialog({ mode: 'create', parentId: folder.id, folder: null })}
                className="p-0.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
                title={i18n('New Subfolder')}
              >
                <Plus className="size-3" />
              </button>
              <button
                onClick={() => setFolderDialog({ mode: 'rename', parentId: folder.parentId, folder })}
                className="p-0.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
                title={i18n('Rename')}
              >
                <Pencil className="size-3" />
              </button>
              {!folder.isDefault && (
                <button
                  onClick={() => setDeleteTarget(folder)}
                  className="p-0.5 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive"
                  title={i18n('Delete')}
                >
                  <Trash2 className="size-3" />
                </button>
              )}
            </div>
          )}
        </div>
        {hasChildren && isOpen && (
          <div>{node.children.map((child) => renderNode(child, depth + 1))}</div>
        )}
      </div>
    );
  };

  return (
    <div className="flex flex-col h-full min-h-0">
      {/* Actions */}
      <div className="px-3 pt-3 pb-2 border-b border-border flex items-center gap-2">
        <button
          onClick={() => setUploadOpen(true)}
          className="flex-1 flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
        >
          <Upload className="size-3.5" />
          {i18n('Upload')}
        </button>
        {canManage && (
          <button
            onClick={() => setFolderDialog({ mode: 'create', parentId: null, folder: null })}
            className="flex items-center justify-center gap-1 px-2 py-1.5 rounded-md text-xs border border-border hover:bg-muted transition-colors"
            title={i18n('New Folder')}
          >
            <FolderPlus className="size-3.5" />
          </button>
        )}
      </div>

      {/* Indexing progress */}
      {indexingProgress && indexingIds.size > 0 && (
        <div className="px-3 py-2 border-b border-border">
          <div className="flex items-center gap-2 mb-1">
            <Loader2 className="size-3 animate-spin text-primary shrink-0" />
            <span className="text-[11px] text-muted-foreground flex-1 min-w-0">
              {i18n('Indexing')} {indexingProgress.done}/{indexingProgress.total}
            </span>
            <button onClick={stopIndexingPoll} className="text-muted-foreground hover:text-foreground shrink-0" title={i18n('Dismiss')}>
              <X className="size-3" />
            </button>
          </div>
          <div className="h-1 rounded-full bg-muted overflow-hidden">
            <div
              className="h-full bg-primary rounded-full transition-all duration-500"
              style={{ width: `${indexingProgress.total > 0 ? (indexingProgress.done / indexingProgress.total) * 100 : 0}%` }}
            />
          </div>
        </div>
      )}

      {(actionError || folderError) && (
        <div className="flex items-center justify-between gap-2 px-3 py-1.5 border-b border-destructive/40 bg-destructive/5 text-[11px] text-destructive">
          <span className="truncate">{actionError ?? folderError}</span>
          {folderError && !actionError && (
            <button
              onClick={() => { void loadFolders().catch(() => undefined); }}
              className="shrink-0 underline underline-offset-2 hover:opacity-70"
            >
              {i18n('Retry')}
            </button>
          )}
          <button
            onClick={() => { setActionError(null); clearFolderError(); }}
            className="hover:opacity-70 shrink-0"
          >
            <X className="size-3" />
          </button>
        </div>
      )}

      {/* Tree */}
      <div className="flex-1 min-h-0 overflow-y-auto px-2 py-2">
        <button
          onClick={() => onSelect(null)}
          className={`w-full flex items-center gap-1.5 px-2 py-1.5 rounded-md text-left transition-colors mb-0.5 ${
            selectedFolderId === null ? 'bg-primary/10 text-primary' : 'hover:bg-muted/60 text-foreground'
          }`}
        >
          <Files className="size-3.5 shrink-0 text-muted-foreground" />
          <span className="text-xs font-medium flex-1 truncate">{i18n('All Articles')}</span>
          <span className="text-[10px] text-muted-foreground shrink-0">{articles.length}</span>
        </button>

        {folderLoading && folders.length === 0 && (
          <div className="flex items-center justify-center py-6">
            <Loader2 className="size-4 animate-spin text-muted-foreground" />
          </div>
        )}

        {tree.map((node) => renderNode(node, 0))}
      </div>

      <UploadKnowledgeDialog
        open={uploadOpen}
        initialFolderId={selectedFolderId}
        onClose={() => setUploadOpen(false)}
      />

      {folderDialog && (
        <FolderDialog
          open
          mode={folderDialog.mode}
          parentId={folderDialog.parentId}
          folder={folderDialog.folder}
          folders={folders}
          onClose={() => setFolderDialog(null)}
        />
      )}

      <ConfirmDialog
        open={!!deleteTarget}
        message={i18n('Delete this folder? It must be empty (no subfolders or articles).')}
        danger
        confirmLabel={i18n('Delete')}
        onConfirm={() => { void handleDelete(); }}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
};
