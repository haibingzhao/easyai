import type { AssistantMessage, ToolCall, ToolResult } from '@/types/message';
import type { MessageSegment } from '@/types/message-segment';
import type { StreamingBlock } from './types';

/**
 * Map live streaming blocks 1:1 to ordered render segments.
 * Tool blocks carrying sub-agent data become `subagent` segments.
 */
export function streamingBlocksToSegments(blocks: StreamingBlock[]): MessageSegment[] {
  const segments: MessageSegment[] = [];
  for (const block of blocks) {
    if (block.type === 'thinking') {
      segments.push({
        kind: 'thinking',
        id: block.id,
        content: block.content,
        durationMs: block.durationMs,
        isFinished: block.isFinished,
      });
    } else if (block.type === 'text') {
      segments.push({ kind: 'text', id: block.id, content: block.content });
    } else if (block.type === 'tool') {
      const toolCall: ToolCall = {
        id: block.toolCall.id,
        toolName: block.toolCall.toolName,
        args: block.toolCall.args,
      };
      if (block.subAgent) {
        segments.push({
          kind: 'subagent',
          id: block.toolCall.id,
          toolCall,
          toolResult: block.toolResult,
          status: block.toolCall.status,
          agentName: block.subAgent.agentName,
          subAgent: block.subAgent,
        });
      } else {
        segments.push({
          kind: 'tool',
          id: block.toolCall.id,
          toolCall,
          toolResult: block.toolResult,
          status: block.toolCall.status,
        });
      }
    } else if (block.type === 'compaction') {
      segments.push({
        kind: 'compaction',
        id: block.id,
        isFinished: block.isFinished,
        compactedCount: block.compactedCount,
        tokensSaved: block.tokensSaved,
        durationMs: block.durationMs,
        currentTokens: block.currentTokens,
        timestamp: block.timestamp,
      });
    }
  }
  return segments;
}

/**
 * Fallback builder for messages without ordered segments: legacy fixed order
 * (thinking → text → tool calls), same as the pre-segment renderer.
 */
export function messageToSegments(message: AssistantMessage): MessageSegment[] {
  const segments: MessageSegment[] = [];
  const resultMap = new Map((message.toolResults ?? []).map(r => [r.id, r]));
  const groups = new Map((message.subAgentMessages ?? []).map(g => [g.toolCallId, g]));
  let index = 0;

  if (message.thinking && message.thinking.trim()) {
    segments.push({
      kind: 'thinking',
      id: `seg-fallback-${message.messageId ?? 'msg'}-${index++}`,
      content: message.thinking,
      durationMs: message.thinkingDurationMs,
      isFinished: true,
    });
  }
  if (message.content && message.content.trim()) {
    segments.push({ kind: 'text', id: `seg-fallback-${message.messageId ?? 'msg'}-${index++}`, content: message.content });
  }
  for (const toolCall of message.toolCalls ?? []) {
    const toolResult = resultMap.get(toolCall.id);
    const status = toolResult ? (toolResult.isError ? 'FAILED' as const : 'COMPLETED' as const) : 'PENDING' as const;
    const group = groups.get(toolCall.id);
    if (group) {
      segments.push({ kind: 'subagent', id: toolCall.id, toolCall, toolResult, status, agentName: group.agentName, group });
    } else {
      segments.push({ kind: 'tool', id: toolCall.id, toolCall, toolResult, status });
    }
  }
  return segments;
}

/**
 * Resolve the segments to render for a committed/history assistant message:
 * prefer ordered `message.segments`, fall back to legacy order, then enrich
 * tool/subagent segments with results (from merged tool messages) and attach
 * sub-agent groups by toolCallId. Tool segments backed by a sub-agent group are
 * upgraded to `subagent` segments.
 */
export function resolveSegments(message: AssistantMessage, resultMap: Map<string, ToolResult>): MessageSegment[] {
  const base = message.segments ?? messageToSegments(message);
  const groups = new Map((message.subAgentMessages ?? []).map(g => [g.toolCallId, g]));

  return base.map((segment) => {
    if (segment.kind !== 'tool' && segment.kind !== 'subagent') return segment;

    const toolResult = segment.toolResult ?? resultMap.get(segment.toolCall.id);
    const status = segment.status
      ?? (toolResult ? (toolResult.isError ? 'FAILED' as const : 'COMPLETED' as const) : 'PENDING' as const);
    const group = segment.kind === 'subagent'
      ? (segment.group ?? groups.get(segment.toolCall.id))
      : groups.get(segment.toolCall.id);

    if (group) {
      return {
        kind: 'subagent' as const,
        id: segment.id,
        toolCall: segment.toolCall,
        toolResult,
        status,
        agentName: group.agentName,
        group,
      };
    }
    if (segment.kind === 'subagent') {
      return { ...segment, toolResult, status };
    }
    return { ...segment, toolResult, status };
  });
}

/**
 * Convenience wrapper: resolve a message's own tool results into its segments.
 * `extraResults` lets callers supply results that live outside the message.
 */
export function segmentsForMessage(
  message: AssistantMessage,
  extraResults: ToolResult[] = [],
): MessageSegment[] {
  const resultMap = new Map<string, ToolResult>();
  for (const result of [...(message.toolResults ?? []), ...extraResults]) {
    resultMap.set(result.id, result);
  }
  return resolveSegments(message, resultMap);
}
