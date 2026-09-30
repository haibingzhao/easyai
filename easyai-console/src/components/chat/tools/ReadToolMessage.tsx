/**
 * Read工具消息渲染组件
 */

import { useState, useRef, useEffect } from 'react';
import { FileText } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { formatFilePath, parseReadOutput, extractOutput, getToolPath, getToolRowSummary } from './parsers';
import { getToolDisplayName } from './icons';
import { CopyableText } from './CopyableText';
import { ToolRowHeader } from './ToolRowHeader';
import { useCopyToast } from './useCopyToast';
import { useNavStore } from '@/services/stores/nav-store';

/**
 * 最大显示行数（折叠状态下）
 */
const MAX_VISIBLE_LINES = 5;

export function ReadToolMessage({ 
  toolCall, 
  result, 
  status, 
  streamingOutput,
  workDir,
  compact
}: ToolMessageProps) {
  const [isCollapsed, setIsCollapsed] = useState(true);
  const userTouchedRef = useRef(false);
  
  const filePath = getToolPath(toolCall.toolName, toolCall.args);
  const displayPath = formatFilePath(filePath, workDir || '');
  const isStreaming = status === 'RUNNING' || status === 'PENDING';
  const hasError = status === 'FAILED';
  
  const rawOutput = extractOutput({ result, streamingOutput });
  const { lines, totalLines } = parseReadOutput(rawOutput);
  const hasMoreLines = totalLines > MAX_VISIBLE_LINES;
  const displayLines = !isCollapsed || !hasMoreLines ? lines : lines.slice(0, MAX_VISIBLE_LINES);
  
  const displayName = getToolDisplayName(toolCall.toolName);
  const statusText = isStreaming ? 'Reading...' : '';
  const { copyToClipboard, toast } = useCopyToast();
  const openFile = useNavStore((s) => s.openFile);

  const statusDotColor = isStreaming
    ? 'bg-muted-foreground animate-pulse'
    : hasError
      ? 'bg-destructive'
      : 'bg-foreground';

  useEffect(() => {
    if (userTouchedRef.current) return;
    setIsCollapsed(!isStreaming);
  }, [isStreaming]);

  return (
    <>
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
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <FileText className="w-4 h-4 text-muted-foreground shrink-0" />
          <span className="text-sm font-medium shrink-0">{displayName}</span>
          <CopyableText
            text={displayPath || '(no path)'}
            copyText={filePath}
            title={filePath}
            className="text-sm font-mono text-muted-foreground"
            onCopy={copyToClipboard}
            onFileOpen={filePath ? () => openFile(filePath) : undefined}
          />
        </div>
        {isStreaming && (
          <div className="flex items-center gap-2 shrink-0">
            <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
            <span className="text-sm text-muted-foreground">{statusText}</span>
          </div>
        )}
      </div>
      </>
      )}

      {/* Error message or file content */}
      {!isCollapsed && hasError && displayLines.length > 0 && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <div className="text-sm font-mono bg-muted rounded p-2 overflow-x-auto">
              {displayLines.map((line, index) => (
                <div key={index} className="flex">
                  <span className="text-muted-foreground select-none w-10 text-right pr-3 flex-shrink-0">
                    {index + 1}
                  </span>
                  <span className="text-foreground whitespace-pre">{line}</span>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
    {toast}
    </>
  );
}