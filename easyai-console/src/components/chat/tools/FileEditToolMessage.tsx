/**
 * FileEdit tool message rendering component (shared by write and edit).
 */

import { useState } from 'react';
import { FileEdit, FilePlus2, ChevronDown } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { formatFilePath, extractOutput, getToolPath } from './parsers';
import { CopyableText } from './CopyableText';
import { useCopyToast } from './useCopyToast';
import { useNavStore } from '@/services/stores/nav-store';

export function FileEditToolMessage({ 
  toolCall, 
  result, 
  status, 
  streamingOutput,
  workDir 
}: ToolMessageProps) {
  const [isCollapsed, setIsCollapsed] = useState(true);
  
  const isWrite = toolCall.toolName === 'write';
  const Icon = isWrite ? FilePlus2 : FileEdit;
  
  const filePath = getToolPath(toolCall.toolName, toolCall.args);
  const displayPath = formatFilePath(filePath, workDir || '');
  const isStreaming = status === 'RUNNING' || status === 'PENDING';
  
  const rawOutput = extractOutput({ result, streamingOutput });
  
  // Status text
  const getStatusText = () => {
    if (isStreaming) {
      return isWrite ? 'Creating...' : 'Editing...';
    }
    if (status === 'FAILED') return 'Failed';
    if (status === 'COMPLETED') return isWrite ? 'Created' : 'Edited';
    return '';
  };
  
  const statusText = getStatusText();
  const { copyToClipboard, toast } = useCopyToast();
  const openFile = useNavStore((s) => s.openFile);

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* Title bar */}
      <div 
        className="p-3 flex items-center justify-between gap-2 border-b border-border cursor-pointer hover:bg-muted/50"
        onClick={() => setIsCollapsed(!isCollapsed)}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <Icon className="w-4 h-4 text-muted-foreground shrink-0" />
          <span className="text-sm font-medium shrink-0">{statusText || (isWrite ? 'Write' : 'Edit')}</span>
          <CopyableText
            text={displayPath || '(no path)'}
            copyText={filePath}
            title={filePath}
            className="text-sm font-mono text-muted-foreground"
            onCopy={copyToClipboard}
            onFileOpen={filePath ? () => openFile(filePath) : undefined}
          />
        </div>
        <ChevronDown 
          className={`w-4 h-4 text-muted-foreground transition-transform duration-200 shrink-0 ${isCollapsed ? '' : 'rotate-180'}`} 
        />
      </div>

      {toast}

      {/* Output info */}
      {!isCollapsed && rawOutput && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <div className="text-sm font-mono text-muted-foreground whitespace-pre-wrap break-all bg-muted rounded p-2">
              {rawOutput}
            </div>
          </div>
        </>
      )}
    </div>
  );
}