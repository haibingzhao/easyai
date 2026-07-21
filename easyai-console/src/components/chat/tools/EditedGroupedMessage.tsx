/**
 * EditedGroupedMessage — 将连续的 edit 工具调用合并展示
 * 标题显示 "Edited X files by Y times:" 摘要
 * 展开后展示每个 Edit 的具体内容
 * Note: write (create file) is NOT aggregated — rendered as standalone ToolMessage.
 */

import { useState, useMemo, useEffect } from 'react';
import { FileEdit, ChevronDown } from 'lucide-react';
import type { ToolCall, ToolResult } from '@/types/message';
import type { ToolCallStatus } from '@/types/socket-event';
import { formatFilePath, extractOutput, getToolPath } from './parsers';
import { useNavStore } from '@/services/stores/nav-store';

export interface EditedGroupedItem {
  toolCall: ToolCall;
  result?: ToolResult;
  status?: ToolCallStatus;
  streamingOutput?: string;
  workDir?: string;
}

interface EditedGroupedMessageProps {
  items: EditedGroupedItem[];
  defaultExpanded?: boolean;
}

/**
 * 统计去重文件数和操作次数
 */
function useCountStats(items: EditedGroupedItem[]) {
  return useMemo(() => {
    const uniquePaths = new Set<string>();

    for (const item of items) {
      const path = getToolPath(item.toolCall.toolName, item.toolCall.args);
      if (path) {
        uniquePaths.add(path);
      }
    }

    return { fileCount: uniquePaths.size, operationCount: items.length };
  }, [items]);
}

/**
 * 判断分组是否处于 streaming 状态
 */
function isAnyStreaming(items: EditedGroupedItem[]): boolean {
  return items.some(item => item.status === 'RUNNING' || item.status === 'PENDING');
}

export function EditedGroupedMessage({ items, defaultExpanded = false }: EditedGroupedMessageProps) {
  const [isExpanded, setIsExpanded] = useState(defaultExpanded);
  const openFile = useNavStore((s) => s.openFile);

  // When defaultExpanded changes to false (group superseded), collapse
  useEffect(() => {
    if (!defaultExpanded) {
      setIsExpanded(false);
    }
  }, [defaultExpanded]);

  const { fileCount, operationCount } = useCountStats(items);
  const streaming = isAnyStreaming(items);

  const statusDotColor = streaming
    ? 'bg-muted-foreground animate-pulse'
    : 'bg-foreground';

  // Title: "Edited X file(s) by Y time(s)"
  const titleText = `Edited ${fileCount} ${fileCount === 1 ? 'file' : 'files'} by ${operationCount} ${operationCount === 1 ? 'time' : 'times'}`;

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* 标题栏 */}
      <div
        className="p-3 flex items-center gap-2 cursor-pointer hover:bg-muted/50"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <FileEdit className="w-4 h-4 text-muted-foreground shrink-0" />
        <span className="text-sm font-medium">{titleText}</span>
        <div className="flex items-center gap-2 ml-auto shrink-0">
          {streaming && (
            <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
          )}
          <ChevronDown
            className={`w-4 h-4 text-muted-foreground transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
          />
        </div>
      </div>

      {/* 展开内容 */}
      {isExpanded && (
        <div>
          {items.map((item) => {
            const filePath = getToolPath(item.toolCall.toolName, item.toolCall.args);
            const displayPath = formatFilePath(filePath, item.workDir || '');
            const rawOutput = extractOutput({ result: item.result, streamingOutput: item.streamingOutput });
            const isItemStreaming = item.status === 'RUNNING' || item.status === 'PENDING';
            const hasError = item.status === 'FAILED';

            return (
              <div key={item.toolCall.id}>
                {/* 每个 item 的子标题 */}
                <div className="px-3 py-2 flex items-center gap-2 bg-muted/30 border-t border-border">
                  <FileEdit className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                  <span
                    className="text-xs font-mono text-muted-foreground truncate cursor-pointer hover:text-foreground"
                    onClick={(e) => { e.stopPropagation(); if (filePath) openFile(filePath); }}
                    title={filePath || undefined}
                  >
                    {displayPath || '(no path)'}
                  </span>
                  <span className="text-xs text-muted-foreground ml-auto shrink-0">
                    {isItemStreaming ? 'Editing...'
                      : hasError ? 'Failed' : 'Edited'}
                  </span>
                </div>
                {/* 详情 */}
                {rawOutput && !isItemStreaming && (
                  <div className="border-t border-border p-3">
                    <div className="text-sm font-mono text-muted-foreground whitespace-pre-wrap break-all bg-muted rounded p-2">
                      {rawOutput}
                    </div>
                  </div>
                )}
                {isItemStreaming && (
                  <div className="border-t border-border p-3">
                    <div className="text-sm text-muted-foreground font-mono">
                      Editing...
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
