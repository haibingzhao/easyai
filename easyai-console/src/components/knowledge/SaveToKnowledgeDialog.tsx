import React, { useState, useEffect, useMemo } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { Bookmark } from 'lucide-react';
import { useCategoryStore } from '@/services/stores/category-store';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { useCapabilityStore, selectKnowledgeShared, selectHasGroup } from '@/services/stores/capability-store';
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
  const knowledgeCategories = useCategoryStore((s) => s.knowledgeCategories);
  const saveToKnowledge = useKnowledgeStore((s) => s.saveToKnowledge);
  const knowledgeShared = useCapabilityStore(selectKnowledgeShared);
  const hasGroup = useCapabilityStore(selectHasGroup);
  const showVisibility = !knowledgeShared && hasGroup;

  const [title, setTitle] = useState('');
  const [category, setCategory] = useState('other');
  const [visibility, setVisibility] = useState<'private' | 'group'>('private');
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
      setCategory('other');
      setVisibility('private');
      setSaving(false);
      setError(null);
      setSavedTitle(null);
    }
  }, [open, content]);

  // The shared bucket is group-wide by construction; forcing the value keeps the UI honest.
  useEffect(() => {
    if (open && knowledgeShared) setVisibility('group');
  }, [open, knowledgeShared]);

  const handleSave = async () => {
    if (!title.trim() || !content.trim() || saving) return;
    setSaving(true);
    setError(null);
    try {
      const article = await saveToKnowledge({
        title: title.trim(),
        content,
        category,
        visibility: knowledgeShared ? 'group' : showVisibility ? visibility : 'private',
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
    <Dialog open={open} onClose={onClose} title={i18n('Save to Knowledge')}>
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

        {/* Category + Visibility row */}
        <div className="flex gap-4">
          <div className="flex-1">
            <label className="block text-sm font-medium mb-1">{i18n('Category')}</label>
            <select
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            >
              {knowledgeCategories.map((c) => (
                <option key={c.code} value={c.code}>
                  {i18n(c.labelKey)}
                </option>
              ))}
            </select>
          </div>
          {showVisibility && (
            <div className="flex-1">
              <label className="block text-sm font-medium mb-1">{i18n('Visibility')}</label>
              <div className="flex gap-3 mt-2">
                <label className="flex items-center gap-1.5 text-sm cursor-pointer">
                  <input
                    type="radio"
                    name="visibility"
                    value="private"
                    checked={visibility === 'private'}
                    onChange={() => setVisibility('private')}
                    className="accent-primary"
                  />
                  {i18n('Private')}
                </label>
                <label className="flex items-center gap-1.5 text-sm cursor-pointer">
                  <input
                    type="radio"
                    name="visibility"
                    value="group"
                    checked={visibility === 'group'}
                    onChange={() => setVisibility('group')}
                    className="accent-primary"
                  />
                  {i18n('Group')}
                </label>
              </div>
            </div>
          )}
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
            {i18n('Saved successfully!')}
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
                {saving ? i18n('Saving...') : i18n('Save')}
              </button>
            </>
          )}
        </div>
      </div>
    </Dialog>
  );
};
