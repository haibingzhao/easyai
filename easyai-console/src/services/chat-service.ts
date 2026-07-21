import type { DoneEvent, ErrorEvent, ChatStreamEvent } from '../types/socket-event';
import type { ChatRequest, ChatAttachment } from '../types/socket-request';
import type { ModelOptions } from '@/types/settings';
import { authFetch } from '@/services/api-client';
import { parseSSEStream, SSEConnectionError } from '@/services/sse-parser';
import type { RawSSEEvent } from '@/services/sse-parser';

const API_BASE = '/api/chat';

// Store active resume abort controllers for cleanup
const activeResumeControllers = new Set<AbortController>();

// Store active question answer abort controllers for cleanup
const activeQuestionControllers = new Set<AbortController>();

// Store active permission reply abort controllers for cleanup
const activePermissionControllers = new Set<AbortController>();

interface SSECallbacks {
  onEvent: (event: ChatStreamEvent) => void;
  onDone: (event: DoneEvent) => void;
  onError: (event: ErrorEvent) => void;
}

interface SSEStreamResult {
  completed: boolean; // Whether a 'done' event was received
}

/**
 * Parse a chat SSE stream, routing events to typed callbacks based on event.type.
 * Wraps the generic parseSSEStream with ChatStreamEvent-specific logic.
 */
async function parseChatSSEStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  callbacks: SSECallbacks
): Promise<SSEStreamResult> {
  let receivedDone = false;

  const result = await parseSSEStream(reader, {
    onEvent: (raw: RawSSEEvent) => {
      try {
        const event: ChatStreamEvent = JSON.parse(raw.data);
        if (event.type === 'done') {
          receivedDone = true;
          callbacks.onDone(event as DoneEvent);
        } else if (event.type === 'error') {
          callbacks.onError(event as ErrorEvent);
        } else {
          callbacks.onEvent(event);
        }
      } catch (e) {
        console.error('Failed to parse SSE event:', e, raw.data);
      }
    },
  });

  return { completed: receivedDone || result.completed };
}

export class ChatService {
  private onEvent: (event: ChatStreamEvent) => void;
  private onDone: (event: DoneEvent) => void;
  private onError: (event: ErrorEvent) => void;
  private abortController: AbortController | null = null;

  constructor(callbacks: {
    onEvent: (event: ChatStreamEvent) => void;
    onDone: (event: DoneEvent) => void;
    onError: (event: ErrorEvent) => void;
  }) {
    this.onEvent = callbacks.onEvent;
    this.onDone = callbacks.onDone;
    this.onError = callbacks.onError;
  }

  async sendMessage(request: ChatRequest): Promise<void> {
    this.abortController = new AbortController();

    try {
      const response = await authFetch(`${API_BASE}/`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        signal: this.abortController.signal,
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const result = await parseChatSSEStream(reader, {
        onEvent: this.onEvent,
        onDone: this.onDone,
        onError: this.onError,
      });

      // Stream ended without receiving a 'done' event — abnormal disconnection
      if (!result.completed) {
        this.onError({
          type: 'error',
          reason: 'connection_lost',
          errorMessage: 'Connection lost — the server may have restarted',
        });
      }
    } catch (error) {
      if ((error as Error).name === 'AbortError') return;

      if (error instanceof SSEConnectionError) {
        this.onError({
          type: 'error',
          reason: 'connection_lost',
          errorMessage: 'Connection lost — the server may have restarted',
        });
      } else {
        this.onError({
          type: 'error',
          reason: 'send_failed',
          errorMessage: (error as Error).message,
        });
      }
    }
  }

  abort(): void {
    this.abortController?.abort();
    this.abortController = null;
  }
}

/**
 * Parameters for sending a message to the backend.
 */
export interface SendMessageParams {
  message: string;
  sessionId: string | null;
  agentId: string | null;
  modelId: string;
  projectId?: string | null;
  options?: ModelOptions;
  attachments?: ChatAttachment[];
  onEvent: (event: ChatStreamEvent) => void;
  onDone: (event: DoneEvent) => void;
  onError: (event: ErrorEvent) => void;
}

/**
 * Shared function to send a message to the backend and handle SSE stream.
 * Used by both MessageEditor and InlineEditMessage.
 * Returns the ChatService instance for abort control.
 */
export async function sendMessageToBackend(params: SendMessageParams): Promise<ChatService> {
  const { message, sessionId, agentId, modelId, projectId, options, attachments, onEvent, onDone, onError } = params;

  const chatService = new ChatService({
    onEvent,
    onDone: onDone as (event: DoneEvent) => void,
    onError: onError as (event: ErrorEvent) => void,
  });

  const optionsToSend = options && Object.keys(options).length > 0 ? options : undefined;

  await chatService.sendMessage({
    sessionId: sessionId!,
    projectId: projectId ?? undefined,
    message: message,
    agentId: agentId ?? 'default',
    modelProviderConfigId: modelId,
    options: optionsToSend,
    attachments: attachments && attachments.length > 0 ? attachments : undefined,
  });

  return chatService;
}

/**
 * Resume a cancelled or errored chat session by calling the resume endpoint.
 * Stream SSE events and calls onEvent/onDone/onError callbacks.
 * Returns an abort function that can be used to cancel the resume.
 */
export function resumeSession(
  sessionId: string,
  message: string | undefined,
  callbacks: {
    onEvent: (event: ChatStreamEvent) => void;
    onDone: (event: DoneEvent) => void;
    onError: (event: ErrorEvent) => void;
  }
): { abort: () => void } {
  const abortController = new AbortController();
  activeResumeControllers.add(abortController);

  const execute = async () => {
    try {
      const response = await authFetch(`${API_BASE}/resume`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message }),
        signal: abortController.signal,
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const result = await parseChatSSEStream(reader, callbacks);
      if (!result.completed) {
        callbacks.onError({
          type: 'error',
          reason: 'connection_lost',
          errorMessage: 'Connection lost — the server may have restarted',
        });
      }
    } catch (error) {
      if ((error as Error).name === 'AbortError') return;
      const isConnectionLost = error instanceof SSEConnectionError;
      callbacks.onError({
        type: 'error',
        reason: isConnectionLost ? 'connection_lost' : 'resume_failed',
        errorMessage: isConnectionLost
          ? 'Connection lost — the server may have restarted'
          : (error as Error).message,
      });
    } finally {
      activeResumeControllers.delete(abortController);
    }
  };

