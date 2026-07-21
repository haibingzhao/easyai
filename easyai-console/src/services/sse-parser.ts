import { flushSync } from 'react-dom';

/**
 * A raw SSE event parsed from the stream.
 * Contains the event type string and raw data string.
 * Callers are responsible for JSON-parsing the data if needed.
 */
export interface RawSSEEvent {
  eventType: string;
  data: string;
}

/**
 * Custom error for SSE connection loss (network error, backend restart, etc.)
 */
export class SSEConnectionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SSEConnectionError';
  }
}

interface RawSSECallbacks {
  onEvent: (event: RawSSEEvent) => void;
  onComplete?: () => void;
}

interface SSEStreamResult {
  completed: boolean;
}

/**
 * Parse an SSE stream from a ReadableStream and dispatch raw events to callbacks.
 * Each event block is parsed into { eventType, data } and delivered via onEvent.
 *
 * Uses flushSync to force synchronous React renders after each event,
 * and yields to the event loop between events so the browser can paint.
 *
 * Returns whether the stream completed normally (reached end without error).
 * Throws SSEConnectionError if the connection is lost unexpectedly.
 */
export async function parseSSEStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  callbacks: RawSSECallbacks
): Promise<SSEStreamResult> {
  const decoder = new TextDecoder();
  let buffer = '';
  let completed = false;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const chunkText = decoder.decode(value, { stream: true });
      buffer += chunkText;

      const events = buffer.split('\n\n');
      buffer = events.pop() || '';

      for (const eventBlock of events) {
        if (!eventBlock.trim()) continue;

        const lines = eventBlock.split('\n');
        let eventType = '';
        let eventData = '';

        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventType = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            eventData = line.slice(5).trim();
          }
        }

        if (eventType && eventData) {
          flushSync(() => callbacks.onEvent({ eventType, data: eventData }));
          await new Promise<void>(r => setTimeout(r, 0));
        }
      }
    }
    completed = true;
  } catch (error) {
    if ((error as Error).name === 'AbortError') {
      throw error;
    }
    throw new SSEConnectionError(
      `SSE connection lost: ${(error as Error).message}`
    );
  }

  callbacks.onComplete?.();
  return { completed };
}
