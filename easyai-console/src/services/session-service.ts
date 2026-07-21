import type { SessionResponse } from '../types/socket-request';
import type { ToolCallStatus } from '../types/socket-event';
import type { TodoInfo } from '../types/todo';
import { authFetch } from '@/services/api-client';

/**
 * Snapshot of a tool execution result from the backend.
 * Used in MessageSnapshot for role === 'tool'.
 */
export interface ToolResultSnapshot {
  toolCallId: string;
  toolName: string;
  result: string;
  exitCode?: number | null;
  mimeType?: string;
  isError?: boolean;
  truncated?: boolean;
}

const API_BASE = '/api/chat';

export interface SessionListItem {
  id: string;
  title: string | null;
  createdAt: number;
  updatedAt: number;
  messageCount: number;
  /** True when DB status is "streaming" (session has an active SSE stream somewhere) */
  streaming?: boolean;
}

export type ContentBlockType = 'text' | 'image' | 'thinking' | 'toolCall' | 'custom' | 'fileRef';

export interface ContentBlockBase {
  type: ContentBlockType;
}

export interface TextContentBlock extends ContentBlockBase {
  type: 'text';
  text: string;
  textSignature?: string | null;
  durationMs?: number | null;
}

export interface ImageContentBlock extends ContentBlockBase {
  type: 'image';
  data: string;
  mimeType: string;
}

export interface ThinkingContentBlock extends ContentBlockBase {
  type: 'thinking';
  thinking: string;
  thinkingSignature?: string | null;
  redacted: boolean;
  durationMs?: number | null;
}

export interface ToolCallContentBlock extends ContentBlockBase {
  type: 'toolCall';
  id: string;
  name: string;
  arguments: string;
  result?: string;
  thoughtSignature?: string | null;
  status: ToolCallStatus;
}

export interface CustomContentBlock extends ContentBlockBase {
  type: 'custom';
  customType: string;
  metadata?: Record<string, unknown>;
}

export interface FileRefContentBlock extends ContentBlockBase {
  type: 'fileRef';
  filePath: string;
  name: string;
  mimeType: string;
}

export interface ToolResultContentBlock {
  type: 'toolResult';
  toolCallId: string;
  toolName: string;
  output: string;
  exitCode?: number | null;
  durationMs?: number | null;
  mimeType?: string;
  isError?: boolean;
  truncated?: boolean;
  /** Token usage from this tool execution (e.g., sub-agent LLM calls) */
  usage?: UsageSnapshot;
}

export type ContentBlock = TextContentBlock | ImageContentBlock | ThinkingContentBlock | ToolCallContentBlock | ToolResultContentBlock | CustomContentBlock | FileRefContentBlock;

export interface UsageSnapshot {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
  durationMs?: number;
}

/** Snapshot of context references (memories and rules) from the backend */
export interface ReferencesSnapshot {
  memories: { name: string; description: string; type: string; scope: string }[];
  rules: { name: string; source: string }[];
}

export interface MessageSnapshot {
  id?: string;
  role: string;
  content: ContentBlock[];
  timestamp: number;
  /** Stop reason for assistant messages (STOP, TOOL_USE, ABORTED, etc.) */
  stopReason?: string | null;
  /** Tool results for role === 'tool' messages */
  toolResults?: ToolResultSnapshot[];
  /** Metadata from user/custom messages (e.g., isCompactionSummary) */
  metadata?: Record<string, string> | null;
  /** Usage data for assistant messages */
  usage?: UsageSnapshot | null;
  /** Timestamp when this message was compacted (null = not compacted) */
  compactedAt?: number | null;
  /** Parent message ID for sub-agent messages (null = top-level message) */
  parentMessageId?: string | null;
  /** Parent tool call ID for sub-agent messages (null = top-level message) */
  parentToolCallId?: string | null;
  /** Context references (memories and rules) for assistant messages */
  references?: ReferencesSnapshot | null;
}

