import React, { useMemo } from 'react';
import { MessageSegments } from './segments/MessageSegments';
import { streamingBlocksToSegments } from '@/services/stores/chat/segment-builder';
import { i18n } from '../../utils/i18n';
import { useProjectStore } from '@/services/stores/project-store';
import type { StreamingBlock } from '@/services/stores/chat/types';

interface StreamingMessageProps {
  blocks: StreamingBlock[];
  cancelReason?: string | null;
  streamingToolOutputs?: Record<string, string>;
}

export const StreamingMessage: React.FC<StreamingMessageProps> = ({ blocks, cancelReason, streamingToolOutputs }) => {
  const workDir = useProjectStore((s) => s.currentProject?.path || '');
  const segments = useMemo(() => streamingBlocksToSegments(blocks), [blocks]);

  return (
    <div className="px-4 flex flex-col gap-3">
      <MessageSegments
        segments={segments}
        isLive
        streamingToolOutputs={streamingToolOutputs}
        workDir={workDir}
      />

      {cancelReason && (
        <div className="mt-2">
          <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted text-muted-foreground">
            {i18n(cancelReason)}
          </span>
        </div>
      )}
    </div>
  );
};
