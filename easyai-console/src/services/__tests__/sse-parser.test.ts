import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock react-dom flushSync to just call the callback synchronously
vi.mock('react-dom', () => ({
  flushSync: (fn: () => void) => fn(),
}));

import { parseSSEStream, SSEConnectionError } from '../sse-parser';
import type { RawSSEEvent } from '../sse-parser';

/**
 * Helper: create a ReadableStreamDefaultReader from raw SSE text chunks.
 */
function createReader(chunks: string[]): ReadableStreamDefaultReader<Uint8Array> {
  const encoder = new TextEncoder();
  let index = 0;
  return {
    read: async () => {
      if (index < chunks.length) {
        const value = encoder.encode(chunks[index]);
        index++;
        return { done: false, value };
      }
      return { done: true, value: undefined };
    },
    releaseLock: () => {},
    cancel: async () => {},
    closed: Promise.resolve(undefined),
  } as unknown as ReadableStreamDefaultReader<Uint8Array>;
}

describe('parseSSEStream', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  it('should parse a single SSE event', async () => {
    const events: RawSSEEvent[] = [];
    const reader = createReader([
      'event: text_delta\ndata: {"delta":"hello"}\n\n',
    ]);

    const promise = parseSSEStream(reader, {
      onEvent: (e) => events.push(e),
    });
    // Advance timers for the setTimeout(r, 0) between events
    await vi.advanceTimersByTimeAsync(10);
    const result = await promise;

    expect(result.completed).toBe(true);
    expect(events).toHaveLength(1);
    expect(events[0].eventType).toBe('text_delta');
    expect(events[0].data).toBe('{"delta":"hello"}');
  });

  it('should parse multiple events in a single chunk', async () => {
    const events: RawSSEEvent[] = [];
    const reader = createReader([
      'event: start\ndata: {"sessionId":"s1"}\n\nevent: text_delta\ndata: {"delta":"hi"}\n\n',
    ]);

    const promise = parseSSEStream(reader, {
      onEvent: (e) => events.push(e),
    });
    await vi.advanceTimersByTimeAsync(50);
    const result = await promise;

    expect(result.completed).toBe(true);
    expect(events).toHaveLength(2);
    expect(events[0].eventType).toBe('start');
    expect(events[1].eventType).toBe('text_delta');
  });

  it('should handle events split across multiple chunks', async () => {
    const events: RawSSEEvent[] = [];
    // Event split across two chunks
    const reader = createReader([
      'event: text_del',
      'ta\ndata: {"delta":"world"}\n\n',
    ]);

    const promise = parseSSEStream(reader, {
      onEvent: (e) => events.push(e),
    });
    await vi.advanceTimersByTimeAsync(10);
    const result = await promise;

    expect(result.completed).toBe(true);
    expect(events).toHaveLength(1);
    expect(events[0].eventType).toBe('text_delta');
    expect(events[0].data).toBe('{"delta":"world"}');
  });

  it('should skip empty or malformed event blocks', async () => {
    const events: RawSSEEvent[] = [];
    const reader = createReader([
      '\n\nevent: text_delta\ndata: {"delta":"ok"}\n\n\n\n',
    ]);

    const promise = parseSSEStream(reader, {
      onEvent: (e) => events.push(e),
    });
    await vi.advanceTimersByTimeAsync(10);
    const result = await promise;

    expect(result.completed).toBe(true);
    expect(events).toHaveLength(1);
    expect(events[0].data).toBe('{"delta":"ok"}');
  });

  it('should skip events missing eventType or data', async () => {
    const events: RawSSEEvent[] = [];
    const reader = createReader([
      'data: orphan-data\n\nevent: no_data\n\nevent: valid\ndata: {"ok":true}\n\n',
    ]);

    const promise = parseSSEStream(reader, {
      onEvent: (e) => events.push(e),
    });
    await vi.advanceTimersByTimeAsync(10);
    const result = await promise;

    expect(result.completed).toBe(true);
    // Only the event with both eventType and data should be delivered
    expect(events).toHaveLength(1);
    expect(events[0].eventType).toBe('valid');
  });

  it('should call onComplete when stream ends normally', async () => {
    const onComplete = vi.fn();
    const reader = createReader([
      'event: done\ndata: {"reason":"stop"}\n\n',
    ]);

    const promise = parseSSEStream(reader, {
      onEvent: () => {},
      onComplete,
    });
    await vi.advanceTimersByTimeAsync(10);
    await promise;

    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('should throw SSEConnectionError on read failure', async () => {
    const failingReader = {
      read: async () => {
        throw new Error('network failure');
      },
      releaseLock: () => {},
      cancel: async () => {},
      closed: Promise.resolve(undefined),
    } as unknown as ReadableStreamDefaultReader<Uint8Array>;

    await expect(
      parseSSEStream(failingReader, { onEvent: () => {} })
    ).rejects.toThrow(SSEConnectionError);
  });

  it('should rethrow AbortError without wrapping', async () => {
    const abortError = new DOMException('Aborted', 'AbortError');
    const abortReader = {
      read: async () => {
        throw abortError;
      },
      releaseLock: () => {},
      cancel: async () => {},
      closed: Promise.resolve(undefined),
    } as unknown as ReadableStreamDefaultReader<Uint8Array>;

    await expect(
      parseSSEStream(abortReader, { onEvent: () => {} })
    ).rejects.toThrow(abortError);
  });
});
