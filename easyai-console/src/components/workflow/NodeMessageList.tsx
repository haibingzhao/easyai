import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Loader2, ChevronDown, ChevronRight } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { swarmService } from '@/services/swarm-service';
import type { MessageSnapshot, TextContentBlock } from '@/services/session-service';
import { convertSnapshot } from '@/services/stores/chat-store';
import { mergeToolResults } from '@/services/stores/chat/session-loader';
import { MessageList } from '@/components/chat/MessageList';
import { markdownCodeComponents } from '@/components/chat/markdownCodeComponents';
import { i18n } from '@/utils/i18n';

const POLL_INTERVAL_MS = 3000;

interface NodeMessageListProps {
  runId: string;
  taskId: string;
  /** Current task status — polling is active while PENDING or IN_PROGRESS */
  taskStatus?: string;
}

export const NodeMessageList: React.FC<NodeMessageListProps> = ({ runId, taskId, taskStatus }) => {
  const [showSystemPrompt, setShowSystemPrompt] = useState(false);
  const [messages, setMessages] = useState<MessageSnapshot[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const scrollContainerRef = useRef<HTMLElement | null>(null);
  /** Tracks the latest message timestamp for incremental fetching */
  const lastTimestampRef = useRef<number>(0);

  const isRunning = taskStatus === 'PENDING' || taskStatus === 'IN_PROGRESS';

  /** Full fetch — used for initial load and compaction fallback */
  const fetchFullMessages = useCallback((showLoader: boolean) => {
    if (showLoader) setLoading(true);
    setError(null);
    swarmService.getTaskSession(runId, taskId).then((session) => {
      if (session) {
        setMessages(session.messages);
        // Update lastTimestamp to the max timestamp in the loaded messages
        const maxTs = session.messages.reduce((max, m) => Math.max(max, m.timestamp ?? 0), 0);
        lastTimestampRef.current = maxTs;
      } else if (showLoader) {
        setError(i18n('No messages available'));
      }
      if (showLoader) setLoading(false);
    }).catch(() => {
      if (showLoader) setError(i18n('Error'));
      if (showLoader) setLoading(false);
    });
  }, [runId, taskId]);

  /** Incremental fetch — only retrieves messages newer than lastTimestamp */
  const fetchIncremental = useCallback(() => {
    const after = lastTimestampRef.current;
    if (after <= 0) {
      // No timestamp yet, fall back to full fetch
      fetchFullMessages(false);
      return;
    }
    swarmService.getTaskSessionMessagesAfter(runId, taskId, after).then((result) => {
      if (!result) return;
      // If compaction occurred, historical messages may have changed — do a full reload
      if (result.compactionOccurredAfter) {
        fetchFullMessages(false);
        return;
      }
      if (result.messages.length > 0) {
        setMessages((prev) => {
          // Deduplicate by message id to handle edge cases
          const existingIds = new Set(prev.map((m) => m.id));
          const newMsgs = result.messages.filter((m) => !m.id || !existingIds.has(m.id));
          return newMsgs.length > 0 ? [...prev, ...newMsgs] : prev;
        });
        // Advance the watermark
        const maxTs = result.messages.reduce((max, m) => Math.max(max, m.timestamp ?? 0), 0);
        if (maxTs > lastTimestampRef.current) {
          lastTimestampRef.current = maxTs;
        }
      }
    }).catch(() => {
      // Silently ignore incremental fetch errors; next poll will retry
    });
  }, [runId, taskId, fetchFullMessages]);

  // Initial load
  useEffect(() => {
    fetchFullMessages(true);
  }, [fetchFullMessages]);

  // Auto-scroll to bottom when messages update
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (container) {
      container.scrollTop = container.scrollHeight;
    }
  }, [messages]);

  // Poll while task is running — uses incremental fetch
  useEffect(() => {
    if (!isRunning) {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
      return;
    }

    pollRef.current = setInterval(() => fetchIncremental(), POLL_INTERVAL_MS);
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [isRunning, fetchIncremental]);

  // Find the closest scrollable ancestor
  const findScrollContainer = useCallback((node: HTMLElement | null) => {
    let current = node;
    while (current) {
      const overflow = getComputedStyle(current).overflowY;
      if (overflow === 'auto' || overflow === 'scroll') {
        scrollContainerRef.current = current;
        return;
      }
      current = current.parentElement;
    }
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="w-5 h-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
        {error}
      </div>
    );
  }

  // Extract the rendered system prompt from session messages
  const systemMsgIndex = messages.findIndex((m) => m.role.toLowerCase() === 'system');
  const renderedSystemPrompt = systemMsgIndex >= 0
    ? messages[systemMsgIndex].content
        .filter((b): b is TextContentBlock => b.type === 'text')
        .map((b) => b.text)
        .join('\n')
    : undefined;

  // Filter out system message only (shown in amber Prompt collapsible section)
  // First user message stays in MessageList as blue background area
  const filteredSnapshots = messages.filter((_, idx) => idx !== systemMsgIndex);

  const convertedMessages = mergeToolResults(filteredSnapshots.map(convertSnapshot));

  return (
    <div ref={findScrollContainer}>
      {/* Rendered system message — amber Prompt section */}
      {renderedSystemPrompt && (
        <div className="mx-3 mt-1 mb-1 rounded-md border border-amber-300 dark:border-amber-700 bg-amber-50/50 dark:bg-amber-950/30">
          <div
            className="flex items-center gap-1.5 px-2 py-1.5 cursor-pointer hover:bg-amber-100/50 dark:hover:bg-amber-900/30 transition-colors rounded-t-md"
            onClick={() => setShowSystemPrompt(!showSystemPrompt)}
          >
            {showSystemPrompt
              ? <ChevronDown className="w-3 h-3 text-amber-600 dark:text-amber-400 shrink-0" />
              : <ChevronRight className="w-3 h-3 text-amber-600 dark:text-amber-400 shrink-0" />
            }
            <span className="text-xs font-medium text-amber-700 dark:text-amber-300">
              {i18n('Prompt')}
            </span>
          </div>
          {showSystemPrompt && (
            <div className="px-2 pb-2 text-xs text-foreground/80 prose prose-xs dark:prose-invert max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                {renderedSystemPrompt}
              </ReactMarkdown>
            </div>
          )}
        </div>
      )}
      <MessageList messages={convertedMessages} isStreaming={false} disableEdit />
    </div>
  );
};
