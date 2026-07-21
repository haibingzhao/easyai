import React, { useState, useMemo, useEffect, useCallback, useRef } from 'react';
import { useSessionFileChanges } from '@/hooks/useSessionFileChanges';
import { useNavStore } from '@/services/stores/nav-store';
import { i18n } from '@/utils/i18n';
import type { FileChangeItem, FileDiff } from '@/types/checkpoint';
import { Check, X, ChevronDown, ChevronRight, Columns2, Rows3, Bot, User } from 'lucide-react';
import { CommitHistoryList } from './CommitHistoryList';

type DiffMode = 'inline' | 'split';

/**
 * Review tab: shows session-level file diffs with per-file accept/reject actions.
 * Supports two view modes:
 * - Combined: flat file list with author icons (default)
 * - Per-commit: commit history grouped by commit with author attribution
 */
export const ReviewTab: React.FC = () => {
  const {
    sessionFileChanges, sessionDiffs, handleAcceptFile, handleRejectFile,
    handleAcceptAll, handleRejectAll, viewMode, setViewMode, commitHistory
  } = useSessionFileChanges(true);
  const openFile = useNavStore((s) => s.openFile);
  const reviewFilePath = useNavStore((s) => s.reviewFilePath);
  const [expandedFiles, setExpandedFiles] = useState<Set<string>>(new Set());
  const [diffMode, setDiffMode] = useState<DiffMode>('inline');
  const [splitRatio, setSplitRatio] = useState(50);
  const fileRefs = React.useRef<Map<string, HTMLDivElement>>(new Map());
  const splitContainerRef = useRef<HTMLDivElement>(null);

  // Drag handlers for split divider
  const handleSplitDrag = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    const container = splitContainerRef.current;
    if (!container) return;
    const rect = container.getBoundingClientRect();
    const onMove = (ev: MouseEvent) => {
      const pct = ((ev.clientX - rect.left) / rect.width) * 100;
      setSplitRatio(Math.min(80, Math.max(20, pct)));
    };
    const onUp = () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  }, []);

  // Auto-expand file when opened from FileChangesPanel (collapse others)
  useEffect(() => {
    if (reviewFilePath) {
      setExpandedFiles(new Set([reviewFilePath]));
      requestAnimationFrame(() => {
        const el = fileRefs.current.get(reviewFilePath);
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      });
      useNavStore.setState({ reviewFilePath: null });
    }
  }, [reviewFilePath]);

  const totals = useMemo(() => {
    let additions = 0;
    let deletions = 0;
    for (const f of sessionFileChanges) {
      additions += f.additions || 0;
      deletions += f.deletions || 0;
    }
    return { additions, deletions };
  }, [sessionFileChanges]);

  const diffMap = useMemo(() => {
    const map = new Map<string, FileDiff>();
    if (sessionDiffs) {
      for (const d of sessionDiffs) map.set(d.path, d);
    }
    return map;
  }, [sessionDiffs]);

  const hasAppliedFiles = sessionFileChanges.some(f => f.reviewStatus === 'applied' && f.changedBy !== 'user');

  const toggleExpand = (path: string) => {
    setExpandedFiles(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

  const statusIcon = (status: FileChangeItem['status']) => {
    switch (status) {
      case 'added':
        return <span className="text-green-500 font-bold text-xs w-4 text-center">A</span>;
      case 'deleted':
        return <span className="text-red-500 font-bold text-xs w-4 text-center">D</span>;
      case 'modified':
        return <span className="text-orange-500 font-bold text-xs w-4 text-center">M</span>;
      case 'renamed':
        return <span className="text-blue-500 font-bold text-xs w-4 text-center">R</span>;
    }
  };

  const authorIcons = (file: FileChangeItem) => {
    const icons: React.ReactNode[] = [];
    if (file.changedBy === 'llm' || file.hasBothAuthors) {
      icons.push(<span key="llm" title="LLM Agent"><Bot className="w-3 h-3 text-cyan-400 inline" /></span>);
    }
    if (file.changedBy === 'user' || file.hasBothAuthors) {
      icons.push(<span key="user" title="User"><User className="w-3 h-3 text-gray-400 inline" /></span>);
    }
    // Fallback: legacy checkpoints without changedBy — default to LLM icon
    if (icons.length === 0) {
      icons.push(<span key="llm" title="LLM Agent"><Bot className="w-3 h-3 text-cyan-400 inline" /></span>);
    }
    return <span className="flex items-center gap-0.5 shrink-0">{icons}</span>;
  };

  type DiffLine = {
    type: 'context' | 'added' | 'removed' | 'hunk';
    content: string;
    oldLineNum: number | null;
    newLineNum: number | null;
  };

  const parseDiffLines = (patch: string): DiffLine[] => {
    const rawLines = patch.split('\n');
    const result: DiffLine[] = [];
    let oldLine = 0;
    let newLine = 0;
    for (const line of rawLines) {
      if (line.startsWith('diff --git') || line.startsWith('index ')) continue;
      if (line.startsWith('--- ') || line.startsWith('+++ ')) continue;
      // Skip git diff metadata lines
      if (line.startsWith('new file mode') || line.startsWith('deleted file mode')) continue;
      if (line.startsWith('old mode') || line.startsWith('new mode')) continue;
      if (line.startsWith('rename from') || line.startsWith('rename to')) continue;
      if (line.startsWith('similarity index') || line.startsWith('dissimilarity index')) continue;
      if (line.startsWith('@@')) {
        const m = line.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
        if (m) { oldLine = parseInt(m[1]); newLine = parseInt(m[2]); }
        result.push({ type: 'hunk', content: line, oldLineNum: null, newLineNum: null });
        continue;
      }
      if (line.startsWith('-')) {
        result.push({ type: 'removed', content: line.slice(1), oldLineNum: oldLine++, newLineNum: null });
      } else if (line.startsWith('+')) {
        result.push({ type: 'added', content: line.slice(1), oldLineNum: null, newLineNum: newLine++ });
      } else {
        const text = line.startsWith(' ') ? line.slice(1) : line;
        result.push({ type: 'context', content: text, oldLineNum: oldLine++, newLineNum: newLine++ });
      }
    }
    return result;
  };

  const renderInlinePatch = (patch: string) => {
    const lines = parseDiffLines(patch);
    return (
      <div className="overflow-x-auto text-xs font-mono">
        {lines.map((line, i) => {
          if (line.type === 'hunk') {
            return (
              <div key={i} className="py-0.5 px-3 text-blue-400/60 select-none">
                ⋯
              </div>
            );
          }
          let rowClass = 'py-0.5';
          if (line.type === 'added') rowClass += ' bg-green-500/10 text-green-400';
          else if (line.type === 'removed') rowClass += ' bg-red-500/10 text-red-400';
          const oldNum = line.oldLineNum;
          const newNum = line.newLineNum;
          return (
            <div key={i} className={`py-0.5 flex ${rowClass}`}>
              <span className="inline-block w-7 text-right pr-1 shrink-0 text-muted-foreground/40 select-none">{oldNum ?? ''}</span>
              <span className="inline-block w-7 text-right pr-2 shrink-0 text-muted-foreground/40 select-none border-r border-border/30">{newNum ?? ''}</span>
              <span className="pl-2 whitespace-pre flex-1 min-w-0">{line.content || '\u00A0'}</span>
            </div>
          );
        })}
      </div>
    );
  };

  const renderSplitPatch = (patch: string) => {
    const lines = parseDiffLines(patch);
    type SplitRow = {
      left: string; right: string;
      leftNum: number | null; rightNum: number | null;
      leftType: 'context' | 'removed' | 'empty';
      rightType: 'context' | 'added' | 'empty';
    };
    const rows: SplitRow[] = [];
    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      if (line.type === 'hunk') {
        rows.push({ left: '⋯', right: '⋯', leftNum: null, rightNum: null, leftType: 'context', rightType: 'context' });
        i++;
        continue;
      }
      if (line.type === 'removed') {
        const removed: DiffLine[] = [];
        while (i < lines.length && lines[i].type === 'removed') { removed.push(lines[i]); i++; }
        const added: DiffLine[] = [];
        while (i < lines.length && lines[i].type === 'added') { added.push(lines[i]); i++; }
        const maxLen = Math.max(removed.length, added.length);
        for (let j = 0; j < maxLen; j++) {
          rows.push({
            left: j < removed.length ? removed[j].content : '',
            right: j < added.length ? added[j].content : '',
            leftNum: j < removed.length ? removed[j].oldLineNum : null,
            rightNum: j < added.length ? added[j].newLineNum : null,
            leftType: j < removed.length ? 'removed' : 'empty',
            rightType: j < added.length ? 'added' : 'empty',
          });
        }
      } else if (line.type === 'added') {
        rows.push({ left: '', right: line.content, leftNum: null, rightNum: line.newLineNum, leftType: 'empty', rightType: 'added' });
        i++;
      } else {
        rows.push({ left: line.content, right: line.content, leftNum: line.oldLineNum, rightNum: line.newLineNum, leftType: 'context', rightType: 'context' });
        i++;
      }
    }

    const splitClass = (type: SplitRow['leftType'] | SplitRow['rightType']) => {
      switch (type) {
        case 'removed': return 'bg-red-500/10 text-red-400';
        case 'added': return 'bg-green-500/10 text-green-400';
        case 'empty': return 'bg-muted/30';
        default: return '';
      }
    };

    return (
      <div ref={splitContainerRef} className="text-xs font-mono flex relative overflow-hidden" style={{ userSelect: 'none' }}>
        <div className="overflow-x-auto min-w-0" style={{ width: `${splitRatio}%` }}>
          {rows.map((row, i) => (
            <div key={i} className={`py-0.5 flex ${splitClass(row.leftType)}`}>
              <span className="inline-block w-7 text-right pr-1 shrink-0 text-muted-foreground/40 select-none">{row.leftNum ?? ''}</span>
              <span className="pl-2 whitespace-pre flex-1">{row.leftType === 'empty' ? '\u00A0' : row.left || '\u00A0'}</span>
            </div>
          ))}
        </div>
        <div
          className="w-1 shrink-0 cursor-col-resize bg-border/50 hover:bg-blue-500/50 active:bg-blue-500/70 transition-colors z-10"
          onMouseDown={handleSplitDrag}
        />
        <div className="overflow-x-auto min-w-0" style={{ width: `calc(${100 - splitRatio}% - 4px)` }}>
          {rows.map((row, i) => (
            <div key={i} className={`py-0.5 flex ${splitClass(row.rightType)}`}>
              <span className="inline-block w-7 text-right pr-1 shrink-0 text-muted-foreground/40 select-none">{row.rightNum ?? ''}</span>
              <span className="pl-2 whitespace-pre flex-1">{row.rightType === 'empty' ? '\u00A0' : row.right || '\u00A0'}</span>
            </div>
          ))}
        </div>
      </div>
    );
  };

  const renderReviewStatus = (file: FileChangeItem) => {
    switch (file.reviewStatus) {
      case 'accepted':
        return <span className="text-xs text-green-500">{i18n('Accepted')}</span>;
      case 'rejected':
        return <span className="text-xs text-red-400">{i18n('Rejected')}</span>;
      case 'applied':
        return null;
      default:
        return null;
    }
  };

  const renderPatch = useCallback((patch: string) => {
    return diffMode === 'inline' ? renderInlinePatch(patch) : renderSplitPatch(patch);
  }, [diffMode, splitRatio]);

  if (sessionFileChanges.length === 0) {
    return (
      <div className="h-full flex flex-col items-center justify-center text-muted-foreground gap-2">
        <span className="text-sm">{i18n('No file changes')}</span>
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col overflow-hidden">
      {/* Toolbar */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-border shrink-0 overflow-x-auto whitespace-nowrap">
        <div className="flex items-center gap-2 text-xs shrink-0">
          {/* View mode toggle */}
          <div className="flex items-center rounded border border-border overflow-hidden">
            <button
              className={`px-2 py-0.5 text-xs transition-colors ${viewMode === 'combined' ? 'bg-primary text-primary-foreground' : 'hover:bg-accent/50'}`}
              onClick={() => setViewMode('combined')}
            >
              {i18n('Combined')}
            </button>
            <button
              className={`px-2 py-0.5 text-xs transition-colors ${viewMode === 'per-commit' ? 'bg-primary text-primary-foreground' : 'hover:bg-accent/50'}`}
              onClick={() => setViewMode('per-commit')}
            >
              {i18n('Per-Commit')}
            </button>
          </div>
          {viewMode === 'combined' && (
            <>
              <span className="font-medium">{sessionFileChanges.length} {i18n('changed files')}</span>
              {(totals.additions > 0 || totals.deletions > 0) && (
                <span className="whitespace-nowrap">
                  {totals.additions > 0 && <span className="text-green-500">+{totals.additions}</span>}
                  {totals.deletions > 0 && <span className="text-red-500 ml-1">-{totals.deletions}</span>}
                </span>
              )}
            </>
          )}
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          {viewMode === 'combined' && hasAppliedFiles && (
            <>
              <button
                className="text-xs px-2 py-0.5 rounded border border-border hover:bg-destructive/10 text-destructive transition-colors"
                onClick={handleRejectAll}
              >
                {i18n('Reject All')}
              </button>
              <button
                className="text-xs px-2 py-0.5 rounded bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
                onClick={handleAcceptAll}
              >
                {i18n('Accept All')}
              </button>
            </>
          )}
          {/* Diff mode toggle */}
          <button
            className="p-1 rounded hover:bg-accent/50 text-muted-foreground hover:text-foreground transition-colors"
            title={diffMode === 'inline' ? i18n('Switch to side-by-side') : i18n('Switch to inline')}
            onClick={() => setDiffMode(prev => prev === 'inline' ? 'split' : 'inline')}
          >
            {diffMode === 'inline' ? <Columns2 className="w-3.5 h-3.5" /> : <Rows3 className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        {viewMode === 'per-commit' ? (
          /* Per-commit view */
          commitHistory === null ? (
            <div className="flex items-center justify-center py-8 text-muted-foreground text-sm">
              {i18n('Loading...')}
            </div>
          ) : (
            <CommitHistoryList
              commits={commitHistory}
              renderPatch={renderPatch}
              onOpenFile={openFile}
            />
          )
        ) : (
          /* Combined view */
          sessionFileChanges.map((file) => {
            const isExpanded = expandedFiles.has(file.path);
            const diff = diffMap.get(file.path);
            let effectiveStatus = diff?.status || file.status;
            // When additions === deletions, treat as deleted for display
            if (effectiveStatus === 'added' && file.additions && file.deletions && file.additions === file.deletions) {
              effectiveStatus = 'deleted';
            }
            const isUserOnly = file.changedBy === 'user' && !file.hasBothAuthors;
            return (
              <div key={file.path} ref={(el) => { if (el) fileRefs.current.set(file.path, el); else fileRefs.current.delete(file.path); }} className="border-b border-border last:border-b-0">
                {/* File row */}
                <div
                  className="flex items-center gap-2 px-3 py-1.5 text-sm hover:bg-accent/20 transition-colors"
                >
                  <button
                    className="shrink-0 text-muted-foreground hover:text-foreground"
                    onClick={() => toggleExpand(file.path)}
                  >
                    {isExpanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
                  </button>

                  {statusIcon(effectiveStatus)}

                  <span
                    className={`flex-1 truncate font-mono text-xs cursor-pointer hover:text-foreground ${
                      effectiveStatus === 'deleted' ? 'line-through text-muted-foreground' : ''
                    }`}
                    onClick={() => openFile(file.path)}
                    title={file.path}
                  >
                    {file.path}
                  </span>

                  {/* Author icons */}
                  {authorIcons(file)}

                  {/* Additions/Deletions */}
                  {(file.additions || file.deletions) && (
                    <span className="text-xs whitespace-nowrap shrink-0">
                      {file.additions ? <span className="text-green-500">+{file.additions}</span> : null}
                      {file.deletions ? <span className="text-red-500 ml-1">-{file.deletions}</span> : null}
                    </span>
                  )}

                  {/* Review status or action buttons (only for LLM-modified files) */}
                  {renderReviewStatus(file)}
                  {!isUserOnly && file.reviewStatus === 'applied' && (
                    <div className="flex items-center gap-1 shrink-0">
                      <button
                        className="p-0.5 rounded hover:bg-destructive/10 text-destructive transition-colors"
                        title={i18n('Reject')}
                        onClick={(e) => { e.stopPropagation(); handleRejectFile(file.path); }}
                      >
                        <X className="w-3.5 h-3.5" />
                      </button>
                      <button
                        className="p-0.5 rounded hover:bg-green-500/10 text-green-500 transition-colors"
                        title={i18n('Accept')}
                        onClick={(e) => { e.stopPropagation(); handleAcceptFile(file.path); }}
                      >
                        <Check className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  )}
                </div>

                {/* Diff patch */}
                {isExpanded && diff?.patch && (
                  <div className="border-t border-border/50">
                    {diffMode === 'inline' ? renderInlinePatch(diff.patch) : renderSplitPatch(diff.patch)}
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