  execute();

  return {
    abort: () => {
      abortController.abort();
      activeResumeControllers.delete(abortController);
    },
  };
}

/**
 * Answer a pending question and resume the session.
 * Streams SSE events and calls onEvent/onDone/onError callbacks.
 * Returns an abort function that can be used to cancel the answer.
 */
export function answerQuestion(
  sessionId: string,
  toolCallId: string,
  answers: string[][],
  callbacks: {
    onEvent: (event: ChatStreamEvent) => void;
    onDone: (event: DoneEvent) => void;
    onError: (event: ErrorEvent) => void;
  }
): { abort: () => void } {
  const abortController = new AbortController();
  activeQuestionControllers.add(abortController);

  const execute = async () => {
    try {
      const response = await authFetch(`${API_BASE}/question/${sessionId}/${toolCallId}/answer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ answers }),
        signal: abortController.signal,
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const result = await parseChatSSEStream(reader, callbacks);
      if (!result.completed) {
        callbacks.onError({
          type: 'error',
          reason: 'connection_lost',
          errorMessage: 'Connection lost — the server may have restarted',
        });
      }
    } catch (error) {
      if ((error as Error).name === 'AbortError') return;
      const isConnectionLost = error instanceof SSEConnectionError;
      callbacks.onError({
        type: 'error',
        reason: isConnectionLost ? 'connection_lost' : 'answer_failed',
        errorMessage: isConnectionLost
          ? 'Connection lost — the server may have restarted'
          : (error as Error).message,
      });
    } finally {
      activeQuestionControllers.delete(abortController);
    }
  };

  execute();

  return {
    abort: () => {
      abortController.abort();
      activeQuestionControllers.delete(abortController);
    },
  };
}

/**
 * Manually trigger context compaction for a session.
 * Streams SSE events and calls onEvent/onDone/onError callbacks.
 * Returns an abort function that can be used to cancel the request.
 */
export function compactSession(
  sessionId: string,
  callbacks: {
    onEvent: (event: ChatStreamEvent) => void;
    onDone: (event: DoneEvent) => void;
    onError: (event: ErrorEvent) => void;
  }
): { abort: () => void } {
  const abortController = new AbortController();

  const execute = async () => {
    try {
      const response = await authFetch(`${API_BASE}/session/${sessionId}/compact`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: abortController.signal,
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const result = await parseChatSSEStream(reader, callbacks);
      if (!result.completed) {
        callbacks.onError({
          type: 'error',
          reason: 'connection_lost',
          errorMessage: 'Connection lost — the server may have restarted',
        });
      }
    } catch (error) {
      if ((error as Error).name === 'AbortError') return;
      const isConnectionLost = error instanceof SSEConnectionError;
      callbacks.onError({
        type: 'error',
        reason: isConnectionLost ? 'connection_lost' : 'compact_failed',
        errorMessage: isConnectionLost
          ? 'Connection lost — the server may have restarted'
          : (error as Error).message,
      });
    }
  };

  execute();

  return {
    abort: () => {
      abortController.abort();
    },
  };
}

/**
 * Abort all active SSE streams (resume, question, permission).
 * Called when user cancels the current chat session.
 */
export function abortAllActiveStreams(): void {
  activeResumeControllers.forEach((controller) => controller.abort());
  activeResumeControllers.clear();
  activeQuestionControllers.forEach((controller) => controller.abort());
  activeQuestionControllers.clear();
  activePermissionControllers.forEach((controller) => controller.abort());
  activePermissionControllers.clear();
}

/**
 * Cancel an ongoing chat session by calling the backend /api/chat/cancel endpoint.
 * Used when the frontend is in polling mode (historical running session) and has no
 * local SSE connection to abort — the backend cancel endpoint stops the agent directly.
 */
export async function cancelChat(sessionId: string): Promise<void> {
  try {
    await authFetch(`${API_BASE}/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId }),
    });
  } catch (e) {
    console.warn('[cancelChat] Failed to cancel session:', e);
  }
}

