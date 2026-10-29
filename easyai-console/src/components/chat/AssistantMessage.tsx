import React from 'react';
import type { AssistantMessage as AssistantMessageType, ToolResult } from '../../types/message';
import { MessageSegments } from './segments/MessageSegments';
import { MessageMetaBar } from './MessageMetaBar';
import { segmentsForMessage } from '@/services/stores/chat/segment-builder';
import { useProjectStore } from '@/services/stores/project-store';

interface AssistantMessageProps {
  message: AssistantMessageType;
  toolResults?: ToolResult[];
  isStreaming?: boolean;
}

/**
 * Shared empty default.
 *
 * A literal `= []` default would allocate a new array on every render, which defeats both the
 * memo comparison and the segment cache keyed on `toolResults` identity.
 */
const NO_TOOL_RESULTS: ToolResult[] = [];

/**
 * Memoised: the transcript re-renders on every streaming block tick, and rebuilding the segments
 * of hundreds of committed turns to throw the result away was most of the per-frame cost. The
 * props are referentially stable per load (`message` is rebuilt only when the snapshot is), and
 * the project-store subscription below still re-renders when the workdir changes.
 */
export const AssistantMessage: React.FC<AssistantMessageProps> = React.memo(({ message, toolResults = NO_TOOL_RESULTS, isStreaming = false }) => {
  const workDir = useProjectStore((s) => s.currentProject?.path || '');
  const segments = segmentsForMessage(message, toolResults);

  return (
    <div className="px-4 flex flex-col gap-3">
      <MessageSegments segments={segments} isLive={false} workDir={workDir} />
      <MessageMetaBar messages={[message]} isStreaming={isStreaming} />
    </div>
  );
});
