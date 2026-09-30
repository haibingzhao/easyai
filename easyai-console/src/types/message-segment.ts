import type { ToolCall, ToolResult, SubAgentMessageGroup } from './message';
import type { ToolCallStatus } from './socket-event';
import type { ToolBlockData } from '@/services/stores/chat/types';

/**
 * Ordered render segment of one assistant message.
 * Built from live streaming blocks, from commit, or from history snapshots so that
 * streaming / committed / history messages share a single interleaved renderer.
 */
export type MessageSegment =
  | ThinkingSegment
  | TextSegment
  | ToolSegment
  | SubAgentSegment
  | CompactionSegment;

export interface ThinkingSegment {
  kind: 'thinking';
  id: string;
  content: string;
  durationMs?: number;
  isFinished?: boolean;
}

export interface TextSegment {
  kind: 'text';
  id: string;
  content: string;
}

export interface ToolSegment {
  kind: 'tool';
  id: string;
  toolCall: ToolCall;
  toolResult?: ToolResult;
  status?: ToolCallStatus;
}

export interface SubAgentSegment {
  kind: 'subagent';
  id: string;
  toolCall: ToolCall;
  toolResult?: ToolResult;
  status?: ToolCallStatus;
  agentName: string;
  /** Live sub-agent streaming data (streaming path only) */
  subAgent?: ToolBlockData['subAgent'];
  /** Committed / history sub-agent message group */
  group?: SubAgentMessageGroup;
}

export interface CompactionSegment {
  kind: 'compaction';
  id: string;
  isFinished: boolean;
  compactedCount: number;
  tokensSaved: number;
  durationMs?: number;
  currentTokens?: number;
  timestamp: number;
}
