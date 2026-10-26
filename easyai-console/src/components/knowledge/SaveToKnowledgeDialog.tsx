import React, { useState, useEffect, useMemo } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { Bookmark } from 'lucide-react';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { readErrorMessage } from '@/services/api-client';
import { i18n } from '@/utils/i18n';

interface SaveToKnowledgeDialogProps {
  open: boolean;
  onClose: () => void;
  content: string;
  sessionId?: string;
  messageId?: string;
}

export const SaveToKnowledgeDialog: React.FC<SaveToKnowledgeDialogProps> = ({
  open,
  onClose,
  content,
  sessionId,
  messageId,
}) => {
  const saveToKnowledge = useKnowledgeStore((s) => s.saveToKnowledge);

  const [title, setTitle] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedTitle, setSavedTitle] = useState<string | null>(null);

  const preview = useMemo(() => {
    if (content.length <= 300) return content;
    return content.slice(0, 300) + '...';
  }, [content]);

  useEffect(() => {
    if (open) {
      const firstLine = content.split('\n').find((l) => l.trim().length > 0) ?? '';
      const cleaned = firstLine
        .replace(/^#{1,6}\s+/, '')
        .replace(/^[-*+]\s+/, '')
        .trim();
      setTitle(cleaned.slice(0, 80));
      setSaving(false);
      setError(null);
      setSavedTitle(null);
    }
  }, [open, content]);

  const handleSave = async () => {
    if (!title.trim() || !content.trim() || saving) return;
    setSaving(true);
    setError(null);
    try {
      const article = await saveToKnowledge({
        title: title.trim(),
        content,
        originSessionId: sessionId,
        originMessageId: messageId,
      });
      setSavedTitle(article.title);
    } catch (e) {
      setError(readErrorMessage(e, i18n('Save failed')));
    } finally {
      setSaving(false);
    }
  };

  const success = savedTitle !== null && !saving;

  return (
    <Dialog open={open} onClose={onClose} title={i18n('Save as Draft')}>
      <div className="space-y-4">
        {/* Title input */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Title')}</label>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder={i18n('Enter a title')}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            autoFocus
          />
        </div>

        {/* Content preview */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Content Preview')}</label>
          <div className="max-h-40 overflow-y-auto border border-border rounded-md p-3 bg-muted/30">
            <pre className="text-xs text-muted-foreground whitespace-pre-wrap font-sans">{preview}</pre>
          </div>
        </div>

        {/* Success state */}
        {success && (
          <div className="flex items-center gap-2 text-sm text-green-600 dark:text-green-400">
            <Bookmark className="size-4" />
            {i18n('Saved as draft')}
            <span className="text-muted-foreground">
              — {savedTitle}
            </span>
          </div>
        )}

        {/* Error state */}
        {error && (
          <div className="text-sm text-destructive">{error}</div>
        )}

        {/* Actions */}
        <div className="flex justify-end gap-2 pt-2">
          {success ? (
            <button
              onClick={onClose}
              className="px-4 py-2 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              {i18n('Close')}
            </button>
          ) : (
            <>
              <button
                onClick={onClose}
                className="px-4 py-2 rounded-md text-sm border border-border hover:bg-muted transition-colors"
              >
                {i18n('Cancel')}
              </button>
              <button
                onClick={handleSave}
                disabled={saving || !title.trim() || !content.trim()}
                className="px-4 py-2 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {saving ? i18n('Saving...') : i18n('Save as Draft')}
              </button>
            </>
          )}
        </div>
      </div>
    </Dialog>
  );
};
