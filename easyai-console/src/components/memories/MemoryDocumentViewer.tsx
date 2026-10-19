import React, { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  Pencil,
  Copy,
  Trash2,
  Eraser,
  Brain,
  Check,
  ChevronRight,
  ChevronDown,
  FileText,
  Layers,
  ArrowUpRight,
  CornerUpLeft,
} from 'lucide-react';
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
  /** Fetch archived entries absorbed into a merged entry (by docId). */
  onLoadRefs: (ids: string[]) => Promise<MemoryEntry[]>;
  /** Show the entry with this docId as the document. Returns false when the read failed or was empty. */
  onOpenById: (docId: string) => Promise<boolean>;
  /** Title of the entry `entry.mergedInto` points at, when it is already loaded; else the raw docId shows. */
  mergedIntoName?: string | null;
}

/** Section markers point at source docIds; the refs panel below already lists them. */
function stripRefMarkers(content: string): string {
  return content.replace(/\s*〔src:[^〕]+〕/g, '');
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
  onLoadRefs,
  onOpenById,
  mergedIntoName,
}) => {
  const [copied, setCopied] = useState(false);
  const [refsOpen, setRefsOpen] = useState(false);
  const [refsLoading, setRefsLoading] = useState(false);
  const [refsError, setRefsError] = useState<string | null>(null);
  const [refEntries, setRefEntries] = useState<MemoryEntry[] | null>(null);
  const [navError, setNavError] = useState<string | null>(null);
  const [navTarget, setNavTarget] = useState<string | null>(null);

  // Reset the drill-down when the selected entry changes.
  useEffect(() => {
    setRefsOpen(false);
    setRefEntries(null);
    setRefsError(null);
    setNavError(null);
  }, [entry?.id, entry?.name]);

  const refs = entry?.refs ?? [];

  const handleToggleRefs = async () => {
    if (refsOpen) {
      setRefsOpen(false);
      return;
    }
    setRefsOpen(true);
    if (refEntries !== null || refs.length === 0) return;
    setRefsLoading(true);
    try {
      setRefEntries(await onLoadRefs(refs));
    } catch (e) {
      // Left unfilled on purpose: "the read failed" and "this entry has no archived sources" are
      // different statements, and collapsing them told the user a merged entry came from nothing.
      // `refEntries` stays null so expanding again retries instead of caching the failure.
      setRefsError((e as Error).message);
    } finally {
      setRefsLoading(false);
    }
  };

  /** Follow the archive chain to another entry, e.g. an archived source up to the one that absorbed it. */
  const handleOpenById = async (docId: string) => {
    setNavTarget(docId);
    setNavError(null);
    try {
      if (!(await onOpenById(docId))) setNavError(i18n('Could not load this entry'));
    } catch (e) {
      setNavError((e as Error).message);
    } finally {
      setNavTarget(null);
    }
  };

  const handleCopy = async () => {
    if (!entry) return;
    try {
      // The same text the panel renders: copying `entry.content` raw handed the user a body with
      // internal docIds in it that they had never seen on screen.
      await navigator.clipboard.writeText(stripRefMarkers(entry.content));
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
              {(entry.level ?? 0) > 0 && (
                <span
                  className="px-1.5 py-0.5 rounded text-[10px] font-medium border border-violet-500/30 bg-violet-500/10 text-violet-600 dark:text-violet-400"
                  title={i18n('Consolidation depth')}
                >
                  {i18n('Level')} {entry.level}
                </span>
              )}
              <span className="px-1.5 py-0.5 rounded text-[10px] font-medium border border-blue-500/30 bg-blue-500/10 text-blue-600 dark:text-blue-400">
                {scopeLabel}
              </span>
              {entry.archived && (
                <span className="px-1.5 py-0.5 rounded text-[10px] font-medium border border-border bg-muted text-muted-foreground">
                  {i18n('Archived')}
                </span>
              )}
            </div>
          </div>

          {/* Actions */}
          <div className="flex items-center gap-1 shrink-0">
            {/* An archived source is read-only here: PUT and DELETE both resolve it by name, and that
                lookup refuses archived docs, so either write would fail on save. It stays mutable as
                part of its scope (Clear All). */}
            {!entry.archived && (
              <>
                <button
                  onClick={onEdit}
                  className="flex items-center gap-1 px-2 py-1 rounded-md text-xs border border-border hover:bg-muted transition-colors"
                >
                  <Pencil className="size-3.5" />
                  {i18n('Edit')}
                </button>
                <button
                  onClick={handleDelete}
                  className="p-1.5 rounded-md text-destructive hover:bg-destructive/10 transition-colors"
                  title={i18n('Delete')}
                >
                  <Trash2 className="size-3.5" />
                </button>
              </>
            )}
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
              onClick={handleClearScope}
              className="p-1.5 rounded-md text-muted-foreground hover:bg-muted transition-colors"
              title={i18n('Clear All')}
            >
              <Eraser className="size-3.5" />
            </button>
          </div>
        </div>

        {/* Upward drill-down: this entry was absorbed by a merged one */}
        {entry.mergedInto && (
          <div className="mt-3">
            <button
              onClick={() => void handleOpenById(entry.mergedInto as string)}
              disabled={navTarget === entry.mergedInto}
              className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md text-xs border border-border hover:bg-muted transition-colors disabled:opacity-60"
              title={i18n('Open the entry that absorbed this one')}
            >
              <CornerUpLeft className="size-3.5 text-muted-foreground shrink-0" />
              <span className="min-w-0 break-words">
                {i18n('Merged into')}{' '}
                {mergedIntoName ?? <code className="text-[11px] text-muted-foreground">{entry.mergedInto}</code>}
              </span>
            </button>
          </div>
        )}

        {navError && <div className="mt-2 text-xs text-destructive">{navError}</div>}

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

        {/* Merged-entry sources (tree view for multi-level consolidation) */}
        {refs.length > 0 && (
          <div className="mt-4 rounded-md border border-border">
            <button
              onClick={() => void handleToggleRefs()}
              className="w-full flex items-center justify-between gap-2 px-3 py-2 text-xs text-foreground hover:bg-muted/60 transition-colors"
            >
              <span className="flex items-center gap-1.5 min-w-0">
                <Layers className="size-3.5 text-muted-foreground shrink-0" />
                <span className="truncate">
                  {i18n('Merged from')} {refs.length} {i18n('memories')}
                </span>
              </span>
              {refsOpen ? (
                <ChevronDown className="size-3.5 text-muted-foreground shrink-0" />
              ) : (
                <ChevronRight className="size-3.5 text-muted-foreground shrink-0" />
              )}
            </button>
            {refsOpen && (
              <div className="border-t border-border px-3 py-2">
                {refsLoading ? (
                  <div className="text-xs text-muted-foreground py-1">{i18n('Loading...')}</div>
                ) : refsError ? (
                  <div className="text-xs text-destructive py-1">{refsError}</div>
                ) : !refEntries || refEntries.length === 0 ? (
                  <div className="text-xs text-muted-foreground py-1">{i18n('No sources found')}</div>
                ) : (
                  <div className="space-y-0.5">
                    {refEntries.map((ref) => (
                      <MemorySourceNode
                        key={ref.id ?? ref.name}
                        entry={ref}
                        depth={0}
                        onLoadRefs={onLoadRefs}
                        onOpenById={handleOpenById}
                        navTarget={navTarget}
                      />
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {/* Content */}
        <div className="mt-4 border-t border-border pt-4">
          <div className="prose prose-sm dark:prose-invert max-w-none break-words">
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
              {stripRefMarkers(entry.content) || i18n('No content')}
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

interface MemorySourceNodeProps {
  entry: MemoryEntry;
  depth: number;
  onLoadRefs: (ids: string[]) => Promise<MemoryEntry[]>;
  onOpenById: (docId: string) => Promise<void>;
  navTarget: string | null;
}

/**
 * Recursive tree node for memory consolidation history. Each node represents a source memory;
 * if that source was itself a consolidation (has refs), it can be expanded to show its own sources.
 */
const MemorySourceNode: React.FC<MemorySourceNodeProps> = ({
  entry,
  depth,
  onLoadRefs,
  onOpenById,
  navTarget,
}) => {
  const [expanded, setExpanded] = useState(false);
  const [childEntries, setChildEntries] = useState<MemoryEntry[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const hasChildren = (entry.refs?.length ?? 0) > 0;

  const handleToggle = async () => {
    if (expanded) {
      setExpanded(false);
      return;
    }
    setExpanded(true);
    if (childEntries !== null || !hasChildren) return;
    setLoading(true);
    try {
      const refs = entry.refs ?? [];
      setChildEntries(await onLoadRefs(refs));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  const handleOpen = async () => {
    if (entry.id) {
      await onOpenById(entry.id);
    }
  };

  return (
    <div>
      <div
        className="flex items-center gap-1 py-1 pr-2 hover:bg-muted/60 transition-colors rounded"
        style={{ paddingLeft: `${depth * 12 + 4}px` }}
      >
        {/* Expand/collapse toggle */}
        {hasChildren ? (
          <button
            onClick={() => void handleToggle()}
            className="shrink-0 p-0.5 hover:bg-muted transition-colors rounded"
          >
            {expanded ? (
              <ChevronDown className="size-3 text-muted-foreground" />
            ) : (
              <ChevronRight className="size-3 text-muted-foreground" />
            )}
          </button>
        ) : (
          <span className="size-3 shrink-0" />
        )}

        {/* Memory icon */}
        <FileText className="size-3 text-muted-foreground shrink-0" />

        {/* Memory name and metadata */}
        <div className="flex-1 min-w-0 flex items-center gap-2">
          <span className="text-xs truncate">{entry.name}</span>
          {(entry.level ?? 0) > 0 && (
            <span className="text-[10px] text-violet-600 dark:text-violet-400 shrink-0">
              {i18n('Level')} {entry.level}
            </span>
          )}
          <span className="text-[10px] text-muted-foreground shrink-0">
            {entry.archived ? i18n('Archived') : i18n('Active')}
          </span>
        </div>

        {/* Open as document button */}
        {entry.id && (
          <button
            onClick={() => void handleOpen()}
            disabled={navTarget === entry.id}
            className="px-1.5 flex items-center shrink-0 text-muted-foreground hover:bg-muted transition-colors disabled:opacity-60 rounded"
            title={i18n('Open as document')}
          >
            <ArrowUpRight className="size-3" />
          </button>
        )}
      </div>

      {/* Loading state */}
      {expanded && loading && (
        <div
          className="flex items-center gap-2 py-1 text-muted-foreground"
          style={{ paddingLeft: `${(depth + 1) * 12 + 4}px` }}
        >
          <span className="text-xs">{i18n('Loading...')}</span>
        </div>
      )}

      {/* Error state */}
      {expanded && error && (
        <div
          className="py-1 text-destructive"
          style={{ paddingLeft: `${(depth + 1) * 12 + 4}px` }}
        >
          <span className="text-xs">{error}</span>
        </div>
      )}

      {/* Child nodes */}
      {expanded && childEntries && childEntries.length > 0 && (
        <div>
          {childEntries.map((child) => (
            <MemorySourceNode
              key={child.id ?? child.name}
              entry={child}
              depth={depth + 1}
              onLoadRefs={onLoadRefs}
              onOpenById={onOpenById}
              navTarget={navTarget}
            />
          ))}
        </div>
      )}

      {/* Empty state */}
      {expanded && childEntries && childEntries.length === 0 && (
        <div
          className="py-1 text-muted-foreground"
          style={{ paddingLeft: `${(depth + 1) * 12 + 4}px` }}
        >
          <span className="text-xs">{i18n('No sources found')}</span>
        </div>
      )}
    </div>
  );
};
