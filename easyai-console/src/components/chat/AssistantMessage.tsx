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

export const AssistantMessage: React.FC<AssistantMessageProps> = ({ message, toolResults = [], isStreaming = false }) => {
  const workDir = useProjectStore((s) => s.currentProject?.path || '');
  const segments = segmentsForMessage(message, toolResults);

  return (
    <div className="px-4 flex flex-col gap-3">
      <MessageSegments segments={segments} isLive={false} workDir={workDir} />
      <MessageMetaBar messages={[message]} isStreaming={isStreaming} />
    </div>
  );
};
