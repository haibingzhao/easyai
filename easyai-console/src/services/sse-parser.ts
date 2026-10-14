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
 *
 * `detail` is the raw browser/transport message. Callers surface it as a *cause*, never as a
 * verdict: the same detail is produced by a backend that died and by an iOS tab that went to the
 * background, and only the caller knows which one it is looking at.
 */
export class SSEConnectionError extends Error {
  readonly detail: string;

  constructor(detail: string) {
    super(`SSE connection lost: ${detail}`);
    this.name = 'SSEConnectionError';
    this.detail = detail;
  }
}

interface RawSSECallbacks {
  onEvent: (event: RawSSEEvent) => void;
  onComplete?: () => void;
}

/**
 * Parse an SSE stream from a ReadableStream and dispatch raw events to callbacks.
 * Each event block is parsed into { eventType, data } and delivered via onEvent.
 *
 * Uses flushSync to force synchronous React renders after each event,
 * and yields to the event loop between events so the browser can paint.
 *
 * Only the read itself counts as a connection loss. A callback that throws is a frontend
 * defect, and killing the stream for it would report that defect to the user as a broken
 * connection while the backend keeps running — so the event is dropped and logged instead.
 *
 * Returning normally means the stream reached its end; whether the *run* ended is the caller's
 * question, answered by the events it saw, not by the transport.
 * Throws SSEConnectionError if the connection is lost unexpectedly.
 */
export async function parseSSEStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  callbacks: RawSSECallbacks
): Promise<void> {
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    let chunk: ReadableStreamReadResult<Uint8Array>;
    try {
      chunk = await reader.read();
    } catch (error) {
      if ((error as Error).name === 'AbortError') {
        throw error;
      }
      throw new SSEConnectionError((error as Error).message);
    }
    if (chunk.done) break;

    const chunkText = decoder.decode(chunk.value, { stream: true });
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

      // Dispatch any event that carries an explicit event type, even when its
      // data payload is empty. Some signals (e.g. `thinking_end` / `stream_end`
      // in the AI-config flow) are emitted as `event:xxx\ndata:` with no body;
      // requiring non-empty data would silently drop them and leave the UI in a
      // stale state (e.g. the thinking timer never stops).
      if (eventType) {
        try {
          flushSync(() => callbacks.onEvent({ eventType, data: eventData }));
        } catch (error) {
          console.error('[SSE] Event handler failed, event dropped:', eventType, error);
        }
        await new Promise<void>(r => setTimeout(r, 0));
      }
    }
  }

  callbacks.onComplete?.();
}
