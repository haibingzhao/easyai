import React, { useState, useEffect, useRef } from 'react';
import { Archive, Loader2 } from 'lucide-react';
import type { CustomMessage } from '../../types/message';
import { i18n } from '../../utils/i18n';
import { formatTokenCount, formatDurationMs } from '../../utils/format';

interface CompactionIndicatorProps {
  message: CustomMessage;
  /**
   * True while compaction is in progress (between compaction_start and compaction_end).
   * Renders a spinner plus a live elapsed timer (mirroring ThinkingBlock); once it flips
   * to false the final stats from message.metadata are shown.
   */
  isCompacting?: boolean;
}

/**
 * Renders a compaction indicator card.
 * - While compacting (isCompacting): a spinner with a live elapsed timer.
 * - Once done: summary statistics about the context compaction that occurred.
 */
export const CompactionIndicator: React.FC<CompactionIndicatorProps> = ({ message, isCompacting }) => {
  const { tokensSaved, durationMs } = message.metadata as { tokensSaved?: number; durationMs?: number };

  // Live elapsed timer while compacting (same approach as ThinkingBlock).
  const [elapsedMs, setElapsedMs] = useState(0);
  const startTimeRef = useRef<number | null>(null);

  // Start the timer the first time the block is seen as in-progress.
  useEffect(() => {
    if (isCompacting && startTimeRef.current === null) {
      startTimeRef.current = Date.now();
    }
  }, [isCompacting]);

  // Tick every second while compacting.
  useEffect(() => {
    if (!isCompacting) return;
    const interval = setInterval(() => {
      if (startTimeRef.current !== null) {
        setElapsedMs(Date.now() - startTimeRef.current);
      }
    }, 1000);
    return () => clearInterval(interval);
  }, [isCompacting]);

  if (isCompacting) {
    return (
      <div className="flex justify-start mx-4 my-1">
        <div className="border-l-2 border-border bg-muted/50 rounded-l-md">
          <div className="flex items-center gap-2 px-3 py-2 text-sm text-muted-foreground">
            <Loader2 className="w-4 h-4 flex-shrink-0 animate-spin" />
            <span>{i18n('Compacting context')}</span>
            {elapsedMs > 0 && (
              <span className="text-muted-foreground/60">· {formatDurationMs(elapsedMs, { precision: 0 })}</span>
            )}
          </div>
        </div>
      </div>
    );
  }

  const durationText = durationMs != null && durationMs > 0 ? formatDurationMs(durationMs, { precision: 0 }) : '';

  return (
    <div className="flex justify-start mx-4 my-1">
      <div className="border-l-2 border-border bg-muted/50 rounded-l-md">
        <div className="flex items-center gap-2 px-3 py-2 text-sm text-muted-foreground">
          <Archive className="w-4 h-4 flex-shrink-0" />
          <span>{i18n('Session compacted')}</span>
          {durationText && (
            <>
              <span className="text-muted-foreground/60">· {durationText}</span>
            </>
          )}
          {tokensSaved != null && tokensSaved > 0 && (
            <>
              <span className="text-muted-foreground/60">· {i18n('Compacted {tokens} tokens').replace('{tokens}', formatTokenCount(tokensSaved))}</span>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
