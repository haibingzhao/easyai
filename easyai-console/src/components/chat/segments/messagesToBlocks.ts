import type { Message } from '@/types/message';
import type { StreamingBlock } from '@/services/stores/chat/types';

/**
 * Convert a committed / history sub-agent message list into streaming blocks so the
 * sub-agent transcript can be rendered with the same row components as a live run.
 */
export function messagesToBlocks(messages: Message[]): StreamingBlock[] {
  const blocks: StreamingBlock[] = [];
  let blockId = 0;

  for (const msg of messages) {
    if (msg.role !== 'assistant') continue;

    if (msg.thinking?.trim()) {
      blocks.push({
        type: 'thinking',
        content: msg.thinking,
        isFinished: true,
        durationMs: msg.thinkingDurationMs,
        id: `sub-hist-${blockId++}`,
      });
    }
    if (msg.content?.trim()) {
      blocks.push({ type: 'text', content: msg.content, id: `sub-hist-${blockId++}` });
    }
    for (const tc of msg.toolCalls ?? []) {
      const result = msg.toolResults?.find((r) => r.id === tc.id);
      blocks.push({
        type: 'tool',
        toolCall: {
          id: tc.id,
          toolName: tc.toolName,
          args: tc.args,
          status: result ? (result.isError ? 'FAILED' : 'COMPLETED') : 'PENDING',
        },
        toolResult: result,
      });
    }
  }

  return blocks;
}
