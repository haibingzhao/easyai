import React, { useState } from 'react';
import type { FileDiff } from '../../types/checkpoint';

interface DiffViewerProps {
  diffs: FileDiff[];
  maxFiles?: number;
}

/**
 * Renders a unified diff view for a list of file diffs.
 * Supports per-file collapsing and "show more" expansion.
 */
export const DiffViewer: React.FC<DiffViewerProps> = ({ diffs, maxFiles = 10 }) => {
  const [showAll, setShowAll] = useState(false);
  const [collapsedFiles, setCollapsedFiles] = useState<Set<string>>(new Set());

  const visibleDiffs = showAll ? diffs : diffs.slice(0, maxFiles);

  const toggleCollapse = (path: string) => {
    setCollapsedFiles(prev => {
      const next = new Set(prev);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  };

  const statusIcon = (status: FileDiff['status']) => {
    switch (status) {
      case 'added':
        return <span className="text-green-500 font-bold">A</span>;
      case 'deleted':
        return <span className="text-red-500 font-bold">D</span>;
      case 'modified':
        return <span className="text-orange-500 font-bold">M</span>;
      case 'renamed':
        return <span className="text-blue-500 font-bold">R</span>;
    }
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

  const renderPatch = (patch: string) => {
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
            <div key={i} className={rowClass}>
              <span className="inline-block w-7 text-right pr-1 text-muted-foreground/40 select-none">{oldNum ?? ''}</span>
              <span className="inline-block w-7 text-right pr-2 text-muted-foreground/40 select-none border-r border-border/30">{newNum ?? ''}</span>
              <span className="pl-2">{line.content || '\u00A0'}</span>
            </div>
          );
        })}
      </div>
    );
  };

  if (diffs.length === 0) {
    return <div className="text-sm text-muted-foreground p-3">No changes</div>;
  }

  return (
    <div className="border border-border rounded-md overflow-hidden">
      {visibleDiffs.map((diff) => {
        const isCollapsed = collapsedFiles.has(diff.path);
        return (
          <div key={diff.path} className="border-b border-border last:border-b-0">
            <button
              className="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-accent/50 transition-colors text-left"
              onClick={() => toggleCollapse(diff.path)}
            >
              <span className="text-xs text-muted-foreground">{isCollapsed ? '▶' : '▼'}</span>
              {statusIcon(diff.status)}
              <span className={`flex-1 truncate font-mono text-xs ${diff.status === 'deleted' ? 'line-through text-muted-foreground' : ''}`}>
                {diff.path}
              </span>
              {(diff.additions > 0 || diff.deletions > 0) && (
                <span className="text-xs text-muted-foreground whitespace-nowrap">
                  {diff.additions > 0 && <span className="text-green-500">+{diff.additions}</span>}
                  {diff.deletions > 0 && <span className="text-red-500 ml-1">-{diff.deletions}</span>}
                </span>
              )}
            </button>
            {!isCollapsed && diff.patch && (
              <div className="border-t border-border">
                {renderPatch(diff.patch)}
              </div>
            )}
          </div>
        );
      })}
      {diffs.length > maxFiles && !showAll && (
        <button
          className="w-full px-3 py-2 text-sm text-primary hover:bg-accent/50 transition-colors"
          onClick={() => setShowAll(true)}
        >
          Show all {diffs.length} files
        </button>
      )}
    </div>
  );
};
