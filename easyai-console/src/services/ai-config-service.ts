import { authFetch } from '@/services/api-client';
import { parseSSEStream, SSEConnectionError } from '@/services/sse-parser';
import type {
  AiConfigGenerateRequest,
  AiConfigGenerateResponse,
  ConfigValidationResult,
} from '@/types/ai-config';

const API_BASE = '/api/ai-config';

/**
 * Decode a JSON-encoded SSE data string back to plain text.
 * The backend uses encodeSseData() to wrap text in JSON quotes,
 * so we JSON.parse it. Falls back to raw data for backward compatibility.
 */
function decodeSseData(raw: string): string {
  if (raw.startsWith('"') && raw.endsWith('"')) {
    try {
      return JSON.parse(raw);
    } catch {
      // Fallback: return raw if parse fails
    }
  }
  return raw;
}

class AiConfigService {
  /**
   * Generate config via SSE streaming using AgentLoop with tools.
   * Returns an abort handle for cancellation.
   */
  generateConfigStream(
    request: AiConfigGenerateRequest,
    callbacks: {
      onStreamStart: (attempt: number) => void;
      onThinkingDelta: (text: string) => void;
      onThinkingEnd: () => void;
      onTextDelta: (text: string) => void;
      onRetryStart: (attempt: number, reason: string) => void;
      onStreamEnd: () => void;
      onDone: (result: AiConfigGenerateResponse) => void;
      onError: (message: string) => void;
      /** Status update from tool execution */
      onStatusUpdate?: (tool: string, status: string, message?: string, toolCallId?: string) => void;
    }
  ): { abort: () => void } {
    const abortController = new AbortController();

    const execute = async () => {
      try {
        const response = await authFetch(`${API_BASE}/generate/stream`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(request),
          signal: abortController.signal,
        });

        if (!response.ok) {
          const error = await response.text();
          callbacks.onError(error || 'Failed to start stream');
          return;
        }

        const reader = response.body?.getReader();
        if (!reader) {
          callbacks.onError('No response body');
          return;
        }

        await parseSSEStream(reader, {
          onEvent: (raw) => {
            switch (raw.eventType) {
              case 'stream_start': {
                try {
                  const parsed = JSON.parse(raw.data);
                  callbacks.onStreamStart(parsed.attempt ?? 1);
                } catch {
                  callbacks.onStreamStart(1);
                }
                break;
              }
              case 'thinking_delta':
                callbacks.onThinkingDelta(decodeSseData(raw.data));
                break;
              case 'thinking_end':
                callbacks.onThinkingEnd();
                break;
              case 'text_delta':
                callbacks.onTextDelta(decodeSseData(raw.data));
                break;
              case 'config_delta':
                // Backward compatibility: treat as text_delta
                callbacks.onTextDelta(decodeSseData(raw.data));
                break;
              case 'retry_start': {
                try {
                  const parsed = JSON.parse(raw.data);
                  callbacks.onRetryStart(parsed.attempt ?? 1, parsed.reason ?? '');
                } catch {
                  callbacks.onRetryStart(1, '');
                }
                break;
              }
              case 'stream_end':
                callbacks.onStreamEnd();
                break;
              case 'status_update': {
                // Tool execution status update
                try {
                  const parsed = JSON.parse(raw.data);
                  callbacks.onStatusUpdate?.(parsed.tool, parsed.status, parsed.message, parsed.toolCallId);
                } catch {
                  // Ignore malformed status updates
                }
                break;
              }
              case 'config_done': {
                try {
                  const result: AiConfigGenerateResponse = JSON.parse(raw.data);
                  callbacks.onDone(result);
                } catch (e) {
                  callbacks.onError(`Failed to parse result: ${(e as Error).message}`);
                }
                break;
              }
              case 'error': {
                try {
                  const parsed = JSON.parse(raw.data);
                  callbacks.onError(parsed.message ?? 'Unknown error');
                } catch {
                  callbacks.onError(raw.data);
                }
                break;
              }
            }
          },
        });
      } catch (error) {
        if ((error as Error).name === 'AbortError') return;
        if (error instanceof SSEConnectionError) {
          callbacks.onError('Connection lost — the server may have restarted');
        } else {
          callbacks.onError((error as Error).message);
        }
      }
    };

    execute();

    return {
      abort: () => abortController.abort(),
    };
  }

  /**
   * Validate a config without calling LLM.
   */
  async validateConfig(
    configType: 'agent' | 'swarm',
    config: Record<string, unknown>
  ): Promise<ConfigValidationResult> {
    const response = await authFetch(`${API_BASE}/validate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ configType, config }),
    });
    if (!response.ok) {
      const error = await response.text();
      throw new Error(error || 'Failed to validate config');
    }
    return response.json();
  }
}

export const aiConfigService = new AiConfigService();
