import type { ToolCallStatus } from '@/types/socket-event';
import type { ToolResult } from '@/types/message';
import type { TodoInfo } from '@/types/todo';
import type {
  ContentBlock,
  TextContentBlock,
  ImageContentBlock,
  ThinkingContentBlock,
  ToolCallContentBlock,
  FileRefContentBlock,
} from '@/services/session-service';

// Type guard functions for ContentBlock
export function isTextBlock(b: ContentBlock): b is TextContentBlock {
  return b.type === 'text';
}

export function isThinkingBlock(b: ContentBlock): b is ThinkingContentBlock {
  return b.type === 'thinking';
}

export function isToolCallBlock(b: ContentBlock): b is ToolCallContentBlock {
  return b.type === 'toolCall';
}

export function isImageBlock(b: ContentBlock): b is ImageContentBlock {
  return b.type === 'image';
}

export function isFileRefBlock(b: ContentBlock): b is FileRefContentBlock {
  return b.type === 'fileRef';
}

export interface ThinkingBlockData {
  type: 'thinking';
  content: string;
  isFinished?: boolean;
  durationMs?: number;
  id: string; // Unique identifier for React key
  messageId?: string; // Message ID from backend (from text_delta/thinking_delta events)
  /** Token count from message_end (outputTokens allocated to this block) */
  tokenCount?: number;
}

export interface TextBlockData {
  type: 'text';
  content: string;
  durationMs?: number;
  id: string; // Unique identifier for React key, avoids component rebuild when tool block insertion shifts index
  messageId?: string; // Message ID from backend (from text_delta/thinking_delta events)
  /** Token count from message_end (outputTokens allocated to this block) */
  tokenCount?: number;
}

/**
 * Streaming-specific tool call type with status for real-time UI.
 * This is separate from the persisted ToolCall type (which has no status).
 */
export interface StreamingToolCall {
  id: string;
  toolName: string;
  args: string;
  status: ToolCallStatus;
}

export interface ToolBlockData {
  type: 'tool';
  toolCall: StreamingToolCall;
  toolResult?: ToolResult;
  /** Sub-agent data merged into the same tool card (for SubAgentTool calls) */
  subAgent?: {
    agentName: string;
    blocks: StreamingBlock[]; // Sub-agent's own thinking/text/tool blocks
    isFinished: boolean;
    /** Accumulated token usage from sub-agent's LLM calls (for SubAgent header + TimelineBar display) */
    accumulatedUsage: { inputTokens: number; outputTokens: number; cacheReadTokens: number };
    /** Latest todo snapshot from the sub-agent's todo_write calls */
    todos?: TodoInfo[];
  };
}

/**
 * Streaming-specific marker for context compaction that occurred mid-stream.
 * Rendered inline within the streaming block list so the indicator appears at the
 * correct position (right after the compacted turns) instead of being placed above
 * all streaming content (which happens when it is added to the committed messages).
 */
export interface CompactionBlockData {
  type: 'compaction';
  compactedCount: number;
  tokensSaved: number;
  durationMs?: number;
  currentTokens?: number;
  timestamp: number;
  id: string; // Unique identifier for React key
}

export type StreamingBlock = ThinkingBlockData | TextBlockData | ToolBlockData | CompactionBlockData;

/** LLM timeout retry state for UI indicator display */
export interface RetryInfo {
  attempt: number;
  maxRetries: number;
}
