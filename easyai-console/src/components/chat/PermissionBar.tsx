import React, { useState } from 'react';
import { useChatStore } from '@/services/stores/chat-store';
import { replyPermission } from '@/services/chat-service';
import type { DoneEvent, ErrorEvent, ChatStreamEvent } from '@/types/socket-event';
import { i18n } from '@/utils/i18n';

/**
 * PermissionBar - displays a permission request and allows user to allow/deny.
 * Shown when a tool requires permission before execution.
 */
export const PermissionBar: React.FC = () => {
  const sessionId = useChatStore((state) => state.sessionId);
  const pendingPermission = useChatStore((state) => state.pendingPermission);
  const isStreaming = useChatStore((state) => state.isStreaming);
  const setPendingPermission = useChatStore((state) => state.setPendingPermission);
  const handleEvent = useChatStore((state) => state.handleEvent);
  const setStreaming = useChatStore((state) => state.setStreaming);

  const [isResponding, setIsResponding] = useState(false);

  if (!pendingPermission || !sessionId) {
    return null;
  }

  const handleAllow = (remember: boolean) => {
    if (!sessionId || isResponding) return;
    setIsResponding(true);
    setStreaming(true);
    // Clear immediately — buttons are disabled via isResponding so no flash.
    // If a new permission_request arrives, handleEvent will re-set pendingPermission.
    setPendingPermission(null);

    replyPermission(
      sessionId,
      pendingPermission.toolCallId,
      'allow',
      remember,
      undefined,
      pendingPermission.permission,
      pendingPermission.pattern,
      {
        onEvent: (event: ChatStreamEvent) => {
          handleEvent(event);
        },
        onDone: (event: DoneEvent) => {
          setIsResponding(false);
          handleEvent(event);
        },
        onError: (event: ErrorEvent) => {
          setIsResponding(false);
          handleEvent(event);
        },
      }
    );
  };

  const handleDeny = () => {
    if (!sessionId || isResponding) return;
    setIsResponding(true);
    setStreaming(true);
    setPendingPermission(null);

    replyPermission(
      sessionId,
      pendingPermission.toolCallId,
      'deny',
      false,
      undefined,
      pendingPermission.permission,
      pendingPermission.pattern,
      {
        onEvent: (event: ChatStreamEvent) => {
          handleEvent(event);
        },
        onDone: (event: DoneEvent) => {
          setIsResponding(false);
          handleEvent(event);
        },
        onError: (event: ErrorEvent) => {
          setIsResponding(false);
          handleEvent(event);
        },
      }
    );
  };

  const formatToolName = (name: string): string => {
    return name.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  };

  /** Map new permission types to human-readable descriptions. */
  const PERMISSION_LABELS: Record<string, string> = {
    'file.read.project': '读取项目文件',
    'file.read.all': '读取所有文件',
    'file.read.other': '读取指定路径文件',
    'file.write.project': '写入项目文件',
    'file.write.all': '写入所有文件',
    'file.write.other': '写入指定路径文件',
    'shell.safe': '执行安全命令',
    'shell.all': '执行所有命令',
    'shell.other': '执行指定命令',
    'browser.use': '使用浏览器',
    'mcp.use': '使用 MCP 服务',
  };

  const formatPermission = (permission: string): string => {
    return PERMISSION_LABELS[permission] || permission;
  };

  return (
    <div className="mx-4 my-2 p-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-lg">
      <div className="flex items-start gap-3">
        <div className="flex-shrink-0">
          <svg
            className="h-5 w-5 text-amber-600 dark:text-amber-400"
            viewBox="0 0 20 20"
            fill="currentColor"
          >
            <path
              fillRule="evenodd"
              d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
              clipRule="evenodd"
            />
          </svg>
        </div>
        <div className="flex-1 min-w-0">
          <h3 className="text-sm font-medium text-amber-800 dark:text-amber-200">
            Permission Required
          </h3>
          <p className="mt-1 text-sm text-amber-700 dark:text-amber-300">
            <span className="font-medium">{formatToolName(pendingPermission.toolName)}</span>
            {' '}{i18n('请求')}{': '}
            <span className="font-mono text-xs bg-amber-100 dark:bg-amber-800/50 px-1.5 py-0.5 rounded">
              {formatPermission(pendingPermission.permission)}
            </span>
          </p>
          {pendingPermission.pattern && pendingPermission.pattern !== '*' && (
            <p className="mt-1 text-xs text-amber-600 dark:text-amber-400 font-mono truncate" title={pendingPermission.pattern}>
              {pendingPermission.pattern}
            </p>
          )}
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              onClick={() => handleAllow(false)}
              disabled={isResponding || isStreaming}
              className="inline-flex items-center px-3 py-1.5 text-xs font-medium rounded-md
                bg-green-600 text-white hover:bg-green-700
                disabled:opacity-50 disabled:cursor-not-allowed
                transition-colors"
            >
              Allow Once
            </button>
            <button
              onClick={() => handleAllow(true)}
              disabled={isResponding || isStreaming}
              className="inline-flex items-center px-3 py-1.5 text-xs font-medium rounded-md
                bg-green-700 text-white hover:bg-green-800
                disabled:opacity-50 disabled:cursor-not-allowed
                transition-colors"
            >
              Always Allow
            </button>
            <button
              onClick={handleDeny}
              disabled={isResponding || isStreaming}
              className="inline-flex items-center px-3 py-1.5 text-xs font-medium rounded-md
                bg-red-600 text-white hover:bg-red-700
                disabled:opacity-50 disabled:cursor-not-allowed
                transition-colors"
            >
              Deny
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default PermissionBar;
