import React from 'react';
import type { MessageSegment } from '@/types/message-segment';
import { ThinkingBlock } from '../ThinkingBlock';
import { ToolMessage } from '../ToolMessage';
import { CompactionIndicator } from '../CompactionIndicator';
import { TextMarkdown } from './TextMarkdown';

export interface SegmentRowProps {
  segment: MessageSegment;
  /** True while the owning message is still streaming */
  isLive: boolean;
  /** Render tool segments as compact rows (inside a process group) */
  compact?: boolean;
  streamingToolOutputs?: Record<string, string>;
  workDir?: string;
}

/**
 * Render one segment as a flat row: thinking row, markdown text, tool row/card or
 * compaction indicator. Sub-agent segments are rendered by SubAgentRow.
 */
export const SegmentRow: React.FC<SegmentRowProps> = ({
  segment,
  isLive,
  compact,
  streamingToolOutputs,
  workDir,
}) => {
  switch (segment.kind) {
    case 'thinking':
      if (!segment.content.trim()) return null;
      return (
        <ThinkingBlock
          content={segment.content}
          isStreaming={isLive}
          isFinished={segment.isFinished}
          durationMs={segment.durationMs}
        />
      );

    case 'text':
      if (!segment.content.trim()) return null;
      return <TextMarkdown content={segment.content} />;

    case 'tool':
      return (
        <ToolMessage
          toolCall={segment.toolCall}
          result={segment.toolResult}
          status={segment.status}
          streamingOutput={streamingToolOutputs?.[segment.toolCall.id]}
          workDir={workDir}
          compact={compact}
        />
      );

    case 'compaction':
      return (
        <CompactionIndicator
          isCompacting={!segment.isFinished}
          message={{
            role: 'custom',
            customType: 'compaction',
            metadata: {
              compactedCount: segment.compactedCount,
              tokensSaved: segment.tokensSaved,
              durationMs: segment.durationMs,
              currentTokens: segment.currentTokens,
              isCompactionIndicator: true,
            },
            timestamp: segment.timestamp,
          }}
        />
      );

    default:
      return null;
  }
};
