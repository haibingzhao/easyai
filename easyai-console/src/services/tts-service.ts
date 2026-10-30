import { authFetch, fetchJson, JSON_HEADERS, readErrorBody } from '@/services/api-client';

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

/** Authenticated audio response; the body can be consumed incrementally or buffered as a fallback. */
export interface TtsSpeechResponse {
  mimeType: string;
  body: ReadableStream<Uint8Array> | null;
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
  async speak(text: string, signal?: AbortSignal): Promise<TtsSpeechResponse> {
    const response = await authFetch(`${API_BASE}/speak`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ text }),
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
    return {
      mimeType: contentType.split(';', 1)[0] || 'audio/mpeg',
      body: response.body,
      blob: () => response.blob(),
    };
  },

  /** Whether a usable TTS model is configured. Never synthesizes, so it costs nothing to poll. */
  async availability(signal?: AbortSignal): Promise<TtsAvailability> {
    return fetchJson<TtsAvailability>(`${API_BASE}/availability`, { signal });
  },
};
