import React, { useEffect, useMemo, useState } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { Loader2, FolderInput } from 'lucide-react';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { readErrorMessage } from '@/services/api-client';
import { folderDisplayPath, folderLabel, foldersById } from '@/utils/knowledge-tree';
import { i18n } from '@/utils/i18n';
import type { KnowledgeArticleDto, KnowledgeFolderDto } from '@/services/knowledge-service';

interface MoveArticleDialogProps {
  open: boolean;
  article: KnowledgeArticleDto | null;
  folders: KnowledgeFolderDto[];
  onClose: () => void;
}

export const MoveArticleDialog: React.FC<MoveArticleDialogProps> = ({
  open,
  article,
  folders,
  onClose,
}) => {
  const moveArticle = useKnowledgeStore((s) => s.moveArticle);
  const [target, setTarget] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const byId = useMemo(() => foldersById(folders), [folders]);
  // A move may only stay inside the article's own bucket; the other visible bucket is read-only.
  const targets = useMemo(
    () => folders.filter((f) => article != null && f.ownerId === article.ownerId),
    [folders, article],
  );

  useEffect(() => {
    if (!open) return;
    setTarget(article?.folderId ?? '');
    setSubmitting(false);
    setError(null);
  }, [open, article]);

  const handleSubmit = async () => {
    if (!article || !target || submitting) return;
    if (target === article.folderId) {
      onClose();
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await moveArticle({ id: article.id, folderId: target });
      onClose();
    } catch (e) {
      setError(readErrorMessage(e, i18n('Move failed')));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} title={i18n('Move Article')}>
      <div className="space-y-4">
        <div className="text-sm text-muted-foreground truncate">
          {article?.title || article?.id}
        </div>
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Destination Folder')}</label>
          <select
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
          >
            {targets.map((f) => (
              <option key={f.id} value={f.id}>
                {folderDisplayPath(f.id, byId, (item) => folderLabel(item, i18n)) || folderLabel(f, i18n)}
              </option>
            ))}
          </select>
        </div>

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
            disabled={submitting || !target || target === article?.folderId}
            className="flex items-center gap-1.5 px-4 py-2 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? <Loader2 className="size-3.5 animate-spin" /> : <FolderInput className="size-3.5" />}
            {i18n('Move')}
          </button>
        </div>
      </div>
    </Dialog>
  );
};
