/**
 * SubAgentToolMessage — thin adapter for `toolName === 'task'`.
 * Live sub-agent transcripts normally render through SubAgentRow via MessageSegments;
 * this keeps the TOOL_RENDERERS.task registration working for any consumer that
 * renders a task tool call directly.
 */

import type { ToolMessageProps } from './types';
import type { SubAgentSegment } from '@/types/message-segment';
import { SubAgentRow } from '../segments/SubAgentRow';

export function SubAgentToolMessage({ toolCall, result, status, subAgent, workDir }: ToolMessageProps) {
  const segment: SubAgentSegment = {
    kind: 'subagent',
    id: toolCall.id,
    toolCall,
    toolResult: result,
    status,
    agentName: subAgent?.agentName ?? '',
    subAgent,
  };
  return (
    <SubAgentRow
      segment={segment}
      isLive={!!subAgent}
      workDir={workDir}
    />
  );
}
