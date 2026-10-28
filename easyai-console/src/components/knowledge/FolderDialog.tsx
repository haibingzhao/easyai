import React, { useEffect, useMemo, useState } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { Loader2, FolderPlus } from 'lucide-react';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { readErrorMessage } from '@/services/api-client';
import { folderDisplayPath, folderLabel, foldersById } from '@/utils/knowledge-tree';
import { i18n } from '@/utils/i18n';
import type { KnowledgeFolderDto } from '@/services/knowledge-service';

type FolderDialogMode = 'create' | 'rename';

interface FolderDialogProps {
  open: boolean;
  mode: FolderDialogMode;
  /** Parent for a new folder; null creates a root. Ignored in rename mode. */
  parentId?: string | null;
  /** Folder being renamed; required in rename mode. */
  folder?: KnowledgeFolderDto | null;
  folders: KnowledgeFolderDto[];
  onClose: () => void;
}

export const FolderDialog: React.FC<FolderDialogProps> = ({
  open,
  mode,
  parentId = null,
  folder = null,
  folders,
  onClose,
}) => {
  const createFolder = useKnowledgeStore((s) => s.createFolder);
  const renameFolder = useKnowledgeStore((s) => s.renameFolder);

  const [name, setName] = useState('');
  const [parent, setParent] = useState<string>(parentId ?? '');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const byId = useMemo(() => foldersById(folders), [folders]);

  useEffect(() => {
    if (!open) return;
    setName(mode === 'rename' && folder ? folder.name : '');
    setParent(parentId ?? '');
    setSubmitting(false);
    setError(null);
  }, [open, mode, folder, parentId]);

  const handleSubmit = async () => {
    const trimmed = name.trim();
    if (!trimmed || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      if (mode === 'create') {
        await createFolder({ parentId: parent || null, name: trimmed });
      } else if (folder) {
        await renameFolder(folder.id, trimmed);
      }
      onClose();
    } catch (e) {
      setError(readErrorMessage(e, i18n('Operation failed')));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={mode === 'create' ? i18n('New Folder') : i18n('Rename Folder')}
    >
      <div className="space-y-4">
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Name')}</label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') void handleSubmit(); }}
            placeholder={i18n('Folder name')}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            autoFocus
          />
        </div>

        {mode === 'create' && (
          <div>
            <label className="block text-sm font-medium mb-1">{i18n('Parent Folder')}</label>
            <select
              value={parent}
              onChange={(e) => setParent(e.target.value)}
              className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            >
              <option value="">{i18n('Root (no parent)')}</option>
              {/* A folder in the read-only bucket cannot host new children (the create targets the write bucket). */}
              {folders.filter((f) => f.writable).map((f) => (
                <option key={f.id} value={f.id}>
                  {folderDisplayPath(f.id, byId, (item) => folderLabel(item, i18n)) || folderLabel(f, i18n)}
                </option>
              ))}
            </select>
          </div>
        )}

        {error && <div className="text-sm text-destructive">{error}</div>}

        <div className="flex justify-end gap-2 pt-2">
          <button
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 rounded-md text-sm border border-border hover:bg-muted transition-colors disabled:opacity-50"
          >
            {i18n('Cancel')}
          </button>
          <button
            onClick={() => { void handleSubmit(); }}
            disabled={submitting || !name.trim()}
            className="flex items-center gap-1.5 px-4 py-2 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? <Loader2 className="size-3.5 animate-spin" /> : <FolderPlus className="size-3.5" />}
            {mode === 'create' ? i18n('Create') : i18n('Save')}
          </button>
        </div>
      </div>
    </Dialog>
  );
};
