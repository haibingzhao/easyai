import React from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ToolMessageRouter } from '@/components/chat/tools/ToolMessageRouter';
import { useChatStore } from '@/services/stores/chat-store';
import { i18n } from '@/utils/i18n';
import { MobileHeader } from '../components/MobileHeader';
import type { ToolCallStatus } from '@/types/socket-event';
import type { ToolCall, ToolResult } from '@/types/message';

/**
 * Full-screen tool detail.
 *
 * A collapsed process row on desktop expands in place inside a 400px+ column; on a phone the same
 * expansion happens by leaving the transcript, which gives the output the whole viewport and keeps
 * `MessageList` free of mobile-only layout. Rendering itself is delegated to the same
 * `ToolMessageRouter` the chat uses, so Bash/Grep/FileEdit/MCP keep their own viewers.
 */
export const MobileToolDetailPage: React.FC = () => {
  const navigate = useNavigate();
  const { toolCallId } = useParams<{ toolCallId: string }>();

  const messages = useChatStore((s) => s.messages);
  const streamingOutput = useChatStore((s) =>
    toolCallId ? s.streamingToolOutputs[toolCallId] : undefined
  );

  let toolCall: ToolCall | undefined;
  let result: ToolResult | undefined;
  let toolName = '';

  for (const message of messages) {
    if (message.role !== 'assistant') continue;
    const found = message.toolCalls?.find((call) => call.id === toolCallId);
    if (!found) continue;
    toolCall = found;
    toolName = found.toolName;
    result = message.toolResults?.find((r) => r.id === toolCallId);
    break;
  }

  // A tool still producing output lives in the streaming buffers, not in a committed message.
  if (!toolCall && streamingOutput !== undefined) {
    toolCall = { id: toolCallId ?? '', toolName: 'unknown', args: '' };
  }

  const status: ToolCallStatus | undefined = result
    ? result.isError
      ? 'FAILED'
      : 'COMPLETED'
    : streamingOutput !== undefined
      ? 'RUNNING'
      : toolCall
        ? 'PENDING'
        : undefined;

  return (
    <div className="flex flex-col flex-1 min-h-0">
      <MobileHeader onBack={() => navigate(-1)} title={toolName || i18n('Tool')} />
      <div className="flex-1 min-h-0 overflow-y-auto message-list-scroll px-3 py-2">
        {!toolCall ? (
          <p className="text-sm text-muted-foreground">{i18n('This step is no longer available.')}</p>
        ) : (
          <ToolMessageRouter toolCall={toolCall} result={result} status={status} streamingOutput={streamingOutput} />
        )}
      </div>
    </div>
  );
};
