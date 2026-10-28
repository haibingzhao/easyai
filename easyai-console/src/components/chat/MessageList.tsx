import React, { useState, useCallback, useMemo } from 'react';
import { Play } from 'lucide-react';
import type { Message, AssistantMessage as AssistantMessageType, ErrorMessage as ErrorMessageType, CustomMessage } from '../../types/message';
import { UserMessage } from './UserMessage';
import { InlineEditMessage } from './InlineEditMessage';
import { AssistantMessage } from './AssistantMessage';
import { AssistantMessageCluster } from './AssistantMessageCluster';
import { StreamingMessage } from './StreamingMessage';
import { StreamingIndicator } from './StreamingIndicator';
import { ErrorMessage } from './ErrorMessage';
import { CompactionIndicator } from './CompactionIndicator';
import { ConsolidationTurn, readConsolidationTurn } from './ConsolidationTurn';
import { useChatStore } from '../../services/stores/chat-store';
import { resumeSession } from '../../services/chat-service';
import { i18n } from '../../utils/i18n';

interface MessageListProps {
  messages: Message[];
  isStreaming: boolean;
  /** Disable user message editing (e.g. in Swarm Run read-only view) */
  disableEdit?: boolean;
}

type RenderItem =
  | { type: 'single'; index: number; message: Message }
  | { type: 'cluster'; index: number; messages: AssistantMessageType[] };

/**
 * One backend turn produces an assistant message per LLM round. Consecutive
 * assistant messages are folded into a single render item so their thinking and
 * tool calls share process groups instead of stacking one group per round.
 */
function buildRenderItems(messages: Message[]): RenderItem[] {
  const items: RenderItem[] = [];
  let run: AssistantMessageType[] = [];
  let runStart = 0;

  const flushRun = () => {
    if (run.length === 1) {
      items.push({ type: 'single', index: runStart, message: run[0] });
    } else if (run.length > 1) {
      items.push({ type: 'cluster', index: runStart, messages: run });
    }
    run = [];
  };

  messages.forEach((message, index) => {
    if (message.role === 'assistant') {
      if (run.length === 0) runStart = index;
      run.push(message);
      return;
    }
    flushRun();
    items.push({ type: 'single', index, message });
  });
  flushRun();

  return items;
}

