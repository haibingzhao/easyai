import { authFetch, JSON_HEADERS } from './api-client';
import { parseSSEStream } from './sse-parser';
import type { SocketEvent } from '@/types/socket-event';

export interface QuickAskTurn {
  role: 'user' | 'assistant';
  content: string;
}

export interface QuickAskRequest {
  modelConfigId: string;
  question: string;
  selectedText: string;
  history: QuickAskTurn[];
}

/**
 * Stateless side-panel Q&A stream. Nothing is persisted server-side:
 * the backend runs a dry-run agent, so the main session stays untouched.
 */
export async function streamQuickAsk(
  request: QuickAskRequest,
  signal: AbortSignal,
  onEvent: (event: SocketEvent) => void,
): Promise<void> {
  const response = await authFetch('/api/chat/quick-ask', {
    method: 'POST',
    headers: { ...JSON_HEADERS },
    body: JSON.stringify(request),
    signal,
  });
  if (!response.ok || !response.body) {
    const body = await response.text().catch(() => '');
    throw new Error(body || `Request failed: ${response.status}`);
  }
  await parseSSEStream(response.body.getReader(), {
    onEvent: ({ data }) => {
      if (!data) return;
      onEvent(JSON.parse(data) as SocketEvent);
    },
  });
}
