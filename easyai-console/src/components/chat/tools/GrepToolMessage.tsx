/**
 * Grep tool message rendering component.
 */

import { useState, useRef, useEffect } from 'react';
import { Search, ChevronDown } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { parseGrepOutput, extractOutput, getGrepPattern, getToolRowSummary } from './parsers';
import { ToolTooltip } from '@/components/agent/ToolItem';
import { getToolDisplayName } from './icons';
import { ToolRowHeader } from './ToolRowHeader';
import { useNavStore } from '@/services/stores/nav-store';

export function GrepToolMessage({ 
  toolCall, 
  result, 
  status, 
  streamingOutput,
  workDir,
  compact
}: ToolMessageProps) {
  const [isCollapsed, setIsCollapsed] = useState(true);
  const userTouchedRef = useRef(false);
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

  useEffect(() => {
    if (userTouchedRef.current) return;
    setIsCollapsed(!isStreaming);
  }, [isStreaming]);

  return (
    <div className={compact ? 'overflow-hidden' : 'border border-border rounded-lg bg-card overflow-hidden'}>
      {compact ? (
        <ToolRowHeader
          toolName={toolCall.toolName}
          status={status ?? 'PENDING'}
          summary={getToolRowSummary(toolCall.toolName, toolCall.args)}
          expanded={!isCollapsed}
          onToggle={() => { userTouchedRef.current = true; setIsCollapsed(prev => !prev); }}
        />
      ) : (
      <>
      {/* Title bar */}
      <div 
        className="p-3 flex items-center justify-between gap-2 border-b border-border cursor-pointer hover:bg-muted/50"
        onClick={() => { userTouchedRef.current = true; setIsCollapsed(!isCollapsed); }}
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

      {/* Search pattern */}
      {pattern && (
        <div className="px-3 py-2 bg-muted/30 border-b border-border">
          <div className="text-sm font-mono text-muted-foreground truncate">
            <span className="text-muted-foreground">Pattern:</span> {pattern}
          </div>
        </div>
      )}
      </>
      )}

      {/* Match results */}
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
                  <ToolTooltip key={index} name={match.content}>
                    <div
                      className="text-sm font-mono p-2 bg-muted rounded hover:bg-muted/80"
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
                  </ToolTooltip>
                );
              })}
            </div>
          </div>
        </>
      )}

      {/* No matches */}
      {!isStreaming && matchCount === 0 && rawOutput && (
        <div className="p-3 text-sm text-muted-foreground">
          {rawOutput}
        </div>
      )}
    </div>
  );
}