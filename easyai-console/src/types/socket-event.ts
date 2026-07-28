import type { FileChangeInfo } from './checkpoint';
import type { ContextReferences } from './message';

export type EventType =
  | 'start'
  | 'text_start'
  | 'text_delta'
  | 'text_end'
  | 'thinking_start'
  | 'thinking_delta'
  | 'thinking_end'
  | 'toolcall_start'
  | 'toolcall_delta'
  | 'toolcall_end'
  | 'toolcall_status'
  | 'tool_execution_start'
  | 'tool_execution_update'
  | 'tool_execution_end'
  | 'done'
  | 'error'
  | 'cancelled'
  | 'retry'
  | 'compaction_start'
  | 'compaction_end'
  | 'permission_request'
  | 'message_end'
  | 'user_message_added'
  | 'checkpoint'
  | 'revert'
  | 'goal_status'
  | 'user_message_ack';

interface BaseEvent {
  type: EventType;
}

export interface StartEvent extends BaseEvent {
  type: 'start';
  sessionId?: string;
}

export interface TextStartEvent extends BaseEvent {
  type: 'text_start';
  contentIndex: number;
}

export interface TextDeltaEvent extends BaseEvent {
  type: 'text_delta';
  contentIndex: number;
  delta: string;
  turnId?: number;
  messageId?: string;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export interface TextEndEvent extends BaseEvent {
  type: 'text_end';
  contentIndex: number;
  contentSignature?: string;
  turnId?: number;
  durationMs?: number;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export interface ThinkingStartEvent extends BaseEvent {
  type: 'thinking_start';
  contentIndex: number;
}

export interface ThinkingDeltaEvent extends BaseEvent {
  type: 'thinking_delta';
  contentIndex: number;
  delta: string;
  turnId?: number;
  messageId?: string;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export interface ThinkingEndEvent extends BaseEvent {
  type: 'thinking_end';
  contentIndex: number;
  contentSignature?: string;
  turnId?: number;
  durationMs?: number;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export interface ToolCallStartEvent extends BaseEvent {
  type: 'toolcall_start';
  contentIndex: number;
  id: string;
  toolName: string;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export interface ToolCallDeltaEvent extends BaseEvent {
  type: 'toolcall_delta';
  contentIndex: number;
  id: string;
  delta: string;
}

export interface ToolCallEndEvent extends BaseEvent {
  type: 'toolcall_end';
  contentIndex: number;
  id: string;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export interface ToolCallStatusEvent extends BaseEvent {
  type: 'toolcall_status';
  toolCallId: string;
  toolName: string;
  status: ToolCallStatus;
  turnId?: number;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export type ToolCallStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ToolExecutionStartEvent extends BaseEvent {
  type: 'tool_execution_start';
  toolCallId: string;
  toolName: string;
  args?: Record<string, unknown>;
  subAgentToolCallId?: string;
  subAgentName?: string;
  /** Whether this tool modifies files on disk */
  tracksFileChanges?: boolean;
}

export interface ToolExecutionUpdateEvent extends BaseEvent {
  type: 'tool_execution_update';
  toolCallId: string;
  output: string;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export interface ToolExecutionEndEvent extends BaseEvent {
  type: 'tool_execution_end';
  toolCallId: string;
  toolName: string;
  result?: string;
  isError: boolean;
  exitCode?: number | null;
  mimeType?: string | null;
  truncated?: boolean;
  subAgentToolCallId?: string;
  subAgentName?: string;
  toolUsage?: UsageInfo;
  /** Whether this tool modifies files on disk */
  tracksFileChanges?: boolean;
}

export interface UsageInfo {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
  durationMs?: number;
}

export interface DoneEvent extends BaseEvent {
  type: 'done';
  reason: string;
  usage?: UsageInfo;
  endReason?: string;
}

export interface ErrorEvent extends BaseEvent {
  type: 'error';
  reason: string;
  errorMessage?: string;
  messageId?: string;
  usage?: UsageInfo;
}

export interface CancelledEvent extends BaseEvent {
  type: 'cancelled';
  reason: string;
}

export interface RetryEvent extends BaseEvent {
  type: 'retry';
  attempt: number;
  maxRetries: number;
  backoffMs: number;
  turnId?: number;
}

export interface CompactionStartEvent extends BaseEvent {
  type: 'compaction_start';
  turnId: number;
  reason: string;
  messageCount: number;
}

export interface CompactionEndEvent extends BaseEvent {
  type: 'compaction_end';
  turnId: number;
  summary: string;
  compactedCount: number;
  tokensSaved: number;
  tailStartMessageId?: string;
  currentTokens: number;
  durationMs?: number;
  usage?: UsageInfo;
  /** Session variables extracted during compaction */
  variables?: Record<string, string>;
}

export interface PermissionRequestEvent extends BaseEvent {
  type: 'permission_request';
  toolCallId: string;
  toolName: string;
  permission: string;
  pattern: string;
  arguments?: Record<string, unknown>;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export interface MessageEndEvent extends BaseEvent {
  type: 'message_end';
  messageId: string;
  turnId?: number;
  usage?: UsageInfo;
  subAgentToolCallId?: string;
  subAgentName?: string;
  references?: ContextReferences;
}

export interface UserMessageAddedEvent extends BaseEvent {
  type: 'user_message_added';
  messageId: string;
  content: string;
  metadata?: Record<string, string>;
}

export interface CheckpointEvent extends BaseEvent {
  type: 'checkpoint';
  messageId?: string;
  assistantMessageId?: string;
  snapshotHash?: string;
  filesChanged?: FileChangeInfo[];
  additions?: number;
  deletions?: number;
}

export interface RevertEvent extends BaseEvent {
  type: 'revert';
  messageId: string;
  additions: number;
  deletions: number;
  filesCount: number;
}

export interface GoalStatusEvent extends BaseEvent {
  type: 'goal_status';
  sessionId: string;
  objective: string;
  status: string;
  turnCount: number;
  maxTurns: number;
  elapsedSeconds: number;
  evidence?: string;
  blockedReason?: string;
}

export interface UserMessageAckEvent extends BaseEvent {
  type: 'user_message_ack';
  messageId: string;
}

export type ChatStreamEvent =
  | StartEvent
  | TextStartEvent
  | TextDeltaEvent
  | TextEndEvent
  | ThinkingStartEvent
  | ThinkingDeltaEvent
  | ThinkingEndEvent
  | ToolCallStartEvent
  | ToolCallDeltaEvent
  | ToolCallEndEvent
  | ToolCallStatusEvent
  | ToolExecutionStartEvent
  | ToolExecutionUpdateEvent
  | ToolExecutionEndEvent
  | DoneEvent
  | ErrorEvent
  | CancelledEvent
  | RetryEvent
  | CompactionStartEvent
  | CompactionEndEvent
  | PermissionRequestEvent
  | MessageEndEvent
  | UserMessageAddedEvent
  | CheckpointEvent
  | RevertEvent
  | GoalStatusEvent
  | UserMessageAckEvent;

// Re-export for backward compatibility
export type SocketEvent = ChatStreamEvent;