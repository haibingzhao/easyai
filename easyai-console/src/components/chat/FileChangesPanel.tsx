import React, { useState } from 'react';
import type { FileChangeItem, FileChangesPanelState, FileDiff } from '../../types/checkpoint';
import { DiffViewer } from './DiffViewer';
import { useNavStore } from '@/services/stores/nav-store';
import { GitCompare } from 'lucide-react';
import { i18n } from '@/utils/i18n';

interface FileChangesPanelProps {
  /** Current panel state */
  state: FileChangesPanelState;
  /** List of file changes */
  files: FileChangeItem[];
  /** Optional diff data for expanded view */
  diffs?: FileDiff[];
  /** Called when user clicks batch "Accept" */
  onAccept?: () => void;
  /** Called when user clicks batch "Reject" */
  onReject?: () => void;
  /** Called when user clicks per-file "Accept" */
  onAcceptFile?: (path: string) => void;
  /** Called when user clicks per-file "Reject" */
  onRejectFile?: (path: string) => void;
  /** Called when user clicks batch "Accept All" (applied state) */
  onAcceptAll?: () => void;
  /** Called when user clicks batch "Reject All" (applied state) */
  onRejectAll?: () => void;
  /** Session ID for diff fetching */
  sessionId?: string;
}

/**
 * Displays file changes for a single assistant turn.
 * Three states: generating, pending_review, applied.
 */
