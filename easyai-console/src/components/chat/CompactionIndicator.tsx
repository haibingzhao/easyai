import React from 'react';
import { Archive } from 'lucide-react';
import type { CustomMessage } from '../../types/message';
import { i18n } from '../../utils/i18n';
import { formatTokenCount, formatDurationMs } from '../../utils/format';

interface CompactionIndicatorProps {
  message: CustomMessage;
}

/**
 * Renders a compaction indicator card showing summary statistics
 * about context compaction that occurred during the session.
 */
export const CompactionIndicator: React.FC<CompactionIndicatorProps> = ({ message }) => {
  const { tokensSaved, durationMs } = message.metadata as { tokensSaved?: number; durationMs?: number };

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
