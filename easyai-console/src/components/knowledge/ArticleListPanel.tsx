import React, { useEffect, useMemo, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  FileText, Pencil, Trash2, Eye, Loader2, Plus, Search, Wand2, X,
  Send, Undo2, Bot, FolderInput,
} from 'lucide-react';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { useCategoryStore } from '@/services/stores/category-store';
import { useCapabilityStore, selectCanManage } from '@/services/stores/capability-store';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { ComposeArticleDialog } from '@/components/knowledge/ComposeArticleDialog';
import { MoveArticleDialog } from '@/components/knowledge/MoveArticleDialog';
import { NEW_ARTICLE_KEY } from '@/components/knowledge/constants';
import { articlesUnderFolder } from '@/utils/knowledge-tree';
import { readErrorMessage } from '@/services/api-client';
import { i18n } from '@/utils/i18n';
import type { KnowledgeArticleDto } from '@/services/knowledge-service';

interface ArticleListPanelProps {
  /** Selected folder subtree to display; null shows every accessible article. */
  folderId: string | null;
}

export const ArticleListPanel: React.FC<ArticleListPanelProps> = ({ folderId }) => {
  const articles = useKnowledgeStore((s) => s.articles);
  const folders = useKnowledgeStore((s) => s.folders);
  const articleLoading = useKnowledgeStore((s) => s.articleLoading);
  const articleError = useKnowledgeStore((s) => s.articleError);
  const clearArticleError = useKnowledgeStore((s) => s.clearArticleError);
  const loadArticles = useKnowledgeStore((s) => s.loadArticles);
  const deleteArticle = useKnowledgeStore((s) => s.deleteArticle);
  const publishArticle = useKnowledgeStore((s) => s.publishArticle);
  const unpublishArticle = useKnowledgeStore((s) => s.unpublishArticle);
  const setArticleRag = useKnowledgeStore((s) => s.setArticleRag);
  const knowledgeCategories = useCategoryStore((s) => s.knowledgeCategories);
  // Knowledge reports its create permission through canManage; per-article write flags come
  // from the article rows themselves.
  const canCreate = useCapabilityStore(selectCanManage('knowledge'));
  const navigate = useNavigate();

  const [query, setQuery] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [visibility, setVisibility] = useState<'draft' | 'published'>('published');
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [moveTarget, setMoveTarget] = useState<KnowledgeArticleDto | null>(null);
  const [composeOpen, setComposeOpen] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    void loadArticles().catch(() => undefined);
  }, [loadArticles]);

  const scoped = useMemo(
    () => articlesUnderFolder(articles, folderId, folders),
    [articles, folderId, folders],
  );

  // Creation inherits the tree selection, but only when that folder accepts writes; the other
  // visible bucket would just answer 400.
  const selectedFolder = useMemo(
    () => (folderId ? folders.find((f) => f.id === folderId) ?? null : null),
    [folderId, folders],
  );
  const createTargetFolderId = selectedFolder?.writable ? selectedFolder.id : undefined;

  const draftCount = useMemo(() => scoped.filter((a) => a.status === 'draft').length, [scoped]);
  const publishedCount = scoped.length - draftCount;

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return scoped.filter((a) => {
      if (a.status !== visibility) return false;
      if (visibility === 'published' && categoryFilter !== 'all' && a.category !== categoryFilter) return false;
      if (!q) return true;
      return [a.title, a.description ?? '', a.folderPath].join('\n').toLowerCase().includes(q);
    });
  }, [scoped, query, categoryFilter, visibility]);

  const handleEdit = useCallback(
    (id: string) => {
      navigate(`/knowledge/editor/${encodeURIComponent(id)}`);
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

  const runAction = useCallback(async (id: string, action: () => Promise<unknown>) => {
    setBusyId(id);
    setActionError(null);
    try {
      await action();
    } catch (e) {
      setActionError(readErrorMessage(e, i18n('Operation failed')));
    } finally {
      setBusyId(null);
    }
  }, []);

  const getCategoryLabelKey = (code: string) =>
    knowledgeCategories.find((c) => c.code === code)?.labelKey ?? code;

  return (
    <div className="h-full flex flex-col">
      {(articleError || actionError) && (
        <div className="flex items-center justify-between px-4 py-2 border-b border-destructive/50 bg-destructive/5 text-xs text-destructive shrink-0">
          <span className="truncate">{articleError ?? actionError}</span>
          <button
            onClick={() => { clearArticleError(); setActionError(null); }}
            className="hover:opacity-70 shrink-0"
          >
            <X className="size-3.5" />
          </button>
        </div>
      )}
      {/* Toolbar */}
      <div className="px-4 pt-3 pb-2 border-b border-border space-y-2 shrink-0">
        <div className="inline-flex items-center rounded-md border border-border bg-muted/30 p-0.5">
          <button
            onClick={() => setVisibility('draft')}
            className={`px-2.5 py-1 rounded text-[11px] font-medium transition-colors ${
              visibility === 'draft' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {i18n('Drafts')} ({draftCount})
          </button>
          <button
            onClick={() => setVisibility('published')}
            className={`px-2.5 py-1 rounded text-[11px] font-medium transition-colors ${
              visibility === 'published' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {i18n('Published')} ({publishedCount})
          </button>
        </div>
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
          {visibility === 'published' && (
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
          )}
          {canCreate && (
            <button
              onClick={() => navigate(
                createTargetFolderId
                  ? `/knowledge/editor/${NEW_ARTICLE_KEY}?folderId=${encodeURIComponent(createTargetFolderId)}`
                  : `/knowledge/editor/${NEW_ARTICLE_KEY}`,
              )}
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
              {scoped.length === 0
                ? i18n('No articles yet. Create one or save chat content to get started.')
                : visibility === 'draft' && draftCount === 0
                  ? i18n('No drafts yet. Save chat selections, dictate, or compose to start one.')
                  : visibility === 'published' && publishedCount === 0
                    ? i18n('No published articles yet')
                    : i18n('No matching articles')}
            </div>
          </div>
        )}

        {filtered.map((article) => {
          const busy = busyId === article.id;
          return (
            <div
              key={article.id}
              className="flex items-center gap-3 px-4 py-2.5 border-b border-border/50 hover:bg-muted/30 transition-colors group"
            >
              <FileText className="size-4 text-muted-foreground shrink-0" />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium truncate">{article.title || article.folderPath}</span>
                </div>
                <div className="flex items-center gap-2 mt-0.5">
                  {article.status === 'draft' ? (
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-600 dark:text-amber-400">
                      {i18n('Draft')}
                    </span>
                  ) : (
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-green-500/10 text-green-600 dark:text-green-400">
                      {i18n(getCategoryLabelKey(article.category))}
                    </span>
                  )}
                  {article.status === 'published' && (
                    <span
                      className={`text-[10px] px-1.5 py-0.5 rounded ${
                        article.ragEnabled
                          ? 'bg-primary/10 text-primary'
                          : 'bg-muted text-muted-foreground'
                      }`}
                      title={article.ragEnabled ? i18n('Retrievable by the assistant') : i18n('Hidden from retrieval')}
                    >
                      {article.ragEnabled ? i18n('RAG on') : i18n('RAG off')}
                    </span>
                  )}
                  {article.indexPending && (
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-600 dark:text-amber-400">
                      {i18n('Indexing pending')}
                    </span>
                  )}
                  <span className="text-[10px] text-muted-foreground truncate">{article.folderPath}</span>
                  <span className="text-[10px] text-muted-foreground shrink-0">
                    {new Date(article.updatedAt).toLocaleDateString()}
                  </span>
                </div>
              </div>
              <div className="flex items-center gap-1 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 transition-opacity shrink-0">
                {busy && <Loader2 className="size-3.5 animate-spin text-muted-foreground" />}
                {/* Publishing is a write (backend gates on canWrite); retrieval toggles and
                    deletion are lifecycle decisions gated on canManage. */}
                {!busy && article.canWrite && article.status === 'draft' && (
                  <button
                    onClick={() => void runAction(article.id, () => publishArticle({ id: article.id, category: article.category, title: article.title }))}
                    className="p-1.5 rounded-md hover:bg-primary/10 text-primary transition-colors"
                    title={i18n('Publish')}
                  >
                    <Send className="size-3.5" />
                  </button>
                )}
                {!busy && article.canManage && article.status === 'published' && (
                  <>
                    <button
                      onClick={() => void runAction(article.id, () => setArticleRag({ id: article.id, enabled: !article.ragEnabled }))}
                      className={`p-1.5 rounded-md hover:bg-muted transition-colors ${article.ragEnabled ? 'text-primary' : 'text-muted-foreground'}`}
                      title={article.ragEnabled ? i18n('Hide from retrieval') : i18n('Allow retrieval')}
                    >
                      <Bot className="size-3.5" />
                    </button>
                    <button
                      onClick={() => void runAction(article.id, () => unpublishArticle(article.id))}
                      className="p-1.5 rounded-md hover:bg-muted text-muted-foreground transition-colors"
                      title={i18n('Unpublish')}
                    >
                      <Undo2 className="size-3.5" />
                    </button>
                  </>
                )}
                {!busy && article.canWrite && (
                  <button
                    onClick={() => setMoveTarget(article)}
                    className="p-1.5 rounded-md hover:bg-muted text-muted-foreground transition-colors"
                    title={i18n('Move')}
                  >
                    <FolderInput className="size-3.5" />
                  </button>
                )}
                <button
                  onClick={() => handleEdit(article.id)}
                  className="p-1.5 rounded-md hover:bg-muted transition-colors"
                  title={article.canWrite ? i18n('Edit') : i18n('View')}
                >
                  {article.canWrite ? <Pencil className="size-3.5" /> : <Eye className="size-3.5" />}
                </button>
                {!busy && article.canManage && (
                  <button
                    onClick={() => setDeleteTarget(article.id)}
                    className="p-1.5 rounded-md hover:bg-destructive/10 text-destructive transition-colors"
                    title={i18n('Delete')}
                  >
                    <Trash2 className="size-3.5" />
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>

      <ConfirmDialog
        open={!!deleteTarget}
        message={i18n('Delete this article? This cannot be undone.')}
        danger
        confirmLabel={i18n('Delete')}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      <MoveArticleDialog
        open={!!moveTarget}
        article={moveTarget}
        folders={folders}
        onClose={() => setMoveTarget(null)}
      />

      <ComposeArticleDialog
        open={composeOpen}
        folderId={createTargetFolderId}
        onClose={() => setComposeOpen(false)}
      />
    </div>
  );
};