export const MessageList: React.FC<MessageListProps> = ({ messages, isStreaming, disableEdit = false }) => {
  const streamingBlocks = useChatStore((state) => state.streamingBlocks);
  const streamingToolOutputs = useChatStore((state) => state.streamingToolOutputs);
  const cancelReason = useChatStore((state) => state.cancelReason);
  const runningSessionId = useChatStore((state) => state.runningSessionId);
  const pendingPermission = useChatStore((state) => state.pendingPermission);
  const retryInfo = useChatStore((state) => state.retryInfo);
  const livePreview = useChatStore((state) => state.livePreview);
  const transcriptMode = useChatStore((state) => state.transcriptMode);
  const [editingMessageIndex, setEditingMessageIndex] = useState<number | null>(null);

  const items = useMemo(() => buildRenderItems(messages), [messages]);

  // Watch-tap preview of the in-flight round during recovery. Rendered only while it is keyed by a
  // messageId that is not yet committed: once the poll delivers an assistant message with the same
  // messageId, the committed copy owns the round and the preview hides — so a turn is never shown
  // twice. An unkeyed preview (tool events seen before any delta) cannot be deduped, so it stays
  // hidden and the poll surfaces that round's committed tool cards instead.
  const previewVisible =
    !!livePreview &&
    !!livePreview.messageId &&
    livePreview.blocks.length > 0 &&
    !messages.some((m) => m.role === 'assistant' && m.messageId === livePreview.messageId);

  const handleEditClick = useCallback((index: number) => {
    setEditingMessageIndex(index);
  }, []);

  const handleEditCancel = useCallback(() => {
    setEditingMessageIndex(null);
  }, []);

  const handleEditSubmit = useCallback(() => {
    setEditingMessageIndex(null);
  }, []);
  return (
    <div className="flex flex-col gap-3 py-4">
      {items.map((item) => {
        if (item.type === 'cluster') {
          return (
            <div key={`cluster-${item.index}`} className="group relative" data-msg-index={item.index}>
              <AssistantMessageCluster messages={item.messages} />
            </div>
          );
        }

        const index = item.index;
        const message = item.message;
        switch (message.role) {
          case 'user':
          case 'user-with-attachments': {
            // A background consolidation run hands the model a whole memory batch per prompt. Shown
            // inline that is a wall of material hiding the answer, so those turns render collapsed and
            // labelled with the pass they belong to.
            const turnMessage = message as Message & { role: 'user' | 'user-with-attachments' };
            if (readConsolidationTurn(turnMessage)) {
              return (
                <div key={index} data-msg-index={index}>
                  <ConsolidationTurn message={turnMessage} />
                </div>
              );
            }
            // If this message is being edited, replace with InlineEditMessage.
            // data-msg-index stays on the wrapper so the question navigator can still scroll to a
            // question that happens to be open in the editor.
            if (editingMessageIndex === index) {
              return (
                <div key={index} data-msg-index={index}>
                  <InlineEditMessage
                    message={message as Message & { role: 'user' | 'user-with-attachments' }}
                    messageIndex={index}
                    onCancel={handleEditCancel}
                    onSubmit={handleEditSubmit}
                  />
                </div>
              );
            }
            // Determine if message is editable: has messageId, not streaming, not system message
            const msgId = (message as { messageId?: string }).messageId;
            const isSystemMsg = message.metadata?.source === 'completion_check' || !!message.metadata?.systemOrigin;
            const isEditable = !disableEdit && !isStreaming && !!msgId && !isSystemMsg;
            return (
              <div key={index} className="group relative" data-msg-index={index}>
                <UserMessage
                  message={message as Message & { role: 'user' | 'user-with-attachments' }}
                  isEditable={isEditable}
                  onEditClick={() => handleEditClick(index)}
                />
              </div>
            );
          }
          case 'assistant':
            return (
              <div key={index} className="group relative" data-msg-index={index}>
                <AssistantMessage message={message} toolResults={message.toolResults} />
              </div>
            );
          case 'error': {
            const errorMsg = message as ErrorMessageType;
            return (
              <ErrorMessage
                key={index}
                message={errorMsg}
              />
            );
          }
          case 'custom': {
            const customMsg = message as CustomMessage;
            if (customMsg.customType === 'compaction') {
              return <CompactionIndicator key={index} message={customMsg} />;
            }
            return null;
          }
          default:
            return null;
        }
      })}
      
      {(isStreaming || pendingPermission) && (
        <StreamingMessage
          blocks={streamingBlocks}
          cancelReason={runningSessionId ? null : cancelReason}
          streamingToolOutputs={streamingToolOutputs}
        />
      )}

      {previewVisible && livePreview && (
        <StreamingMessage
          blocks={livePreview.blocks}
          cancelReason={null}
          streamingToolOutputs={livePreview.toolOutputs}
        />
      )}

      {isStreaming && retryInfo && !runningSessionId && <StreamingIndicator retryInfo={retryInfo} />}
      
      {isStreaming && streamingBlocks.length === 0 && !runningSessionId && !retryInfo && <StreamingIndicator />}

      {!isStreaming && !transcriptMode && cancelReason && !runningSessionId && (
        <div className="px-4 flex items-center gap-2">
          <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted text-muted-foreground">
            {i18n(cancelReason)}
          </span>
          <button
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md bg-primary/10 hover:bg-primary/20 text-primary text-sm font-medium transition-colors"
            onClick={() => {
              const chatState = useChatStore.getState();
              if (!chatState.sessionId) return;
              chatState.setCancelReason(null);
              chatState.setStreaming(true);
              resumeSession(chatState.sessionId, undefined, {
                onEvent: (event) => chatState.handleEvent(event),
                onDone: (event) => chatState.handleEvent(event),
                onError: (event) => chatState.handleEvent(event),
              });
            }}
          >
            <Play className="size-3.5" />
            {i18n('Resume')}
          </button>
        </div>
      )}
    </div>
  );
};