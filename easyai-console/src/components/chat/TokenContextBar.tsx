import React, { useCallback, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { formatTokenCount } from '@/utils/format';
import { i18n } from '@/utils/i18n';
import { compactSession } from '@/services/chat-service';
import type { ErrorEvent } from '@/types/socket-event';
import { TimelineBar } from './TimelineBar';

/** Compress icon: chevrons pointing inward toward center line (from kilo-code) */
const CompressIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="square" className={className}>
    <path d="M5 10H15" />
    <path d="M6.667 4.167L10 7.5L13.333 4.167" />
    <path d="M6.667 15.833L10 12.5L13.333 15.833" />
  </svg>
);

/** Chevron down icon for expand/collapse toggle */
const ChevronDownIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <polyline points="6 9 12 15 18 9" />
  </svg>
);

export const TokenContextBar: React.FC = () => {
  const [expanded, setExpanded] = useState(false);
  const {
    sessionId,
    messages,
    streamingBlocks,
    cumulativeUsage,
    contextTokens,
    contextWindow,
    isStreaming,
    isAwaitingAskQuestion,
    isCompacting,
    setCompacting,
    handleEvent,
  } = useChatStore();

  const awaitingAskQuestion = isAwaitingAskQuestion();
  const isLocked = isStreaming || awaitingAskQuestion;

  // Use contextTokens (actual context window size) for percentage calculation
  const percentage = Math.round((contextTokens / contextWindow) * 100);
  const isTooSmall = !isCompacting && percentage < 15;

  // Determine compact button disabled state and tooltip
  const compactDisabled = isLocked || isCompacting || isTooSmall;
  const compactTooltip = isCompacting
    ? i18n('Compacting session, please wait...')
    : isTooSmall
      ? i18n('Content too short, no need to compact')
      : isLocked
        ? i18n('Outputting, please wait...')
        : i18n('Compact Current Session');

  const handleCompact = useCallback(() => {
    if (!sessionId) return;
    // Show the loading state immediately on click. The compaction_start SSE event also
    // sets isCompacting, but it can take a moment to arrive; setting it here gives instant
    // feedback (spinner + "compacting" tooltip) and disables the button to prevent a
    // double-trigger.
    setCompacting(true);
    compactSession(sessionId, {
      onEvent: (event) => handleEvent(event),
      onDone: () => {
        // Compaction complete — cumulativeUsage already updated via compaction_end event
      },
      onError: (event) => {
        // Reset the loading state so the spinner does not get stuck if compaction fails.
        setCompacting(false);
        handleEvent(event as ErrorEvent);
      },
    });
  }, [sessionId, handleEvent, setCompacting]);

  // Don't render if no active session
  if (!sessionId) {
    return null;
  }

  // Cache tokens
  const cacheReadTokens = cumulativeUsage?.cacheReadTokens ?? 0;
  const cacheWriteTokens = cumulativeUsage?.cacheWriteTokens ?? 0;
  const hasCache = cacheReadTokens > 0 || cacheWriteTokens > 0;
  const totalCacheTokens = cacheReadTokens + cacheWriteTokens;

  return (
    <div className={expanded ? 'px-4 py-1.5' : 'px-4 py-1'}>
      <div className="flex items-center gap-2" style={expanded ? { marginBottom: 'calc(var(--spacing) * 1.5)' } : undefined}>
        <span className="text-sm font-semibold tabular-nums">
          {percentage}% {formatTokenCount(contextTokens)} / {formatTokenCount(contextWindow)}
        </span>
        <span className="text-xs text-muted-foreground">
          {i18n('context used')}
        </span>
        <button
          onClick={handleCompact}
          disabled={compactDisabled}
          className="p-1 rounded hover:bg-muted transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          title={compactTooltip}
        >
          {isCompacting
            ? <Loader2 className="size-3.5 text-muted-foreground animate-spin" />
            : <CompressIcon className="size-3.5 text-muted-foreground" />
          }
        </button>
        <button
          onClick={() => setExpanded((v) => !v)}
          className="ml-auto p-1 rounded hover:bg-muted transition-colors"
          title={expanded ? i18n('Collapse') : i18n('Expand')}
        >
          <ChevronDownIcon className={`size-3.5 text-muted-foreground transition-transform ${expanded ? '' : '-rotate-90'}`} />
        </button>
      </div>

      {expanded && (
        <>
          {/* Timeline bar — colored bars representing message activity */}
          <TimelineBar messages={messages} streamingBlocks={streamingBlocks} />

          {/* Token total consumption */}
          <div className="flex items-center gap-3 text-xs text-muted-foreground mt-1">
            <span>{i18n('Token total consumption')}</span>
            <span className="tabular-nums">
              <span className="text-muted-foreground">↑</span> {formatTokenCount(cumulativeUsage?.inputTokens ?? 0)}
              <span className="text-muted-foreground ml-1.5">↓</span> {formatTokenCount(cumulativeUsage?.outputTokens ?? 0)}
            </span>
            {hasCache && (
              <span className="tabular-nums">
                <span className="text-muted-foreground ml-1.5">↓</span> cache {formatTokenCount(totalCacheTokens)}
              </span>
            )}
          </div>
        </>
      )}
    </div>
  );
};
