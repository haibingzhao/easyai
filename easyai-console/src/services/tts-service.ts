import { authFetch, fetchJson, JSON_HEADERS, readErrorBody } from '@/services/api-client';
import type { ReadAloudMode, ReadAloudTone } from '@/types/settings';

const API_BASE = '/api/tts';

/** Read-aloud state as the backend reports it; `reason` is a stable code, not display text. */
export interface TtsAvailability {
  configured: boolean;
  modelId?: string;
  /** NOT_CONFIGURED | WRONG_MODEL_TYPE | DISABLED | UNSUPPORTED_PROVIDER | INCOMPLETE_CONFIG */
  reason?: string;
}

/** A refused read-aloud request; `status` lets callers tell a limit from a provider failure. */
export class TtsError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'TtsError';
  }
}

/** How much of an error body is read before it is turned into a message. */
const MAX_ERROR_BODY_CHARS = 500;

/**
 * Longest unterminated line the event stream may accumulate.
 *
 * One segment's Base64 audio is the largest legitimate line; anything past this is a framing failure
 * (no newline at all), and buffering it would block playback while growing without bound.
 */
const MAX_LINE_CHARS = 32 * 1024 * 1024;

/** One word with the time range the provider reported for it. */
export interface SpeechWord {
  text: string;
  beginMs: number;
  endMs: number;
}

/** A defect the server repaired, kept machine-readable so the UI can explain rather than guess. */
export interface SpeechWarning {
  code: string;
  segmentId?: string | null;
  message: string;
}

/**
 * One plan segment. `sourceText` is the authoritative slice of the reply; `spokenText` is what the
 * voice actually says. They differ only when the plan rewrote the sentence for speaking.
 */
export interface SpeechSegment {
  id: string;
  index: number;
  sourceText: string;
  spokenText: string;
  voice: string;
  emotion?: string | null;
  styleNote?: string | null;
  rate?: number | null;
  role: string;
  pauseAfterMs: number;
}

/**
 * Where the text came from.
 *
 * The server files every plan under a target so a later preview can reuse it instead of paying for
 * a second planning call. Omitting it means "manual text", which is never shared with a message.
 */
export interface SpeechTargetRef {
  type: 'session_message' | 'manual_text' | 'knowledge_article' | 'tool_call';
  id?: string | null;
  sessionId?: string | null;
}

/** A validated plan, as the script panel needs it; nothing has been synthesized yet. */
export interface SpeechScript {
  scriptId: string;
  revision: number;
  style: string;
  mood: string;
  segments: SpeechSegment[];
  warnings: SpeechWarning[];
  expiresInSeconds: number;
  /** True when the server answered from its archive instead of planning again. */
  reused?: boolean;
}

/**
 * What `/api/tts/render` sends, one JSON object per line.
 *
 * The audio travels in the same stream as the metadata that says which segment it is and how long
 * to pause after it: neither can go in a static response header, and a second request per segment
 * would pay the planning cost again.
 */
export type SpeechRenderEvent =
  | {
      type: 'meta';
      scriptId: string;
      revision: number;
      segmentCount: number;
      planned: boolean;
      warnings: SpeechWarning[];
    }
  | {
      type: 'audio';
      segmentId: string;
      index: number;
      spokenText: string;
      voice: string;
      pauseAfterMs: number;
      mimeType: string;
      /** Base64: NDJSON has to survive text framing. */
      data: string;
      words: SpeechWord[];
    }
  | ({ type: 'warning' } & SpeechWarning)
  | { type: 'done'; segments: number; warnings: SpeechWarning[] }
  | { type: 'failed'; code: string; message: string; playedSegments: number };

/** Either `text` (plan it now) or `scriptId` (render the stored plan) identifies the work. */
export interface RenderSpeechRequest {
  text?: string;
  scriptId?: string;
  /** Only used when the plan is built from `text`; a stored plan already knows its target. */
  target?: SpeechTargetRef;
  /** Only meaningful with `scriptId`: replaces the stored plan, re-validated and re-revisioned. */
  segments?: SpeechSegment[];
  /**
   * `true` streams a fresh plan line by line; `false` validates the whole plan before any audio.
   * Ignored with `scriptId`: a stored plan is always validated whole.
   */
  incremental?: boolean;
  /**
   * How a fresh plan should sound. Ignored with `scriptId` — a stored plan keeps the wording it was
   * made with — and ignored by `direct`, which never asks the planner.
   */
  tone?: ReadAloudTone;
}