export interface SessionDetail {
  id: string;
  title: string | null;
  createdAt: number;
  updatedAt: number;
  messageCount: number;
  messages: MessageSnapshot[];
  pendingPermission?: PendingPermissionInfo | null;
  /** Why the last agent execution ended (e.g. "max_iterations"). Absent = normal. */
  endReason?: string | null;
  /** Agent ID from the last message (derived server-side) */
  lastAgentId?: string | null;
  /** Model config ID from the last message (derived server-side) */
  lastConfigId?: string | null;
}

/**
 * Response from the incremental message fetch endpoint.
 * Contains only new messages plus metadata for fallback decisions.
 */
export interface SessionMessagesAfterResponse {
  sessionId: string;
  messages: MessageSnapshot[];
  /** True if compaction occurred after the given timestamp — historical messages may have changed. */
  compactionOccurredAfter: boolean;
  /** Session.contentUpdatedAt dirty marker — bumped only by updateMessage(), not by updateStatus(). */
  contentUpdatedAt: number;
  streaming: boolean;
  /** Pending permission request, if any. */
  pendingPermission?: PendingPermissionInfo | null;
  /** Why the last agent execution ended (e.g. "max_iterations"). Absent = normal. */
  endReason?: string | null;
}

export interface PendingPermissionInfo {
  toolCallId: string;
  toolName: string;
  permission: string;
  pattern: string;
  arguments?: Record<string, unknown>;
  subAgentToolCallId?: string;
  subAgentName?: string;
}

export class SessionService {
  async createSession(): Promise<string> {
    const response = await authFetch(`${API_BASE}/session`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    });

    if (!response.ok) {
      throw new Error('Failed to create session');
    }

    const data: SessionResponse = await response.json();
    return data.sessionId;
  }

  async listSessions(limit: number = 10, offset: number = 0, projectId?: string): Promise<{ sessions: SessionListItem[], hasMore: boolean }> {
    const params = new URLSearchParams();
    params.set('limit', String(limit));
    params.set('offset', String(offset));
    if (projectId) params.set('projectId', projectId);
    const response = await authFetch(`${API_BASE}/sessions?${params.toString()}`);
    if (!response.ok) {
      throw new Error('Failed to list sessions');
    }
    return response.json();
  }

  async getSessionDetail(id: string): Promise<SessionDetail> {
    const response = await authFetch(`${API_BASE}/session/${id}`);
    if (!response.ok) {
      throw new Error('Failed to get session detail');
    }
    return response.json();
  }

  /**
   * Fetch messages created after [afterTimestamp] for incremental recovery.
   * Also returns compaction/dirty-marker metadata for fallback decisions.
   */
  async getSessionMessagesAfter(id: string, afterTimestamp: number): Promise<SessionMessagesAfterResponse> {
    const response = await authFetch(`${API_BASE}/session/${id}/messages?after=${afterTimestamp}`);
    if (!response.ok) {
      throw new Error('Failed to get messages after timestamp');
    }
    return response.json();
  }

  async deleteSession(sessionId: string): Promise<void> {
    const response = await authFetch(`${API_BASE}/session/${sessionId}`, {
      method: 'DELETE',
    });

    if (!response.ok) {
      throw new Error('Failed to delete session');
    }
  }

  async closeSession(sessionId: string): Promise<void> {
    return this.deleteSession(sessionId);
  }

  async getActiveSessionCount(): Promise<number> {
    const response = await authFetch(`${API_BASE}/sessions/count`);
    const data = await response.json();
    return data.count;
  }

  async getTodos(sessionId: string): Promise<TodoInfo[]> {
    const response = await authFetch(`${API_BASE}/${sessionId}/todos`);
    if (!response.ok) {
      return [];
    }
    return response.json();
  }

  async getGroupedTodos(sessionId: string): Promise<{
    main: TodoInfo[];
    subAgents: Array<{ agentName: string; todos: TodoInfo[] }>;
  }> {
    const response = await authFetch(`${API_BASE}/${sessionId}/todos/grouped`);
    if (!response.ok) {
      return { main: [], subAgents: [] };
    }
    return response.json();
  }

  async healthCheck(): Promise<boolean> {
    try {
      const response = await authFetch(`${API_BASE}/health`);
      const data = await response.json();
      return data.status === 'ok';
    } catch {
      return false;
    }
  }
}

export const sessionService = new SessionService();
