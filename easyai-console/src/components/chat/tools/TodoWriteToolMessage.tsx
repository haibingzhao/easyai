/**
 * TodoWrite 工具消息渲染组件
 */

import { useState } from 'react';
import { ListTodo, AlertTriangle, ChevronDown } from 'lucide-react';
import type { ToolMessageProps } from './types';

/**
 * 解析 todo_write 工具的参数
 */
interface ParsedTodoArgs {
  todos: Array<{
    id?: string;
    content: string;
    status: string;
    priority: string;
  }>;
}

function parseTodoArgs(args: string): ParsedTodoArgs | null {
  try {
    const parsed = JSON.parse(args);
    if (parsed.todos && Array.isArray(parsed.todos)) {
      return parsed;
    }
  } catch {
    // ignore parse error
  }
  return null;
}

function extractOutput(result?: import('@/types/message').ToolResult, streamingOutput?: string): string {
  if (streamingOutput !== undefined) return streamingOutput;
  if (!result) return '';
  if (result.contentBlocks && result.contentBlocks.length > 0) {
    return result.contentBlocks
      .map((block) => {
        if (block.type === 'toolResult') return block.output;
        if (block.type === 'text') return block.text;
        return '';
      })
      .join('');
  }
  return result.result;
}

const statusColors: Record<string, string> = {
  pending: 'text-gray-400 dark:text-gray-500',
  in_progress: 'text-yellow-500 dark:text-yellow-400',
  completed: 'text-green-500 dark:text-green-400',
  cancelled: 'text-red-500 dark:text-red-400',
};

export function TodoWriteToolMessage({
  toolCall,
  result,
  status,
}: ToolMessageProps) {
  const [isCollapsed, setIsCollapsed] = useState(true);
  const parsedArgs = parseTodoArgs(toolCall.args);
  const output = extractOutput(result);
  const isError = result?.isError ?? false;
  const isFailed = status === 'FAILED';

  const todos = parsedArgs?.todos ?? [];
  const completedCount = todos.filter(t => t.status === 'completed').length;
  const totalCount = todos.length;

  const statusText = status === 'RUNNING'
    ? 'Running...'
    : status === 'PENDING'
      ? 'Pending...'
      : isFailed
        ? 'Failed'
        : status === 'COMPLETED'
          ? 'Completed'
          : 'Completed';

  const statusColor = isFailed
    ? 'text-destructive'
    : status === 'RUNNING' || status === 'PENDING'
      ? 'text-muted-foreground'
      : 'text-foreground';

  const statusDotColor = isFailed
    ? 'bg-destructive'
    : status === 'RUNNING' || status === 'PENDING'
      ? 'bg-muted-foreground animate-pulse'
      : 'bg-green-500';

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* 标题栏 */}
      <div 
        className="p-3 flex items-center justify-between gap-2 border-b border-border cursor-pointer hover:bg-muted/50"
        onClick={() => setIsCollapsed(!isCollapsed)}
      >
        <div className="flex items-center gap-2">
          <ListTodo className="w-4 h-4 text-muted-foreground" />
          <span className="text-sm font-medium">TodoWrite</span>
          {totalCount > 0 && (
            <span className="text-xs text-muted-foreground">
              ({completedCount}/{totalCount})
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
          <span className={`text-sm font-medium ${statusColor}`}>
            {statusText}
          </span>
          <ChevronDown 
            className={`w-4 h-4 text-muted-foreground transition-transform duration-200 ${isCollapsed ? '' : 'rotate-180'}`} 
          />
        </div>
      </div>

      {/* TODO 列表 */}
      {!isCollapsed && todos.length > 0 && (
        <div className="p-3 space-y-1.5">
          {todos.map((todo, index) => (
            <div key={todo.id || index} className="flex items-start gap-2 text-sm">
              <span className={`mt-0.5 ${statusColors[todo.status] || 'text-gray-400'}`}>
                {todo.status === 'completed' ? (
                  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/></svg>
                ) : todo.status === 'in_progress' ? (
                  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 6v6l4 2"/><circle cx="12" cy="12" r="10"/></svg>
                ) : todo.status === 'cancelled' ? (
                  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/></svg>
                ) : (
                  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"/></svg>
                )}
              </span>
              <span className="flex-1 text-foreground">{todo.content}</span>
              {todo.priority === 'high' && (
                <span className="text-xs font-medium text-red-600 dark:text-red-400">HIGH</span>
              )}
              {todo.priority === 'medium' && (
                <span className="text-xs font-medium text-yellow-600 dark:text-yellow-400">MEDIUM</span>
              )}
              {todo.priority === 'low' && (
                <span className="text-xs font-medium text-gray-500 dark:text-gray-400">LOW</span>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 错误输出 - 仅在出错时显示 */}
      {(isError || isFailed) && output && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <div className="flex items-center gap-2 mb-2">
              <AlertTriangle className="w-4 h-4 text-amber-500 dark:text-amber-400" />
              <span className="text-sm font-medium text-amber-600 dark:text-amber-400">Error Output</span>
            </div>
            <div className="text-sm font-mono whitespace-pre-wrap break-all text-destructive bg-destructive/10 rounded p-2 max-h-[15em] overflow-y-auto">
              {output}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