/**
 * Reply to a permission request and resume the session.
 * Streams SSE events and calls onEvent/onDone/onError callbacks.
 * Returns an abort function that can be used to cancel the reply.
 */
export function replyPermission(
  sessionId: string,
  toolCallId: string,
  action: 'allow' | 'deny',
  remember: boolean,
  reason: string | undefined,
  permission: string | undefined,
  pattern: string | undefined,
  callbacks: {
    onEvent: (event: ChatStreamEvent) => void;
    onDone: (event: DoneEvent) => void;
    onError: (event: ErrorEvent) => void;
  }
): { abort: () => void } {
  const abortController = new AbortController();
  activePermissionControllers.add(abortController);

  const execute = async () => {
    try {
      const endpoint = action === 'allow' ? 'allow' : 'deny';
      const response = await authFetch(`${API_BASE}/permission/${sessionId}/${toolCallId}/${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ remember, reason, permission, pattern }),
        signal: abortController.signal,
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const result = await parseChatSSEStream(reader, callbacks);
      if (!result.completed) {
        callbacks.onError({
          type: 'error',
          reason: 'connection_lost',
          errorMessage: 'Connection lost — the server may have restarted',
        });
      }
    } catch (error) {
      if ((error as Error).name === 'AbortError') return;
      const isConnectionLost = error instanceof SSEConnectionError;
      callbacks.onError({
        type: 'error',
        reason: isConnectionLost ? 'connection_lost' : 'permission_reply_failed',
        errorMessage: isConnectionLost
          ? 'Connection lost — the server may have restarted'
          : (error as Error).message,
      });
    } finally {
      activePermissionControllers.delete(abortController);
    }
  };

  execute();

  return {
    abort: () => {
      abortController.abort();
      activePermissionControllers.delete(abortController);
    },
  };
}

/**
 * Check if a session has an active SSE stream on the backend.
 * Used by frontend after page refresh to detect a running agent.
 *
 * @returns streaming: true when DB status is "streaming" (remote);
 *          local: true when this server instance holds the SSE connection.
 */
export async function getStreamingStatus(sessionId: string): Promise<{ streaming: boolean; local: boolean }> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/streaming-status`);
  if (!response.ok) return { streaming: false, local: false };
  return response.json();
}

// ==================== Queue management API ====================

export interface QueuedMessageDto {
  id: string;
  content: string;
  type: 'steer' | 'followUp';
}

/**
 * Add a message to the session queue (steer or followUp).
 * Returns the queue response with the generated queue ID.
 */
export async function addQueueMessage(
  sessionId: string,
  content: string,
  type: 'steer' | 'followUp',
  attachments?: ChatAttachment[]
): Promise<QueuedMessageDto> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/queue`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      content,
      type,
      ...(attachments && attachments.length > 0 ? { attachments } : {}),
    }),
  });
  if (!response.ok) throw new Error(`Failed to add queue message: ${response.status}`);
  return response.json();
}

/**
 * Get the current queue snapshot for a session.
 */
export async function getQueueMessages(sessionId: string): Promise<QueuedMessageDto[]> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/queue`);
  if (!response.ok) return [];
  return response.json();
}

/**
 * Remove a queued message by ID.
 */
export async function removeQueueMessage(sessionId: string, queueId: string): Promise<void> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/queue/${queueId}`, {
    method: 'DELETE',
  });
  if (!response.ok) throw new Error(`Failed to remove queue message: ${response.status}`);
}

/**
 * Update the content of a queued message.
 */
export async function updateQueueMessage(
  sessionId: string,
  queueId: string,
  content: string
): Promise<void> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/queue/${queueId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });
  if (!response.ok) throw new Error(`Failed to update queue message: ${response.status}`);
}

/**
 * Reorder queued messages.
 */
export async function reorderQueueMessages(sessionId: string, ids: string[]): Promise<void> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/queue/reorder`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids }),
  });
  if (!response.ok) throw new Error(`Failed to reorder queue: ${response.status}`);
}
