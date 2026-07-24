/**
 * ReadLsGroupedMessage — Merge consecutive read/ls tool calls into a grouped display.
 * Title shows "Read X files, Y folders:" summary.
 * Expanded view shows details for each Read/Ls item.
 */

import { useState, useMemo, useEffect } from 'react';
import { FileText, FolderOpen, ChevronDown, File } from 'lucide-react';
import type { ToolCall, ToolResult } from '@/types/message';
import type { ToolCallStatus } from '@/types/socket-event';
import { formatFilePath, extractOutput, parseReadOutput, parseLsOutput, getToolPath } from './parsers';
import { useNavStore } from '@/services/stores/nav-store';

export interface ReadLsGroupedItem {
  toolCall: ToolCall;
  result?: ToolResult;
  status?: ToolCallStatus;
  streamingOutput?: string;
  workDir?: string;
}

interface ReadLsGroupedMessageProps {
  items: ReadLsGroupedItem[];
  defaultExpanded?: boolean;
}

/**
 * Count files and folders
 */
function useCountStats(items: ReadLsGroupedItem[]) {
  return useMemo(() => {
    let fileCount = 0;
    let folderCount = 0;

    for (const item of items) {
      if (item.toolCall.toolName === 'read') {
        fileCount++;
      } else if (item.toolCall.toolName === 'ls') {
        folderCount++;
      }
    }

    return { fileCount, folderCount };
  }, [items]);
}

/**
 * Determine if the group is in streaming state (any item still running)
 */
function isAnyStreaming(items: ReadLsGroupedItem[]): boolean {
  return items.some(item => item.status === 'RUNNING' || item.status === 'PENDING');
}

/**
 * Render detail for a single read item (when expanded)
 */
const DETAIL_PREVIEW_LINES = 5;

function ReadItemDetail({ item }: { item: ReadLsGroupedItem }) {
  const rawOutput = extractOutput({ result: item.result, streamingOutput: item.streamingOutput });
  const { lines, totalLines } = parseReadOutput(rawOutput);
  const hasError = item.status === 'FAILED';
  const isStreaming = item.status === 'RUNNING' || item.status === 'PENDING';

  if (isStreaming) {
    return (
      <div className="border-t border-border p-3">
        <div className="text-sm text-muted-foreground font-mono">Reading...</div>
      </div>
    );
  }

  if (lines.length === 0) return null;

  return (
    <div className="border-t border-border p-3">
      <div className="text-sm font-mono bg-muted rounded p-2 overflow-x-auto">
        {lines.slice(0, DETAIL_PREVIEW_LINES).map((line, index) => (
          <div key={index} className="flex">
            <span className="text-muted-foreground select-none w-10 text-right pr-3 flex-shrink-0">
              {index + 1}
            </span>
            <span className={`${hasError ? 'text-destructive' : 'text-foreground'} whitespace-pre`}>{line}</span>
          </div>
        ))}
        {totalLines > DETAIL_PREVIEW_LINES && (
          <div className="text-muted-foreground text-xs mt-1">... {totalLines - DETAIL_PREVIEW_LINES} more lines</div>
        )}
      </div>
    </div>
  );
}

/**
 * Render detail for a single ls item (when expanded)
 */
function LsItemDetail({ item }: { item: ReadLsGroupedItem }) {
  const rawOutput = extractOutput({ result: item.result, streamingOutput: item.streamingOutput });
  const entries = parseLsOutput(rawOutput);
  const isStreaming = item.status === 'RUNNING' || item.status === 'PENDING';

  if (isStreaming) {
    return (
      <div className="border-t border-border p-3">
        <div className="text-sm text-muted-foreground font-mono">Listing...</div>
      </div>
    );
  }

  if (entries.length === 0) return null;

  return (
    <div className="border-t border-border p-3">
      <div className="space-y-1 max-h-40 overflow-y-auto">
        {entries.map((entry, index) => (
          <div
            key={index}
            className="flex items-center gap-2 text-sm font-mono p-1.5 bg-muted rounded"
          >
            {entry.isDirectory ? (
              <FolderOpen className="w-4 h-4 text-blue-500 shrink-0" />
            ) : (
              <File className="w-4 h-4 text-muted-foreground shrink-0" />
            )}
            <span className="text-foreground truncate" title={entry.path}>
              {entry.name}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

export function ReadLsGroupedMessage({ items, defaultExpanded = false }: ReadLsGroupedMessageProps) {
  const [isExpanded, setIsExpanded] = useState(defaultExpanded);
  const openFile = useNavStore((s) => s.openFile);

  // When defaultExpanded changes to false (group superseded by text/thinking), collapse
  useEffect(() => {
    if (!defaultExpanded) {
      setIsExpanded(false);
    }
  }, [defaultExpanded]);
  const { fileCount, folderCount } = useCountStats(items);
  const streaming = isAnyStreaming(items);

  const statusDotColor = streaming
    ? 'bg-muted-foreground animate-pulse'
    : 'bg-foreground';

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* Title bar */}
      <div
        className="p-3 flex items-center justify-between gap-2 border-b border-border cursor-pointer hover:bg-muted/50"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <FileText className="w-4 h-4 text-muted-foreground shrink-0" />
          <span className="text-sm font-medium shrink-0">
            Read {fileCount} {fileCount === 1 ? 'file' : 'files'}
            {folderCount > 0 && `, ${folderCount} ${folderCount === 1 ? 'folder' : 'folders'}`}
            :
          </span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {streaming && (
            <>
              <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
              <span className="text-sm text-muted-foreground">Reading...</span>
            </>
          )}
          <ChevronDown
            className={`w-4 h-4 text-muted-foreground transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
          />
        </div>
      </div>

      {/* Expanded content */}
      {isExpanded && (
        <div>
          {items.map((item) => (
            <div key={item.toolCall.id}>
              {/* Sub-title for each item */}
              <div className="px-3 py-2 flex items-center gap-2 bg-muted/30 border-t border-border">
                {item.toolCall.toolName === 'read' ? (
                  <FileText className="w-3.5 h-3.5 text-muted-foreground" />
                ) : (
                  <FolderOpen className="w-3.5 h-3.5 text-muted-foreground" />
                )}
                {(() => {
                  const path = getToolPath(item.toolCall.toolName, item.toolCall.args);
                  const isRead = item.toolCall.toolName === 'read';
                  const displayText = isRead
                    ? formatFilePath(path, item.workDir || '')
                    : path || '(root)';
                  return (
                    <span
                      className={`text-xs font-mono text-muted-foreground truncate ${isRead && path ? 'cursor-pointer hover:text-foreground' : ''}`}
                      onClick={(e) => { e.stopPropagation(); if (isRead && path) openFile(path); }}
                      title={path || undefined}
                    >
                      {displayText}
                    </span>
                  );
                })()}
              </div>
              {/* Detail */}
              {item.toolCall.toolName === 'read' ? (
                <ReadItemDetail item={item} />
              ) : (
                <LsItemDetail item={item} />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