/** Authenticated audio response; the body can be consumed incrementally or buffered as a fallback. */
export interface TtsSpeechResponse {
  mimeType: string;
  body: ReadableStream<Uint8Array> | null;
  /**
   * Why the server used the original text despite `mode: 'refine'`; absent on a real refine/direct.
   *
   * A short reason code, or `code:seconds` when a budget explains it (`refine-timeout:4`). Passed
   * through verbatim rather than whitelisted here: a new server-side reason then still shows its
   * generic notice instead of being silently dropped, and the wording stays in the view layer.
   */
  fallbackReason?: string;
  blob(): Promise<Blob>;
}

/**
 * Chat read-aloud calls.
 *
 * The response is fetched with an auth header rather than played from `<audio src="…?token=…">`.
 * Returning the body before buffering it lets the playback controller start on the first MP3 chunk.
 * `signal` covers both the initial request and body consumption, so stop also cancels paid synthesis.
 */
export const ttsService = {
  async speak(
    text: string,
    signal?: AbortSignal,
    mode: ReadAloudMode = 'direct',
    target?: SpeechTargetRef,
    tone: ReadAloudTone = 'default'
  ): Promise<TtsSpeechResponse> {
    const response = await authFetch(`${API_BASE}/speak`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ text, mode, tone, ...targetFields(target) }),
      signal,
    });
    if (!response.ok) {
      const body = await response.text().catch(() => '');
      throw new TtsError(readErrorBody(body).slice(0, MAX_ERROR_BODY_CHARS) || `HTTP ${response.status}`, response.status);
    }
    // A 200 that is not audio means something in front of the API answered instead (login page,
    // proxy error page). Handing those bytes to `<audio>` would surface as an opaque media error.
    const contentType = response.headers.get('content-type') ?? '';
    if (contentType && !contentType.startsWith('audio/')) {
      throw new TtsError(`expected audio, got ${contentType}`, response.status);
    }
    const fallback = response.headers.get('x-speech-fallback');
    return {
      mimeType: contentType.split(';', 1)[0] || 'audio/mpeg',
      body: response.body,
      fallbackReason: fallback || undefined,
      blob: () => response.blob(),
    };
  },

  /**
   * Directed read-aloud: one NDJSON line per event, each carrying its own audio.
   *
   * The response is consumed as it arrives rather than buffered, because the whole point of the
   * event stream is that segment 1 can start playing while segment 5 is still synthesizing. `signal`
   * covers the request and the body, so stopping also stops the paid work behind it.
   *
   * `onEvent` is awaited before the next line is read, so a slow consumer slows the producer rather
   * than letting the plan pile up in memory: not reading the response is what eventually
   * back-pressures the server-side render, and through it the number of segments synthesized ahead
   * of playback. A line that does not parse is skipped: only a truncated response produces one, and
   * the stream's own `done`/`failed` event is what decides the outcome.
   */
  async render(
    request: RenderSpeechRequest,
    signal: AbortSignal | undefined,
    onEvent: (event: SpeechRenderEvent) => void | Promise<void>
  ): Promise<void> {
    const response = await authFetch(`${API_BASE}/render`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({
        text: request.text ?? '',
        scriptId: request.scriptId ?? null,
        segments: request.segments ?? null,
        incremental: request.incremental ?? true,
        tone: request.tone ?? 'default',
        ...targetFields(request.target),
      }),
      signal,
    });
    if (!response.ok) {
      const body = await response.text().catch(() => '');
      throw new TtsError(readErrorBody(body).slice(0, MAX_ERROR_BODY_CHARS) || `HTTP ${response.status}`, response.status);
    }
    // A 200 that is not the event stream means something in front of the API answered instead. Only
    // ndjson is accepted: `application/json` is what a proxy or an error handler returns, and parsing
    // its body line-by-line would silently produce "no events" instead of an error.
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('ndjson')) {
      // Body is not read here on purpose: an error page can be arbitrarily large. It is cancelled
      // rather than dropped, so the connection is not left open waiting for someone to read it.
      await response.body?.cancel().catch(() => undefined);
      throw new TtsError(`expected ndjson, got ${contentType || 'no content type'}`, response.status);
    }
    const body = response.body;
    if (!body) throw new TtsError('the read-aloud stream was empty', response.status);

    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (true) {
        const next = await reader.read();
        if (next.done) break;
        buffer += decoder.decode(next.value, { stream: true });
        // A chunk boundary can fall anywhere, including inside a line or inside a multi-byte
        // character; only newline-complete lines are parsed, and the decoder keeps the rest.
        let newline = buffer.indexOf('\n');
        while (newline >= 0) {
          const line = buffer.slice(0, newline).trim();
          buffer = buffer.slice(newline + 1);
          if (line) await emitEvent(line, onEvent);
          newline = buffer.indexOf('\n');
        }
        // A line this long means the framing itself is broken (one event per segment cannot reach it),
        // and holding it would grow without bound while playback waits for a line that never ends.
        if (buffer.length > MAX_LINE_CHARS) {
          throw new TtsError('the read-aloud stream is not newline-delimited', response.status);
        }
      }
      buffer += decoder.decode();
      const tail = buffer.trim();
      if (tail) await emitEvent(tail, onEvent);
    } catch (error) {
      // Releasing the lock on a still-open body leaves the response running to completion in the
      // background; cancelling is what actually tells the server to stop synthesizing.
      await reader.cancel().catch(() => undefined);
      throw error;
    } finally {
      reader.releaseLock();
    }
  },

  /**
   * Plan the text and return the script without synthesizing anything.
   *
   * The plan is stored server-side against `scriptId`, so a later render by id re-validates it
   * against the source the server kept instead of trusting text the client sends back.
   */
  async script(text: string, signal?: AbortSignal, target?: SpeechTargetRef): Promise<SpeechScript> {
    const response = await authFetch(`${API_BASE}/script`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ text, ...targetFields(target) }),
      signal,
    });
    if (!response.ok) {
      // Same status mapping as playback: a 409 means "no model configured", not "the plan is bad".
      const body = await response.text().catch(() => '');
      throw new TtsError(readErrorBody(body).slice(0, MAX_ERROR_BODY_CHARS) || `HTTP ${response.status}`, response.status);
    }
    return response.json() as Promise<SpeechScript>;
  },

  /** Whether a usable TTS model is configured. Never synthesizes, so it costs nothing to poll. */
  async availability(signal?: AbortSignal): Promise<TtsAvailability> {
    return fetchJson<TtsAvailability>(`${API_BASE}/availability`, { signal });
  },
};

/** Flattened target fields: the wire keeps three optional strings, not a nested object. */
export function targetFields(target?: SpeechTargetRef): Record<string, string> {
  if (!target) return {};
  const fields: Record<string, string> = { targetType: target.type };
  if (target.id) fields.targetId = target.id;
  if (target.sessionId) fields.sessionId = target.sessionId;
  return fields;
}

async function emitEvent(
  line: string,
  onEvent: (event: SpeechRenderEvent) => void | Promise<void>
): Promise<void> {
  let event: SpeechRenderEvent;
  try {
    event = JSON.parse(line) as SpeechRenderEvent;
  } catch {
    // Only a truncated or proxied-through response produces an unparsable line; the stream's own
    // terminal event still decides whether playback finished or failed.
    return;
  }
  // `null`, a number, an array — all valid JSON and all meaningless here. Handing one to the
  // consumer would throw inside its `switch` and turn a single stray line into a dead stream.
  if (!event || typeof (event as { type?: unknown }).type !== 'string') return;
  // A handler that throws is the consumer's own failure and must stop the stream, so it is not
  // caught here.
  await onEvent(event);
}
