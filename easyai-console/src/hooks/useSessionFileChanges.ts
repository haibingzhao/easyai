import { useMemo, useCallback, useState, useEffect } from 'react';
import { useChatStore } from '@/services/stores/chat-store';
import { acceptFile, rejectFile, batchAcceptFiles, batchRejectFiles, getSessionDiff, getSessionCommitHistory } from '@/services/checkpoint-service';
import type { FileChangeItem, FileDiff, CommitChangeInfo } from '@/types/checkpoint';

export type ViewMode = 'combined' | 'per-commit';

export interface UseSessionFileChangesReturn {
  sessionFileChanges: FileChangeItem[];
  sessionDiffs: FileDiff[] | null;
  handleAcceptFile: (path: string) => void;
  handleRejectFile: (path: string) => void;
  handleAcceptAll: () => void;
  handleRejectAll: () => void;
  viewMode: ViewMode;
  setViewMode: (mode: ViewMode) => void;
  commitHistory: CommitChangeInfo[] | null;
}

/**
 * Aggregates checkpoint file changes across all messages in the current session,
 * applies per-file review overrides, and provides accept/reject handlers.
 * Also lazily fetches session-level diff data and commit history.
 */
export function useSessionFileChanges(fetchDiffs = false): UseSessionFileChangesReturn {
  const sessionId = useChatStore((s) => s.sessionId);
  const checkpointsByMessageId = useChatStore((s) => s.checkpointsByMessageId);
  const fileReviewOverrides = useChatStore((s) => s.fileReviewOverrides);
  const setFileReviewOverride = useChatStore((s) => s.setFileReviewOverride);
  const setFileReviewOverrides = useChatStore((s) => s.setFileReviewOverrides);
  const removeFileReviewOverride = useChatStore((s) => s.removeFileReviewOverride);

  const [sessionDiffs, setSessionDiffs] = useState<FileDiff[] | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>('combined');
  const [commitHistory, setCommitHistory] = useState<CommitChangeInfo[] | null>(null);

  // Aggregate all checkpoints into a session-level file change list
  const sessionFileChanges: FileChangeItem[] = useMemo(() => {
    const fileMap = new Map<string, FileChangeItem & { authors: Set<string> }>();
    const checkpoints = Object.values(checkpointsByMessageId);
    for (const cp of checkpoints) {
      for (const fc of cp.filesChanged) {
        const existing = fileMap.get(fc.path);
        if (!existing) {
          const authors = new Set<string>();
          if (fc.changedBy) authors.add(fc.changedBy);
          fileMap.set(fc.path, {
            path: fc.path,
            status: fc.status,
            additions: fc.additions,
            deletions: fc.deletions,
            reviewStatus: 'applied',
            changedBy: fc.changedBy,
            authors,
          });
        } else {
          existing.additions = (existing.additions || 0) + fc.additions;
          existing.deletions = (existing.deletions || 0) + fc.deletions;
          if (fc.status !== 'modified') {
            existing.status = fc.status;
          }
          if (fc.changedBy) existing.authors.add(fc.changedBy);
          // If any checkpoint has 'llm' author, mark the file as llm-changeable
          if (fc.changedBy === 'llm') {
            existing.changedBy = 'llm';
          }
        }
      }
    }
    // Build final result with hasBothAuthors flag
    const result: FileChangeItem[] = [];
    for (const [, item] of fileMap) {
      const { authors, ...rest } = item;
      result.push({
        ...rest,
        hasBothAuthors: authors.has('llm') && authors.has('user'),
      });
    }
    // Apply per-file review overrides
    for (const item of result) {
      const override = fileReviewOverrides[item.path];
      if (override) {
        item.reviewStatus = override;
      }
    }
    return result;
  }, [checkpointsByMessageId, fileReviewOverrides]);

  // Lazily fetch session-level diff data
  useEffect(() => {
    if (!fetchDiffs || !sessionId) {
      setSessionDiffs(null);
      return;
    }
    let cancelled = false;
    getSessionDiff(sessionId)
      .then((diffs) => { if (!cancelled) setSessionDiffs(diffs); })
      .catch(() => { if (!cancelled) setSessionDiffs([]); });
    return () => { cancelled = true; };
  }, [fetchDiffs, sessionId]);

  // Lazily fetch commit history when in per-commit mode
  useEffect(() => {
    if (viewMode !== 'per-commit' || !sessionId) {
      setCommitHistory(null);
      return;
    }
    let cancelled = false;
    getSessionCommitHistory(sessionId)
      .then((history) => { if (!cancelled) setCommitHistory(history); })
      .catch(() => { if (!cancelled) setCommitHistory([]); });
    return () => { cancelled = true; };
  }, [viewMode, sessionId]);

  // Per-file review status: handle accept/reject with backend API calls
  const handleAcceptFile = useCallback((path: string) => {
    if (!sessionId) return;
    setFileReviewOverride(path, 'accepted');
    acceptFile(sessionId, path).catch((err) => {
      console.error('Failed to accept file:', err);
      removeFileReviewOverride(path);
    });
  }, [sessionId, setFileReviewOverride, removeFileReviewOverride]);

  const handleRejectFile = useCallback((path: string) => {
    if (!sessionId) return;
    setFileReviewOverride(path, 'rejected');
    rejectFile(sessionId, path).catch((err) => {
      console.error('Failed to reject file:', err);
      removeFileReviewOverride(path);
    });
  }, [sessionId, setFileReviewOverride, removeFileReviewOverride]);

  // Batch accept all applied files (only LLM-changed files)
  const handleAcceptAll = useCallback(() => {
    if (!sessionId) return;
    const appliedFiles = sessionFileChanges.filter(f => f.reviewStatus === 'applied' && f.changedBy !== 'user');
    const paths = appliedFiles.map(f => f.path);
    if (paths.length === 0) return;
    setFileReviewOverrides(Object.fromEntries(paths.map(p => [p, 'accepted' as const])));
    batchAcceptFiles(sessionId, paths).catch((err) => {
      console.error('Failed to batch accept files:', err);
      for (const p of paths) removeFileReviewOverride(p);
    });
  }, [sessionFileChanges, sessionId, setFileReviewOverrides, removeFileReviewOverride]);

  // Batch reject all applied files (only LLM-changed files)
  const handleRejectAll = useCallback(() => {
    if (!sessionId) return;
    const appliedFiles = sessionFileChanges.filter(f => f.reviewStatus === 'applied' && f.changedBy !== 'user');
    const paths = appliedFiles.map(f => f.path);
    if (paths.length === 0) return;
    setFileReviewOverrides(Object.fromEntries(paths.map(p => [p, 'rejected' as const])));
    batchRejectFiles(sessionId, paths).catch((err) => {
      console.error('Failed to batch reject files:', err);
      for (const p of paths) removeFileReviewOverride(p);
    });
  }, [sessionFileChanges, sessionId, setFileReviewOverrides, removeFileReviewOverride]);

  return {
    sessionFileChanges,
    sessionDiffs,
    handleAcceptFile,
    handleRejectFile,
    handleAcceptAll,
    handleRejectAll,
    viewMode,
    setViewMode,
    commitHistory,
  };
}
