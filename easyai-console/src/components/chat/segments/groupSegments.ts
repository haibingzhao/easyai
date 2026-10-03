import type { MessageSegment } from '@/types/message-segment';
import { TOOL_NAMES } from '@/constants/tools';

/**
 * Tools that are interactive or carry the deliverable itself, so they stay as
 * standalone cards instead of being folded into a process group.
 */
const UNGROUPED_TOOLS: ReadonlySet<string> = new Set([
  TOOL_NAMES.ASK_QUESTION,
  TOOL_NAMES.RENDER_VISUAL,
  'task',
  'run_swarm',
  'generate_image',
  'generate_speech',
  'generate_video',
  'fetch_media',
]);

export interface ProcessGroupNode {
  type: 'group';
  id: string;
  items: MessageSegment[];
  toolCount: number;
  failureCount: number;
  /** True when nothing follows the group in the message (live tail) */
  isTrailing: boolean;
}

export interface SingleSegmentNode {
  type: 'segment';
  id: string;
  segment: MessageSegment;
}

export type RenderNode = ProcessGroupNode | SingleSegmentNode;

function isFailure(segment: MessageSegment): boolean {
  if (segment.kind !== 'tool') return false;
  return segment.status === 'FAILED' || segment.toolResult?.isError === true;
}

/**
 * Fold consecutive thinking + tool segments into collapsible process groups.
 * A non-empty text segment, a sub-agent segment, a compaction marker or an
 * ungrouped tool closes the current group and renders standalone.
 */
export function groupSegments(segments: MessageSegment[]): RenderNode[] {
  const nodes: RenderNode[] = [];
  let buffer: MessageSegment[] = [];
  let bufferFirstId = '';

  const flush = () => {
    if (buffer.length === 0) return;
    const items = buffer;
    const firstId = bufferFirstId;
    buffer = [];
    const toolCount = items.filter((s) => s.kind === 'tool').length;
    nodes.push({
      type: 'group',
      id: `group-${firstId}`,
      items,
      toolCount,
      failureCount: items.filter(isFailure).length,
      isTrailing: false,
    });
  };

  for (const segment of segments) {
    if (segment.kind === 'thinking') {
      if (segment.content.trim()) {
        if (buffer.length === 0) bufferFirstId = segment.id;
        buffer.push(segment);
      }
      continue;
    }
    if (segment.kind === 'tool' && !UNGROUPED_TOOLS.has(segment.toolCall.toolName)) {
      if (buffer.length === 0) bufferFirstId = segment.id;
      buffer.push(segment);
      continue;
    }
    if (segment.kind === 'text' && !segment.content.trim()) continue;

    flush();
    nodes.push({ type: 'segment', id: segment.id, segment });
  }
  flush();

  const last = nodes[nodes.length - 1];
  if (last && last.type === 'group') last.isTrailing = true;

  return nodes;
}
