import React, { useState } from 'react';
import type { CommitChangeInfo, FileDiff } from '@/types/checkpoint';
import { Bot, User, ChevronDown, ChevronRight } from 'lucide-react';
import { i18n } from '@/utils/i18n';

interface CommitHistoryListProps {
  commits: CommitChangeInfo[];
  renderPatch: (patch: string) => React.ReactNode;
  onOpenFile: (path: string) => void;
}

/**
 * Per-commit view component: renders commit history grouped by commit,
 * each with author icon, timestamp, and per-file diffs.
 */
export const CommitHistoryList: React.FC<CommitHistoryListProps> = ({ commits, renderPatch, onOpenFile }) => {
  const [expandedCommits, setExpandedCommits] = useState<Set<string>>(new Set());
  const [expandedFiles, setExpandedFiles] = useState<Set<string>>(new Set());

  const toggleCommit = (hash: string) => {
    setExpandedCommits(prev => {
      const next = new Set(prev);
      if (next.has(hash)) next.delete(hash);
      else next.add(hash);
      return next;
    });
  };

  const toggleFile = (key: string) => {
    setExpandedFiles(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const formatTime = (ts: number) => {
    const d = new Date(ts);
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  };

  const statusIcon = (status: FileDiff['status']) => {
    switch (status) {
      case 'added': return <span className="text-green-500 font-bold text-xs w-4 text-center">A</span>;
      case 'deleted': return <span className="text-red-500 font-bold text-xs w-4 text-center">D</span>;
      case 'modified': return <span className="text-orange-500 font-bold text-xs w-4 text-center">M</span>;
      case 'renamed': return <span className="text-blue-500 font-bold text-xs w-4 text-center">R</span>;
    }
  };

  const authorIcon = (author: 'llm' | 'user', agentId?: string) => {
    if (author === 'llm') {
      return <span title={agentId ? `LLM Agent · ${agentId}` : 'LLM Agent'}><Bot className="w-3 h-3 text-cyan-400" /></span>;
    }
    return <span title="User"><User className="w-3 h-3 text-gray-400" /></span>;
  };

  const authorLabel = (author: 'llm' | 'user', agentId?: string) => {
    if (author === 'llm') return agentId ?? 'LLM';
    return i18n('User');
  };

  // Filter out user commits with no file changes
  const visibleCommits = commits.filter(
    (c) => !(c.author === 'user' && c.files.length === 0)
  );

  if (visibleCommits.length === 0) {
    return (
      <div className="flex items-center justify-center py-8 text-muted-foreground text-sm">
        {i18n('No commit history')}
      </div>
    );
  }

  return (
    <div className="divide-y divide-border">
      {visibleCommits.map((commit) => {
        const isCommitExpanded = expandedCommits.has(commit.commitHash);
        return (
          <div key={commit.commitHash}>
            {/* Commit header */}
            <div
              className="flex items-center gap-2 px-3 py-2 text-xs hover:bg-accent/20 cursor-pointer transition-colors"
              onClick={() => toggleCommit(commit.commitHash)}
            >
              {isCommitExpanded
                ? <ChevronDown className="w-3 h-3 shrink-0 text-muted-foreground" />
                : <ChevronRight className="w-3 h-3 shrink-0 text-muted-foreground" />
              }
              {authorIcon(commit.author, commit.agentId)}
              <span className="font-medium">{authorLabel(commit.author, commit.agentId)}</span>
              <span className="text-muted-foreground">·</span>
              <span className="text-muted-foreground">{commit.message}</span>
              <span className="ml-auto text-muted-foreground/60 shrink-0">{formatTime(commit.timestamp)}</span>
            </div>

            {/* File list within commit */}
            {isCommitExpanded && (
              <div className="border-t border-border/50">
                {commit.files.map((file) => {
                  const fileKey = `${commit.commitHash}:${file.path}`;
                  const isFileExpanded = expandedFiles.has(fileKey);
                  const isLlmCommit = commit.author === 'llm';
                  return (
                    <div key={fileKey} className="border-b border-border/30 last:border-b-0">
                      <div className="flex items-center gap-2 px-6 py-1.5 text-sm hover:bg-accent/10 transition-colors">
                        <button
                          className="shrink-0 text-muted-foreground hover:text-foreground"
                          onClick={(e) => { e.stopPropagation(); toggleFile(fileKey); }}
                        >
                          {isFileExpanded
                            ? <ChevronDown className="w-3 h-3" />
                            : <ChevronRight className="w-3 h-3" />
                          }
                        </button>

                        {statusIcon(file.status)}

                        <span
                          className={`flex-1 truncate font-mono text-xs cursor-pointer hover:text-foreground ${
                            file.status === 'deleted' ? 'line-through text-muted-foreground' : ''
                          }`}
                          onClick={() => onOpenFile(file.path)}
                          title={file.path}
                        >
                          {file.path}
                        </span>

                        {(file.additions || file.deletions) && (
                          <span className="text-xs whitespace-nowrap shrink-0">
                            {file.additions ? <span className="text-green-500">+{file.additions}</span> : null}
                            {file.deletions ? <span className="text-red-500 ml-1">-{file.deletions}</span> : null}
                          </span>
                        )}

                        {/* Accept/Reject only for LLM commits — handled at parent level */}
                        {isLlmCommit && (
                          <div className="flex items-center gap-1 shrink-0">
                            <span className="text-[10px] text-muted-foreground/60">LLM</span>
                          </div>
                        )}
                      </div>

                      {/* Diff patch */}
                      {isFileExpanded && file.patch && (
                        <div className="border-t border-border/30">
                          {renderPatch(file.patch)}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};