export const FileChangesPanel: React.FC<FileChangesPanelProps> = ({
  state,
  files,
  diffs,
  onAccept,
  onReject,
  onAcceptFile,
  onRejectFile,
  onAcceptAll,
  onRejectAll,
}) => {
  const [expanded, setExpanded] = useState(false);
  const [hoveredFile, setHoveredFile] = useState<string | null>(null);
  const openReviewTab = useNavStore((s) => s.openReviewTab);

  /** When additions === deletions, treat as deleted for display */
  const effectiveStatus = (file: FileChangeItem): FileChangeItem['status'] => {
    if (file.status === 'added' && file.additions && file.deletions && file.additions === file.deletions) {
      return 'deleted';
    }
    return file.status;
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

  /** Compute summary status text from all files' reviewStatus */
  const computeSummaryStatus = (): string | null => {
    if (files.length === 0) return null;
    const statuses = files.map(f => f.reviewStatus);
    const allAccepted = statuses.every(s => s === 'accepted');
    const allRejected = statuses.every(s => s === 'rejected');
    if (allAccepted) return '已接受';
    if (allRejected) return '已拒绝';
    return '部分接受';
  };

  /** Render per-file review status text only (no line counts) */
  const renderReviewStatusText = (file: FileChangeItem) => {
    switch (file.reviewStatus) {
      case 'pending':
        return null;
      case 'accepted':
        return <span className="text-xs text-muted-foreground">{i18n('Accepted')}</span>;
      case 'rejected':
        return <span className="text-xs text-red-400">{i18n('Rejected')}</span>;
      case 'applied':
        return null;
    }
  };

  /** Render per-file right-side content (line counts + status or hover actions) */
  const renderFileRightSide = (file: FileChangeItem) => {
    // When generating: hide accept/reject buttons, only show status
    if (state === 'generating') {
      return (
        <>
          {statusIcon(effectiveStatus(file))}
          {renderReviewStatusText(file)}
        </>
      );
    }

    // When hovering an "applied" file, show per-file accept/reject buttons
    if (hoveredFile === file.path && file.reviewStatus === 'applied') {
      return (
        <div className="flex items-center gap-1.5 shrink-0">
          <button
            className="text-xs px-2 py-0.5 rounded border border-border hover:bg-destructive/10 text-destructive transition-colors"
            onClick={(e) => { e.stopPropagation(); onRejectFile?.(file.path); }}
          >
            {i18n('Reject')}
          </button>
          <button
            className="text-xs px-2 py-0.5 rounded bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            onClick={(e) => { e.stopPropagation(); onAcceptFile?.(file.path); }}
          >
            {i18n('Accept')}
          </button>
        </div>
      );
    }

    // For accepted/rejected files: only show status letter + status text (no line counts)
    if (file.reviewStatus === 'accepted' || file.reviewStatus === 'rejected') {
      return (
        <>
          {statusIcon(effectiveStatus(file))}
          {renderReviewStatusText(file)}
        </>
      );
    }

    // For applied/pending files: show line counts + status letter + status text
    return (
      <>
        {(file.additions || file.deletions) && (
          <span className="text-xs whitespace-nowrap">
            {file.additions ? <span className="text-green-500">+{file.additions}</span> : null}
            {file.deletions ? <span className="text-red-500 ml-1">-{file.deletions}</span> : null}
          </span>
        )}
        {statusIcon(effectiveStatus(file))}
        {renderReviewStatusText(file)}
      </>
    );
  };

  const renderStateLabel = () => {
    switch (state) {
      case 'generating':
        return (
          <span className="text-xs text-muted-foreground">
            {i18n('Generating')}
          </span>
        );
      case 'pending_review':
        return null; // Buttons shown instead
      case 'applied':
        return null; // No label needed
    }
  };

  const renderActions = () => {
    if (state === 'pending_review') {
      return (
        <div className="flex gap-1.5">
          <button
            className="text-xs px-2.5 py-1 rounded-md border border-border hover:bg-destructive/10 text-destructive transition-colors"
            onClick={(e) => { e.stopPropagation(); onReject?.(); }}
          >
            {i18n('Reject')}
          </button>
          <button
            className="text-xs px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            onClick={(e) => { e.stopPropagation(); onAccept?.(); }}
          >
            {i18n('Accept')}
          </button>
        </div>
      );
    }
    // For applied state: check if there are still applied (unreviewed) files
    if (state === 'applied') {
      const hasAppliedFiles = files.some(f => f.reviewStatus === 'applied');
      if (hasAppliedFiles) {
        return (
          <div className="flex gap-1.5">
            <button
              className="text-xs px-2.5 py-1 rounded-md border border-border hover:bg-destructive/10 text-destructive transition-colors"
              onClick={(e) => { e.stopPropagation(); onRejectAll?.(); }}
            >
              {i18n('Reject All')}
            </button>
            <button
              className="text-xs px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
              onClick={(e) => { e.stopPropagation(); onAcceptAll?.(); }}
            >
              {i18n('Accept All')}
            </button>
          </div>
        );
      }
    }
    // All files reviewed: show summary status text
    const summary = computeSummaryStatus();
    if (summary) {
      return <span className="text-xs text-muted-foreground">{summary}</span>;
    }
    return null;
  };

  return (
    <div className="border border-border rounded-t-lg bg-muted">
      {/* Header */}
      <div
        role="button"
        tabIndex={0}
        className="w-full flex items-center justify-between px-3 py-2 hover:bg-accent/30 transition-colors cursor-pointer"
        onClick={() => setExpanded(!expanded)}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setExpanded(!expanded); }}
      >
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-xs text-muted-foreground">{expanded ? '▼' : '▶'}</span>
          <span className="text-sm font-medium truncate">
            {files.length} {i18n('changed files')}
          </span>
          {state === 'applied' && files.length > 0 && (
            <button
              className="text-xs text-primary hover:underline ml-2"
              onClick={(e) => { e.stopPropagation(); openReviewTab(); }}
            >
              <GitCompare className="w-3 h-3 inline mr-1" />
              Review
            </button>
          )}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {renderStateLabel()}
          {renderActions()}
        </div>
      </div>

      {/* File list (always visible when expanded) */}
      {expanded && (
        <div className="border-t border-border max-h-[256px] overflow-y-auto">
          {files.map((file) => (
            <div
              key={file.path}
              className="flex items-center gap-2 px-3 py-1.5 text-sm hover:bg-accent/20 transition-colors"
              onMouseEnter={() => setHoveredFile(file.path)}
              onMouseLeave={() => setHoveredFile(null)}
            >
              <span
                className={`flex-1 truncate font-mono text-xs cursor-pointer hover:text-foreground ${
                  effectiveStatus(file) === 'deleted' ? 'line-through text-muted-foreground' : ''
                }`}
                onClick={() => openReviewTab(file.path)}
                title={file.path}
              >
                {file.path}
              </span>
              {renderFileRightSide(file)}
            </div>
          ))}
        </div>
      )}

      {/* Diff viewer for applied state */}
      {expanded && state === 'applied' && diffs && diffs.length > 0 && (
        <div className="border-t border-border">
          <DiffViewer diffs={diffs} />
        </div>
      )}
    </div>
  );
};
