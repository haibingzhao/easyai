/**
 * FileSearch工具消息渲染组件（glob和ls共用）
 */

import { useState, useRef, useEffect, useCallback } from 'react';
import { FolderOpen, FolderSearch, File, ChevronDown } from 'lucide-react';
import type { ToolMessageProps, FileEntry } from './types';
import { parseLsOutput, parseGlobOutput, extractOutput, getSearchPath, getGlobPattern } from './parsers';
import { getToolDisplayName } from './icons';
import { CopyableText } from './CopyableText';
import { useCopyToast } from './useCopyToast';
import { useNavStore } from '@/services/stores/nav-store';

/** 判定文件列表是否在底部的阈值（px） */
const FILE_LIST_SCROLL_THRESHOLD = 30;

export function FileSearchToolMessage({
  toolCall,
  result,
  status,
  streamingOutput
}: ToolMessageProps) {
  const [isCollapsed, setIsCollapsed] = useState(true);

  const isGlob = toolCall.toolName === 'glob';
  const Icon = isGlob ? FolderSearch : FolderOpen;

  const searchPath = getSearchPath(toolCall.args);
  const globPattern = isGlob ? getGlobPattern(toolCall.args) : '';
  const isStreaming = status === 'RUNNING' || status === 'PENDING';

  const rawOutput = extractOutput({ result, streamingOutput });

  // 根据工具类型解析输出
  const entries: FileEntry[] = isGlob
    ? parseGlobOutput(rawOutput)
    : parseLsOutput(rawOutput);

  const entryCount = entries.length;

  const displayName = getToolDisplayName(toolCall.toolName);
  const statusText = isStreaming ? (isGlob ? 'Searching...' : 'Listing...') : '';

  const statusDotColor = isStreaming
    ? 'bg-muted-foreground animate-pulse'
    : 'bg-foreground';

  const { copyToClipboard, toast } = useCopyToast();
  const openFile = useNavStore((s) => s.openFile);

  // For ls results, entries have only filenames — resolve to absolute path using searchPath
  const resolveFilePath = (entry: FileEntry) => {
    if (isGlob || entry.path.startsWith('/')) return entry.path;
    return searchPath ? `${searchPath.replace(/\/+$/, '')}/${entry.path}` : entry.path;
  };

  // 文件列表自动滚动相关
  const fileListRef = useRef<HTMLDivElement>(null);
  const fileListAutoScrollEnabledRef = useRef(true);
  const prevScrollTopRef = useRef(0);

  /** 检查文件列表是否在底部 */
  const isFileListAtBottom = useCallback(() => {
    const el = fileListRef.current;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= FILE_LIST_SCROLL_THRESHOLD;
  }, []);

  /** 滚动文件列表到底部 */
  const scrollFileListToBottom = useCallback(() => {
    const el = fileListRef.current;
    if (!el || !fileListAutoScrollEnabledRef.current) return;
    prevScrollTopRef.current = el.scrollTop;
    el.scrollTop = el.scrollHeight;
  }, []);

  /** 处理文件列表滚动事件 */
  const handleFileListScroll = useCallback(() => {
    const el = fileListRef.current;
    if (!el) return;

    const currentScrollTop = el.scrollTop;
    const prevScrollTop = prevScrollTopRef.current;
    prevScrollTopRef.current = currentScrollTop;

    if (isStreaming) {
      if (currentScrollTop < prevScrollTop - 5) {
        fileListAutoScrollEnabledRef.current = false;
      } else if (isFileListAtBottom() && !fileListAutoScrollEnabledRef.current) {
        fileListAutoScrollEnabledRef.current = true;
      }
      return;
    }

    fileListAutoScrollEnabledRef.current = isFileListAtBottom();
  }, [isStreaming, isFileListAtBottom]);

  // wheel事件：streaming期间用户向上滚轮时立即禁用自动滚动
  useEffect(() => {
    const el = fileListRef.current;
    if (!el) return;
    const handleWheel = (e: WheelEvent) => {
      if (isStreaming && e.deltaY < 0) {
        fileListAutoScrollEnabledRef.current = false;
      }
    };
    el.addEventListener('wheel', handleWheel, { passive: true });
    return () => el.removeEventListener('wheel', handleWheel);
  }, [isStreaming]);

  // streaming时自动滚动文件列表到底部
  useEffect(() => {
    if (isStreaming && fileListAutoScrollEnabledRef.current && !isCollapsed) {
      scrollFileListToBottom();
    }
  }, [entries.length, streamingOutput, isStreaming, isCollapsed, scrollFileListToBottom]);

  // streaming结束时，根据当前位置重新判断是否启用自动滚动
  useEffect(() => {
    if (!isStreaming) {
      fileListAutoScrollEnabledRef.current = isFileListAtBottom();
    }
  }, [isStreaming, isFileListAtBottom]);

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* 标题栏 */}
      <div 
        className="p-3 flex items-center justify-between gap-2 border-b border-border cursor-pointer hover:bg-muted/50"
        onClick={() => setIsCollapsed(!isCollapsed)}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <Icon className="w-4 h-4 text-muted-foreground shrink-0" />
          <span className="text-sm font-medium shrink-0">{displayName}</span>
          {(() => {
            // Combine searchPath and globPattern into one text
            const parts: string[] = [];
            const headerPath = searchPath ? searchPath.replace(/\/+$/, '') : '';
            if (headerPath) parts.push(headerPath);
            if (isGlob && globPattern && globPattern !== '') {
              // Normalize: strip leading and trailing slashes from pattern to prevent
              // double slashes when joining with path, and trailing slash at end
              const normalizedPattern = globPattern.replace(/^\/+/, '').replace(/\/+$/, '');
              if (normalizedPattern) parts.push(normalizedPattern);
            }
            const combined = parts.join(' ');
            const isHeaderFilePath = headerPath && /\.\w+$/.test(headerPath);
            return combined ? (
              <CopyableText
                text={combined}
                title={combined}
                className="text-sm font-mono text-muted-foreground"
                onCopy={copyToClipboard}
                onFileOpen={isHeaderFilePath ? () => openFile(headerPath) : undefined}
              />
            ) : null;
          })()}
        </div>
        <div className="flex items-center gap-1 shrink-0">
          {isStreaming ? (
            <>
              <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
              <span className="text-sm text-muted-foreground">{statusText}</span>
            </>
          ) : entryCount > 0 ? (
            <span className="text-xs text-muted-foreground">{entryCount} {entryCount === 1 ? 'item' : 'items'}</span>
          ) : null}
          {entryCount > 0 && (
            <ChevronDown 
              className={`w-4 h-4 text-muted-foreground transition-transform duration-200 ${isCollapsed ? '' : 'rotate-180'}`} 
            />
          )}
        </div>
      </div>

      {toast}

      {/* 文件列表 */}
      {!isCollapsed && entryCount > 0 && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <div
              ref={fileListRef}
              onScroll={handleFileListScroll}
              className="space-y-1 max-h-60 overflow-y-auto"
            >
              {entries.map((entry, index) => (
                <div 
                  key={index} 
                  className="flex items-center gap-2 text-sm font-mono p-1.5 bg-muted rounded hover:bg-muted/80 min-w-0"
                >
                  {entry.isDirectory ? (
                    <FolderOpen className="w-4 h-4 text-blue-500 shrink-0" />
                  ) : (
                    <File className="w-4 h-4 text-muted-foreground shrink-0" />
                  )}
                  <CopyableText
                    text={entry.path}
                    title={entry.path}
                    className="text-sm font-mono text-foreground"
                    onCopy={copyToClipboard}
                    onFileOpen={!entry.isDirectory && entry.path ? () => openFile(resolveFilePath(entry)) : undefined}
                  />
                </div>
              ))}
            </div>
          </div>
        </>
      )}

      {/* 无结果 */}
      {!isStreaming && entryCount === 0 && rawOutput && (
        <div className="p-3 text-sm text-muted-foreground">
          {rawOutput}
        </div>
      )}
    </div>
  );
}