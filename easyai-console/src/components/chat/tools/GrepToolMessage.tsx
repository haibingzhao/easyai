/**
 * Grep工具消息渲染组件
 */

import { useState } from 'react';
import { Search, ChevronDown } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { parseGrepOutput, extractOutput, getGrepPattern } from './parsers';
import { getToolDisplayName } from './icons';
import { useNavStore } from '@/services/stores/nav-store';

export function GrepToolMessage({ 
  toolCall, 
  result, 
  status, 
  streamingOutput,
  workDir 
}: ToolMessageProps) {
  const [isCollapsed, setIsCollapsed] = useState(true);
  const openFile = useNavStore((s) => s.openFile);
  
  const pattern = getGrepPattern(toolCall.args);
  const isStreaming = status === 'RUNNING' || status === 'PENDING';
  
  const rawOutput = extractOutput({ result, streamingOutput });
  const matches = parseGrepOutput(rawOutput);
  const matchCount = matches.length;
  
  const displayName = getToolDisplayName(toolCall.toolName);
  const statusText = isStreaming ? 'Searching...' : '';
  
  const statusDotColor = isStreaming
    ? 'bg-muted-foreground animate-pulse'
    : 'bg-foreground';

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* 标题栏 */}
      <div 
        className="p-3 flex items-center justify-between gap-2 border-b border-border cursor-pointer hover:bg-muted/50"
        onClick={() => setIsCollapsed(!isCollapsed)}
      >
        <div className="flex items-center gap-2">
          <Search className="w-4 h-4 text-muted-foreground" />
          <span className="text-sm font-medium">{displayName}</span>
        </div>
        {isStreaming ? (
          <div className="flex items-center gap-2">
            <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
            <span className="text-sm text-muted-foreground">{statusText}</span>
          </div>
        ) : matchCount > 0 ? (
          <div className="flex items-center gap-1">
            <span className="text-xs text-muted-foreground">{matchCount} {matchCount === 1 ? 'match' : 'matches'}</span>
            <ChevronDown 
              className={`w-4 h-4 text-muted-foreground transition-transform duration-200 ${isCollapsed ? '' : 'rotate-180'}`} 
            />
          </div>
        ) : null}
      </div>

      {/* 搜索模式 */}
      {pattern && (
        <div className="px-3 py-2 bg-muted/30 border-b border-border">
          <div className="text-sm font-mono text-muted-foreground truncate">
            <span className="text-muted-foreground">Pattern:</span> {pattern}
          </div>
        </div>
      )}

      {/* 匹配结果 */}
      {!isCollapsed && matchCount > 0 && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <div className="space-y-1">
              {matches.map((match, index) => {
                // Resolve relative paths to absolute using workDir
                const absolutePath = match.filePath.startsWith('/')
                  ? match.filePath
                  : workDir ? `${workDir}/${match.filePath}` : match.filePath;
                return (
                  <div 
                    key={index} 
                    className="text-sm font-mono p-2 bg-muted rounded hover:bg-muted/80"
                    title={match.content}
                  >
                    <span
                      className="text-blue-600 cursor-pointer hover:underline"
                      onClick={(e) => { e.stopPropagation(); openFile(absolutePath); }}
                    >
                      {match.filePath}
                    </span>
                    {match.lineNum > 0 && (
                      <span className="text-muted-foreground">:{match.lineNum}</span>
                    )}
                    {match.content && (
                      <span className="text-muted-foreground block truncate">
                        {match.content}
                      </span>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        </>
      )}

      {/* 无匹配结果 */}
      {!isStreaming && matchCount === 0 && rawOutput && (
        <div className="p-3 text-sm text-muted-foreground">
          {rawOutput}
        </div>
      )}
    </div>
  );
}