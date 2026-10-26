import React, { useState, useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Dialog } from '@/components/ui/Dialog';
import { Loader2, FileText, Check, Wand2 } from 'lucide-react';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { useEnabledModels } from '@/hooks/useEnabledModels';
import { readErrorMessage } from '@/services/api-client';
import { i18n } from '@/utils/i18n';
import type { KnowledgeArticleDto } from '@/services/knowledge-service';

interface ComposeArticleDialogProps {
  open: boolean;
  onClose: () => void;
}

const STYLE_OPTIONS = [
  { value: '', label: 'Auto (decide by content)' },
  { value: 'comprehensive summary', label: 'Comprehensive Summary' },
  { value: 'blog post', label: 'Blog Post' },
  { value: 'technical documentation', label: 'Technical Documentation' },
  { value: 'research report', label: 'Research Report' },
  { value: 'tutorial', label: 'Tutorial / Guide' },
  { value: 'executive brief', label: 'Executive Brief' },
];

export const ComposeArticleDialog: React.FC<ComposeArticleDialogProps> = ({
  open,
  onClose,
}) => {
  const navigate = useNavigate();
  const articles = useKnowledgeStore((s) => s.articles);
  const composing = useKnowledgeStore((s) => s.composing);
  const composeResult = useKnowledgeStore((s) => s.composeResult);
  const composeError = useKnowledgeStore((s) => s.composeError);
  const aiCompose = useKnowledgeStore((s) => s.aiCompose);
  const clearComposeResult = useKnowledgeStore((s) => s.clearComposeResult);
  const saveToKnowledge = useKnowledgeStore((s) => s.saveToKnowledge);

  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set());
  const [guidance, setGuidance] = useState('');
  const [style, setStyle] = useState('');
  const { models, selectedModelId, setSelectedModelId, error: modelsError } = useEnabledModels(open);
  const [searchQuery, setSearchQuery] = useState('');
  const [savingTitle, setSavingTitle] = useState('');
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const redirectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (open) {
      setSelectedKeys(new Set());
      setGuidance('');
      setStyle('');
      setSearchQuery('');
      setSavingTitle('');
      setSaved(false);
      setSaving(false);
      setSaveError(null);
      clearComposeResult();
    } else if (redirectTimerRef.current) {
      clearTimeout(redirectTimerRef.current);
      redirectTimerRef.current = null;
    }
  }, [open, clearComposeResult]);

  useEffect(() => {
    return () => {
      if (redirectTimerRef.current) clearTimeout(redirectTimerRef.current);
    };
  }, []);

  const filteredArticles = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return articles;
    return articles.filter((a) =>
      [a.title, a.description ?? '', a.relativePath].join('\n').toLowerCase().includes(q)
    );
  }, [articles, searchQuery]);

  const toggleArticle = (key: string) => {
    setSelectedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const handleCompose = async () => {
    if (selectedKeys.size === 0 || !guidance.trim() || !selectedModelId || composing) return;
    try {
      await aiCompose({
        sourceKeys: Array.from(selectedKeys),
        guidance: guidance.trim(),
        style,
        modelConfigId: selectedModelId,
      });
    } catch {
      // error already in store
    }
  };

  const handleSave = async () => {
    if (!composeResult || saving) return;
    const title = savingTitle.trim() || i18n('Untitled');
    setSaving(true);
    setSaveError(null);
    try {
      const article = await saveToKnowledge({
        title,
        content: composeResult,
        source: 'manual',
        origin: 'compose',
        composedFrom: Array.from(selectedKeys).join(','),
      });
      setSaved(true);
      redirectTimerRef.current = setTimeout(() => {
        onClose();
        navigate(`/knowledge/editor/${encodeURIComponent(article.knowledgeKey)}`);
      }, 800);
    } catch (e) {
      setSaveError(readErrorMessage(e, i18n('Save failed')));
    } finally {
      setSaving(false);
    }
  };

  const canCompose = selectedKeys.size >= 1
    && guidance.trim().length > 0
    && !!selectedModelId
    && models.length > 0
    && !composing;

  return (
    <Dialog open={open} onClose={onClose} title={i18n('Compose Article')}>
      <div className="space-y-4">
        {!composeResult && !saved && (
          <>
            {/* Step 1: Select source articles */}
            <div>
              <label className="block text-sm font-medium mb-1">
                {i18n('Source Articles')} ({selectedKeys.size})
              </label>
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder={i18n('Search articles...')}
                className="w-full px-3 py-1.5 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary mb-2"
              />
              <div className="max-h-40 overflow-y-auto border border-border rounded-md divide-y divide-border/50">
                {filteredArticles.length === 0 && (
                  <div className="px-3 py-4 text-xs text-muted-foreground text-center">
                    {articles.length === 0
                      ? i18n('No articles available')
                      : i18n('No matching articles')}
                  </div>
                )}
                {filteredArticles.map((article) => (
                  <ArticleCheckbox
                    key={article.id}
                    article={article}
                    checked={selectedKeys.has(article.knowledgeKey)}
                    onToggle={() => toggleArticle(article.knowledgeKey)}
                  />
                ))}
              </div>
            </div>

            {/* Step 2: Guidance */}
            <div>
              <label className="block text-sm font-medium mb-1">{i18n('Guidance')}</label>
              <textarea
                value={guidance}
                onChange={(e) => setGuidance(e.target.value)}
                placeholder={i18n('Describe how to compose the article...')}
                rows={3}
                className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary resize-none"
              />
            </div>

            {/* Step 3: Style + Model row */}
            <div className="flex gap-3">
              <div className="flex-1">
                <label className="block text-sm font-medium mb-1">{i18n('Style')}</label>
                <select
                  value={style}
                  onChange={(e) => setStyle(e.target.value)}
                  className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                >
                  {STYLE_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {i18n(opt.label)}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex-1">
                <label className="block text-sm font-medium mb-1">{i18n('Model')}</label>
                <select
                  value={selectedModelId}
                  onChange={(e) => setSelectedModelId(e.target.value)}
                  className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                >
                  {models.map((m) => (
                    <option key={m.id} value={m.id}>{m.name || m.modelId}</option>
                  ))}
                </select>
                {models.length === 0 && (
                  <div className="text-xs text-muted-foreground mt-1">
                    {modelsError ?? i18n('No available models')}
                  </div>
                )}
              </div>
            </div>
          </>
        )}

        {/* Composing state */}
        {composing && (
          <div className="flex items-center justify-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" />
            {i18n('Composing article...')}
          </div>
        )}

        {/* Compose error */}
        {composeError && (
          <div className="text-sm text-destructive">{composeError}</div>
        )}

        {/* Result preview */}
        {composeResult && !saved && (
          <div>
            <label className="block text-sm font-medium mb-1">{i18n('Composed Result')}</label>
            <div className="max-h-60 overflow-y-auto border border-border rounded-md p-3 bg-muted/30">
              <pre className="text-xs text-foreground whitespace-pre-wrap font-sans">{composeResult}</pre>
            </div>
            <div className="mt-3">
              <label className="block text-sm font-medium mb-1">{i18n('Title')}</label>
              <input
                type="text"
                value={savingTitle}
                onChange={(e) => setSavingTitle(e.target.value)}
                placeholder={i18n('Article title')}
                className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                autoFocus
              />
            </div>
            {saveError && (
              <div className="text-sm text-destructive mt-2">{saveError}</div>
            )}
          </div>
        )}

        {/* Success */}
        {saved && (
          <div className="flex items-center gap-2 text-sm text-green-600 dark:text-green-400">
            <Check className="size-4" />
            {i18n('Saved successfully!')}
          </div>
        )}

        {/* Actions */}
        <div className="flex justify-end gap-2 pt-2">
          {composeResult && !saved ? (
            <>
              <button
                onClick={() => { clearComposeResult(); }}
                className="px-4 py-2 rounded-md text-sm border border-border hover:bg-muted transition-colors"
              >
                {i18n('Back')}
              </button>
              <button
                onClick={handleSave}
                disabled={saving}
                className="flex items-center gap-1.5 px-4 py-2 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              >
                {saving ? <Loader2 className="size-3.5 animate-spin" /> : <FileText className="size-3.5" />}
                {i18n('Save')}
              </button>
            </>
          ) : saved ? (
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
                onClick={handleCompose}
                disabled={!canCompose}
                className="flex items-center gap-1.5 px-4 py-2 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {composing ? <Loader2 className="size-3.5 animate-spin" /> : <Wand2 className="size-3.5" />}
                {i18n('Compose')}
              </button>
            </>
          )}
        </div>
      </div>
    </Dialog>
  );
};

const ArticleCheckbox: React.FC<{
  article: KnowledgeArticleDto;
  checked: boolean;
  onToggle: () => void;
}> = ({ article, checked, onToggle }) => (
  <button
    onClick={onToggle}
    className={`w-full flex items-center gap-2.5 px-3 py-2 text-left hover:bg-muted/50 transition-colors ${checked ? 'bg-primary/5' : ''}`}
  >
    <div className={`size-4 rounded border flex items-center justify-center shrink-0 transition-colors ${checked ? 'bg-primary border-primary' : 'border-border'}`}>
      {checked && <Check className="size-3 text-primary-foreground" />}
    </div>
    <FileText className="size-3.5 text-muted-foreground shrink-0" />
    <span className="text-xs truncate flex-1">{article.title || article.relativePath}</span>
    {article.status === 'draft' && (
      <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-600 dark:text-amber-400 shrink-0">
        {i18n('Draft')}
      </span>
    )}
    <span className="text-[10px] text-muted-foreground shrink-0">{article.source}</span>
  </button>
);
