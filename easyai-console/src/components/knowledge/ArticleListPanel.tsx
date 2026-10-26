import React, { useEffect, useMemo, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { FileText, Pencil, Trash2, Eye, Loader2, Plus, Search, Wand2, X } from 'lucide-react';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { useCategoryStore } from '@/services/stores/category-store';
import { useCapabilityStore, selectCanManage } from '@/services/stores/capability-store';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { ComposeArticleDialog } from '@/components/knowledge/ComposeArticleDialog';
import { NEW_ARTICLE_KEY } from '@/components/knowledge/constants';
import { i18n } from '@/utils/i18n';

export const ArticleListPanel: React.FC = () => {
  const articles = useKnowledgeStore((s) => s.articles);
  const articleLoading = useKnowledgeStore((s) => s.articleLoading);
  const articleError = useKnowledgeStore((s) => s.articleError);
  const clearArticleError = useKnowledgeStore((s) => s.clearArticleError);
  const loadArticles = useKnowledgeStore((s) => s.loadArticles);
  const deleteArticle = useKnowledgeStore((s) => s.deleteArticle);
  const knowledgeCategories = useCategoryStore((s) => s.knowledgeCategories);
  // Knowledge reports its create permission through canManage; per-article write flags come
  // from the article rows themselves.
  const canCreate = useCapabilityStore(selectCanManage('knowledge'));
  const navigate = useNavigate();

  const [query, setQuery] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [composeOpen, setComposeOpen] = useState(false);

  useEffect(() => {
    void loadArticles().catch(() => undefined);
  }, [loadArticles]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return articles.filter((a) => {
      if (categoryFilter !== 'all' && a.category !== categoryFilter) return false;
      if (!q) return true;
      return [a.title, a.description ?? '', a.relativePath].join('\n').toLowerCase().includes(q);
    });
  }, [articles, query, categoryFilter]);

  const handleEdit = useCallback(
    (key: string) => {
      navigate(`/knowledge/editor/${encodeURIComponent(key)}`);
    },
    [navigate],
  );

  const handleDelete = useCallback(async () => {
    if (!deleteTarget) return;
    try {
      await deleteArticle(deleteTarget);
    } catch {
      // articleError banner already reflects the failure
    } finally {
      setDeleteTarget(null);
    }
  }, [deleteTarget, deleteArticle]);

  const getCategoryLabelKey = (code: string) =>
    knowledgeCategories.find((c) => c.code === code)?.labelKey ?? code;

  return (
    <div className="h-full flex flex-col">
      {articleError && (
        <div className="flex items-center justify-between px-4 py-2 border-b border-destructive/50 bg-destructive/5 text-xs text-destructive shrink-0">
          <span className="truncate">{articleError}</span>
          <button onClick={clearArticleError} className="hover:opacity-70 shrink-0">
            <X className="size-3.5" />
          </button>
        </div>
      )}
      {/* Toolbar */}
      <div className="px-4 pt-3 pb-2 border-b border-border space-y-2 shrink-0">
        <div className="flex items-center gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-3.5 text-muted-foreground" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={i18n('Search articles...')}
              className="w-full pl-8 pr-3 py-1.5 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary"
            />
          </div>
          <select
            value={categoryFilter}
            onChange={(e) => setCategoryFilter(e.target.value)}
            className="px-2 py-1.5 rounded-md border border-border bg-background text-[11px] focus:outline-none focus:ring-2 focus:ring-primary"
          >
            <option value="all">{i18n('All Categories')}</option>
            {knowledgeCategories.map((c) => (
              <option key={c.code} value={c.code}>
                {i18n(c.labelKey)}
              </option>
            ))}
          </select>
          {canCreate && (
            <button
              onClick={() => navigate(`/knowledge/editor/${NEW_ARTICLE_KEY}`)}
              className="flex items-center gap-1 px-3 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors shrink-0"
            >
              <Plus className="size-3.5" />
              {i18n('New Article')}
            </button>
          )}
          {canCreate && (
            <button
              onClick={() => setComposeOpen(true)}
              className="flex items-center gap-1 px-3 py-1.5 rounded-md text-xs border border-border hover:bg-muted transition-colors shrink-0"
              title={i18n('Compose Article')}
            >
              <Wand2 className="size-3.5" />
              {i18n('Compose')}
            </button>
          )}
        </div>
      </div>

      {/* Article list */}
      <div className="flex-1 min-h-0 overflow-y-auto">
        {articleLoading && articles.length === 0 && (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="size-5 animate-spin text-muted-foreground" />
          </div>
        )}

        {!articleLoading && filtered.length === 0 && (
          <div className="flex flex-col items-center justify-center py-12 text-center px-8">
            <FileText className="size-10 text-muted-foreground/30 mb-3" />
            <div className="text-sm text-muted-foreground">
              {articles.length === 0
                ? i18n('No articles yet. Create one or save chat content to get started.')
                : i18n('No matching articles')}
            </div>
          </div>
        )}

        {filtered.map((article) => (
          <div
            key={article.id}
            className="flex items-center gap-3 px-4 py-2.5 border-b border-border/50 hover:bg-muted/30 transition-colors group"
          >
            <FileText className="size-4 text-muted-foreground shrink-0" />
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium truncate">{article.title || article.relativePath}</span>
              </div>
              <div className="flex items-center gap-2 mt-0.5">
                <span className="text-[10px] px-1.5 py-0.5 rounded bg-green-500/10 text-green-600 dark:text-green-400">
                  {i18n(getCategoryLabelKey(article.category))}
                </span>
                <span className="text-[10px] text-muted-foreground">{article.source}</span>
                <span className="text-[10px] text-muted-foreground">
                  {new Date(article.updatedAt).toLocaleDateString()}
                </span>
              </div>
            </div>
            <div className="flex items-center gap-1 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 transition-opacity shrink-0">
              <button
                onClick={() => handleEdit(article.knowledgeKey)}
                className="p-1.5 rounded-md hover:bg-muted transition-colors"
                title={article.canWrite ? i18n('Edit') : i18n('View')}
              >
                {article.canWrite ? <Pencil className="size-3.5" /> : <Eye className="size-3.5" />}
              </button>
              {article.canManage && (
                <button
                  onClick={() => setDeleteTarget(article.knowledgeKey)}
                  className="p-1.5 rounded-md hover:bg-destructive/10 text-destructive transition-colors"
                  title={i18n('Delete')}
                >
                  <Trash2 className="size-3.5" />
                </button>
              )}
            </div>
          </div>
        ))}
      </div>

      <ConfirmDialog
        open={!!deleteTarget}
        message={i18n('Delete this article? This cannot be undone.')}
        danger
        confirmLabel={i18n('Delete')}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      <ComposeArticleDialog
        open={composeOpen}
        onClose={() => setComposeOpen(false)}
      />
    </div>
  );
};
