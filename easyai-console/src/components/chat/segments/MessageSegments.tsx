import React, { useMemo } from 'react';
import type { MessageSegment } from '@/types/message-segment';
import { groupSegments } from './groupSegments';
import { ProcessGroup } from './ProcessGroup';
import { SegmentRow } from './SegmentRow';
import { SubAgentRow } from './SubAgentRow';

interface MessageSegmentsProps {
  segments: MessageSegment[];
  /** True while the message is still streaming */
  isLive: boolean;
  streamingToolOutputs?: Record<string, string>;
  workDir?: string;
}

/**
 * Single renderer for assistant messages: live streaming, committed and history
 * messages all render through here, so intermediate process stays folded and the
 * result text leads.
 */
export const MessageSegments: React.FC<MessageSegmentsProps> = ({
  segments,
  isLive,
  streamingToolOutputs,
  workDir,
}) => {
  const nodes = useMemo(() => groupSegments(segments), [segments]);

  return (
    <div className="flex flex-col gap-1.5">
      {nodes.map((node) => {
        if (node.type === 'group') {
          return (
            <ProcessGroup
              key={node.id}
              toolCount={node.toolCount}
              failureCount={node.failureCount}
              isActive={isLive && node.isTrailing}
            >
              {node.items.map((item) => (
                <SegmentRow
                  key={item.id}
                  segment={item}
                  isLive={isLive}
                  compact
                  streamingToolOutputs={streamingToolOutputs}
                  workDir={workDir}
                />
              ))}
            </ProcessGroup>
          );
        }

        if (node.segment.kind === 'subagent') {
          return (
            <SubAgentRow
              key={node.id}
              segment={node.segment}
              isLive={isLive}
              streamingToolOutputs={streamingToolOutputs}
              workDir={workDir}
            />
          );
        }

        return (
          <SegmentRow
            key={node.id}
            segment={node.segment}
            isLive={isLive}
            streamingToolOutputs={streamingToolOutputs}
            workDir={workDir}
          />
        );
      })}
    </div>
  );
};
