import React, { useMemo } from 'react';
import type { AssistantMessage } from '../../types/message';
import { MessageSegments } from './segments/MessageSegments';
import { MessageMetaBar } from './MessageMetaBar';
import { segmentsForMessage } from '@/services/stores/chat/segment-builder';
import { useProjectStore } from '@/services/stores/project-store';

interface AssistantMessageClusterProps {
  /** Consecutive assistant messages of one backend turn, in arrival order */
  messages: AssistantMessage[];
}

/**
 * A backend turn spans one assistant message per LLM round, so consecutive
 * messages render as one block: their segments are concatenated and folded into
 * shared process groups instead of one group per round.
 */
export const AssistantMessageCluster: React.FC<AssistantMessageClusterProps> = ({ messages }) => {
  const workDir = useProjectStore((s) => s.currentProject?.path || '');

  const segments = useMemo(
    () => messages.flatMap((message, messageIndex) =>
      segmentsForMessage(message).map(segment => ({ ...segment, id: `${messageIndex}:${segment.id}` }))
    ),
    [messages],
  );

  return (
    <div className="px-4 flex flex-col gap-3">
      <MessageSegments segments={segments} isLive={false} workDir={workDir} />
      <MessageMetaBar messages={messages} />
    </div>
  );
};
