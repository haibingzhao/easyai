import React, { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Pencil, Copy, Trash2, Eraser, Brain, Check } from 'lucide-react';
import type { MemoryEntry } from '@/types/memory';
import { maturityLabelKey } from '@/types/memory';
import { markdownCodeComponents } from '@/components/chat/markdownCodeComponents';
import { i18n } from '@/utils/i18n';

interface MemoryDocumentViewerProps {
  entry: MemoryEntry | null;
  /** Display label of the entry's scope group ('Global' or project name). */
  scopeLabel: string;
  loading: boolean;
  onEdit: () => void;
  onDelete: () => void;
  onClearScope: () => void;
}

const MATURITY_BADGE: Record<string, string> = {
  low: 'bg-slate-500/10 text-slate-600 dark:text-slate-400 border-slate-500/30',
  medium: 'bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/30',
  high: 'bg-green-500/10 text-green-600 dark:text-green-400 border-green-500/30',
};

export const MemoryDocumentViewer: React.FC<MemoryDocumentViewerProps> = ({
  entry,
  scopeLabel,
  loading,
  onEdit,
  onDelete,
  onClearScope,
}) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    if (!entry) return;
    try {
      await navigator.clipboard.writeText(entry.content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard unavailable — ignore
    }
  };

  const handleDelete = () => {
    if (!entry) return;
    if (window.confirm(i18n('Are you sure to delete this memory?'))) {
      onDelete();
    }
  };

  const handleClearScope = () => {
    if (!entry) return;
    if (window.confirm(i18n('Are you sure to clear all memories?'))) {
      onClearScope();
    }
  };

  if (loading) {
    return (
      <div className="h-full flex items-center justify-center text-sm text-muted-foreground">
        {i18n('Loading...')}
      </div>
    );
  }

  if (!entry) {
    return (
      <div className="h-full flex flex-col items-center justify-center text-center px-8">
        <Brain className="size-12 text-muted-foreground/30 mb-3" />
        <div className="text-sm text-muted-foreground max-w-sm">
          {i18n('Select a memory from the sidebar to view its details.')}
        </div>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto">
      <div className="max-w-3xl mx-auto p-6">
        {/* Title row */}
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold break-words">{entry.name}</h2>
            <div className="flex items-center gap-2 mt-1 flex-wrap">
              <code className="text-[11px] text-muted-foreground bg-muted px-1.5 py-0.5 rounded">
                {entry.type}/{entry.name}.md
              </code>
              {entry.maturity && (
                <span
                  className={`px-1.5 py-0.5 rounded text-[10px] font-medium border ${
                    MATURITY_BADGE[entry.maturity] ?? MATURITY_BADGE.low
                  }`}
                >
                  {i18n(maturityLabelKey(entry.maturity))}
                </span>
              )}
              <span className="px-1.5 py-0.5 rounded text-[10px] font-medium border border-blue-500/30 bg-blue-500/10 text-blue-600 dark:text-blue-400">
                {scopeLabel}
              </span>
            </div>
          </div>

          {/* Actions */}
          <div className="flex items-center gap-1 shrink-0">
            <button
              onClick={onEdit}
              className="flex items-center gap-1 px-2 py-1 rounded-md text-xs border border-border hover:bg-muted transition-colors"
            >
              <Pencil className="size-3.5" />
              {i18n('Edit')}
            </button>
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
            <button
              onClick={handleClearScope}
              className="p-1.5 rounded-md text-muted-foreground hover:bg-muted transition-colors"
              title={i18n('Clear All')}
            >
              <Eraser className="size-3.5" />
            </button>
          </div>
        </div>

        {/* Description */}
        {entry.description && (
          <div className="mt-3 text-sm text-muted-foreground">{entry.description}</div>
        )}

        {/* Keywords / Scenarios chips */}
        <div className="mt-3 space-y-2">
          {entry.keywords.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {entry.keywords.map((kw) => (
                <span
                  key={kw}
                  className="px-1.5 py-0.5 rounded text-[11px] bg-muted border border-border"
                >
                  {kw}
                </span>
              ))}
            </div>
          )}
          {entry.scenarios.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {entry.scenarios.map((sc) => (
                <span
                  key={sc}
                  className="px-1.5 py-0.5 rounded text-[11px] bg-primary/5 border border-primary/20 text-primary"
                >
                  {sc}
                </span>
              ))}
            </div>
          )}
        </div>

        {/* Content */}
        <div className="mt-4 border-t border-border pt-4">
          <div className="prose prose-sm dark:prose-invert max-w-none break-words">
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
              {entry.content || i18n('No content')}
            </ReactMarkdown>
          </div>
        </div>

        {/* Footer meta */}
        {(entry.created || entry.updated) && (
          <div className="mt-6 pt-3 border-t border-border text-[11px] text-muted-foreground flex gap-4">
            {entry.created && (
              <span>
                {i18n('Created')}: {entry.created}
              </span>
            )}
            {entry.updated && (
              <span>
                {i18n('Updated')}: {entry.updated}
              </span>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
