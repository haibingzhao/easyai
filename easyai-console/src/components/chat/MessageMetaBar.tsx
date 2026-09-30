import React, { useState } from 'react';
import { GitBranch } from 'lucide-react';
import type { AssistantMessage } from '../../types/message';
import { useChatStore } from '@/services/stores/chat-store';
import { sessionService } from '@/services/session-service';
import { switchToSession } from '@/services/session-switch';
import { i18n } from '@/utils/i18n';
import { formatTokenCount } from '../../utils/format';
import { ConfirmDialog } from '../ui/ConfirmDialog';

interface MessageMetaBarProps {
  /** Messages covered by this bar — a single message, or a merged process cluster */
  messages: AssistantMessage[];
  isStreaming?: boolean;
}

/**
 * Hover meta bar: aggregated token usage of the covered turn(s) plus the fork
 * action, which branches from the newest message in the group.
 */
export const MessageMetaBar: React.FC<MessageMetaBarProps> = ({ messages, isStreaming = false }) => {
  const sessionId = useChatStore((s) => s.sessionId);
  const [forking, setForking] = useState(false);
  const [forkConfirmOpen, setForkConfirmOpen] = useState(false);

  const forkTarget = [...messages].reverse().find((m) => m.messageId);
  const canFork = !isStreaming && !!sessionId && !!forkTarget?.messageId;

  let inputTokens = 0;
  let outputTokens = 0;
  let totalCache = 0;
  let durationMs = 0;
  let modelName: string | undefined;
  for (const message of messages) {
    const usage = message.usage;
    if (!usage) continue;
    inputTokens += usage.inputTokens;
    outputTokens += usage.outputTokens;
    totalCache += (usage.cacheReadTokens ?? 0) + (usage.cacheWriteTokens ?? 0);
    durationMs += usage.durationMs ?? 0;
    if (usage.modelName) modelName = usage.modelName;
  }
  const showUsage = outputTokens > 0;
  if (!showUsage && !canFork) return null;

  const handleFork = async () => {
    if (!sessionId || !forkTarget?.messageId || forking) return;
    setForking(true);
    try {
      const branchId = await sessionService.forkSession(sessionId, forkTarget.messageId);
      await switchToSession(branchId);
    } catch (e) {
      console.error('Failed to fork session:', e);
    } finally {
      setForking(false);
    }
  };

  let durationText = '';
  if (showUsage && durationMs > 0) {
    const secs = Math.round(durationMs / 1000);
    if (secs < 60) {
      durationText = `${secs}s`;
    } else {
      const m = Math.floor(secs / 60);
      const s = secs % 60;
      durationText = `${m}m ${s}s`;
    }
  }
  const callTime = showUsage
    ? new Date(messages[messages.length - 1].timestamp).toLocaleTimeString()
    : '';

  return (
    <>
      <div className="max-h-0 overflow-hidden group-hover:max-h-8 transition-[max-height] duration-200 ease-out">
        <div className="flex items-center gap-2 text-[11px] text-muted-foreground/60 tabular-nums pt-1">
          {showUsage && (
            <>
              {modelName && (
                <span className="font-mono">{modelName}</span>
              )}
              <span>↑ {formatTokenCount(inputTokens)}</span>
              <span>↓ {formatTokenCount(outputTokens)}</span>
              {totalCache > 0 && (
                <span>cache {formatTokenCount(totalCache)}</span>
              )}
              {durationText && (
                <span>· {durationText}</span>
              )}
              <span>· {callTime}</span>
            </>
          )}
          {canFork && (
            <button
              onClick={() => setForkConfirmOpen(true)}
              disabled={forking}
              className="p-1 -my-1 rounded hover:bg-muted hover:text-foreground transition-colors disabled:opacity-50"
              title={i18n('Create a branch task from here')}
            >
              <GitBranch className="w-3 h-3" />
            </button>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={forkConfirmOpen}
        message={i18n('Create a new branch session from this message?')}
        confirmLabel={i18n('Create')}
        onConfirm={() => {
          setForkConfirmOpen(false);
          handleFork();
        }}
        onCancel={() => setForkConfirmOpen(false)}
      />
    </>
  );
};
