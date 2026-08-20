import React, { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { BookOpen, Copy, Check, Trash2, FolderTree, Link2, List } from 'lucide-react';
import type { KnowledgeDetailDto } from '@/services/knowledge-service';
import { markdownCodeComponents } from '@/components/chat/markdownCodeComponents';
import { useCategoryStore } from '@/services/stores/category-store';
import { i18n } from '@/utils/i18n';

interface KnowledgeViewerProps {
  detail: KnowledgeDetailDto | null;
  loading: boolean;
  onDelete: (key: string) => void;
  onSelectKey: (key: string) => void;
}

export const KnowledgeViewer: React.FC<KnowledgeViewerProps> = ({
  detail,
  loading,
  onDelete,
  onSelectKey,
}) => {
  const knowledgeCategories = useCategoryStore((s) => s.knowledgeCategories);
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    if (!detail) return;
    try {
      await navigator.clipboard.writeText(detail.fullContent);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard unavailable
    }
  };

  const handleDelete = () => {
    if (!detail) return;
    if (window.confirm(i18n('Are you sure to delete this knowledge entry?'))) {
      onDelete(detail.entry.key);
    }
  };

  if (loading) {
    return (
      <div className="h-full flex items-center justify-center text-sm text-muted-foreground">
        {i18n('Loading...')}
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="h-full flex flex-col items-center justify-center text-center px-8">
        <BookOpen className="size-12 text-muted-foreground/30 mb-3" />
        <div className="text-sm text-muted-foreground max-w-sm">
          {i18n('Select a knowledge entry from the sidebar to view its details.')}
        </div>
      </div>
    );
  }

  const { entry, fullContent, toc, parent, children, related } = detail;
  const categoryLabel = knowledgeCategories.find((c) => c.code === entry.category)?.labelKey ?? entry.category;

  return (
    <div className="h-full overflow-y-auto">
      <div className="max-w-3xl mx-auto p-6">
        {/* Title row */}
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold break-words">{entry.title || entry.relativePath}</h2>
            <div className="flex items-center gap-2 mt-1 flex-wrap">
              <code className="text-[11px] text-muted-foreground bg-muted px-1.5 py-0.5 rounded">
                {entry.source}/{entry.relativePath}
              </code>
              {entry.category && (
                <span className="px-1.5 py-0.5 rounded text-[10px] font-medium border border-green-500/30 bg-green-500/10 text-green-600 dark:text-green-400">
                  {i18n(categoryLabel)}
                </span>
              )}
              {entry.ext && (
                <span className="px-1.5 py-0.5 rounded text-[10px] font-medium border border-border bg-muted">
                  .{entry.ext}
                </span>
              )}
            </div>
          </div>

          {/* Actions */}
          <div className="flex items-center gap-1 shrink-0">
            <button
              onClick={handleCopy}
              className="flex items-center gap-1 px-2 py-1 rounded-md text-xs border border-border hover:bg-muted transition-colors"
              title={i18n('Copy')}
            >
              {copied ? (
                <Check className="size-3.5 text-green-500" />
              ) : (
                <Copy className="size-3.5" />
              )}
              {copied ? i18n('Copied') : i18n('Copy')}
            </button>
            <button
              onClick={handleDelete}
              className="p-1.5 rounded-md text-destructive hover:bg-destructive/10 transition-colors"
              title={i18n('Delete')}
            >
              <Trash2 className="size-3.5" />
            </button>
          </div>
        </div>

        {/* Description */}
        {entry.description && (
          <div className="mt-3 text-sm text-muted-foreground">{entry.description}</div>
        )}

        {/* Metadata row */}
        <div className="mt-2 flex items-center gap-3 text-[11px] text-muted-foreground">
          {entry.chunksCount != null && (
            <span>{entry.chunksCount} chunks</span>
          )}
          {entry.updatedAt != null && (
            <span>{i18n('Updated')}: {new Date(entry.updatedAt).toLocaleString()}</span>
          )}
        </div>

        {/* Table of Contents */}
        {toc.length > 0 && (
          <div className="mt-4 border border-border rounded-md p-3">
            <div className="flex items-center gap-1.5 text-xs font-medium mb-2">
              <List className="size-3.5 text-muted-foreground" />
              {i18n('Table of Contents')}
            </div>
            <ul className="space-y-0.5">
              {toc.map((heading, idx) => (
                <li key={idx} className="text-xs text-muted-foreground truncate">
                  {heading}
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* Parent / Children / Related */}
        {(parent || children.length > 0 || related.length > 0) && (
          <div className="mt-3 space-y-2">
            {parent && (
              <div className="flex items-center gap-2 text-xs">
                <FolderTree className="size-3.5 text-muted-foreground shrink-0" />
                <span className="text-muted-foreground">{i18n('Parent')}:</span>
                <button
                  onClick={() => onSelectKey(parent)}
                  className="text-primary hover:underline truncate"
                >
                  {parent}
                </button>
              </div>
            )}
            {children.length > 0 && (
              <div className="flex items-start gap-2 text-xs">
                <FolderTree className="size-3.5 text-muted-foreground shrink-0 mt-0.5" />
                <span className="text-muted-foreground shrink-0">{i18n('Children')}:</span>
                <div className="flex flex-wrap gap-1">
                  {children.map((child) => (
                    <button
                      key={child}
                      onClick={() => onSelectKey(child)}
                      className="text-primary hover:underline truncate max-w-48"
                    >
                      {child}
                    </button>
                  ))}
                </div>
              </div>
            )}
            {related.length > 0 && (
              <div className="flex items-start gap-2 text-xs">
                <Link2 className="size-3.5 text-muted-foreground shrink-0 mt-0.5" />
                <span className="text-muted-foreground shrink-0">{i18n('Related')}:</span>
                <div className="flex flex-wrap gap-1">
                  {related.map((rel) => (
                    <button
                      key={rel}
                      onClick={() => onSelectKey(rel)}
                      className="text-primary hover:underline truncate max-w-48"
                    >
                      {rel}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Content */}
        <div className="mt-4 border-t border-border pt-4">
          <div className="prose prose-sm dark:prose-invert max-w-none break-words">
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
              {fullContent || i18n('No content')}
            </ReactMarkdown>
          </div>
        </div>
      </div>
    </div>
  );
};
