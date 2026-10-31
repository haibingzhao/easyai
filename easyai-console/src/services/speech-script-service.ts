import { authFetch, JSON_HEADERS, readErrorBody } from '@/services/api-client';
import { targetFields } from '@/services/tts-service';
import type { SpeechScript, SpeechSegment } from '@/services/tts-service';
import type {
  PinScriptRequest,
  ScriptDetail,
  ScriptListQuery,
  ScriptSummary,
} from '@/types/speech-script';
import { i18n } from '@/utils/i18n';

const API_BASE = '/api/speech-scripts';

/** A proxy's HTML error page must not be pasted whole into a status line in the UI. */
const MAX_ERROR_BODY_CHARS = 500;

function errorText(body: string, status: number): string {
  return readErrorBody(body).slice(0, MAX_ERROR_BODY_CHARS) || `HTTP ${status}`;
}

/** A refused library call; `status` lets the page tell "gone" from "no database". */
export class ScriptLibraryError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'ScriptLibraryError';
  }
}

/**
 * The read-aloud script library.
 *
 * Rendering a stored script is not a function here: it goes through `ttsService.render({scriptId})`,
 * which the server resolves from the archive when the in-memory plan has expired. Keeping one render
 * path means segment validation, pauses and progress counting stay in one place.
 */
export const speechScriptService = {
  async list(query: ScriptListQuery = {}, signal?: AbortSignal): Promise<ScriptSummary[]> {
    const params = new URLSearchParams();
    if (query.type) params.set('type', query.type);
    if (query.sessionId) params.set('sessionId', query.sessionId);
    if (query.status) params.set('status', query.status);
    if (query.mode) params.set('mode', query.mode);
    if (query.favorite) params.set('favorite', 'true');
    if (query.q) params.set('q', query.q);
    if (query.sort) params.set('sort', query.sort);
    if (query.limit !== undefined) params.set('limit', String(query.limit));
    if (query.offset !== undefined) params.set('offset', String(query.offset));
    const suffix = params.toString();
    return get<ScriptSummary[]>(suffix ? `${API_BASE}?${suffix}` : API_BASE, signal);
  },

  async detail(id: string, signal?: AbortSignal): Promise<ScriptDetail> {
    return get<ScriptDetail>(`${API_BASE}/${encodeURIComponent(id)}`, signal);
  },

  /**
   * Keep the plan the panel is currently showing.
   *
   * The server reads the text from its own copy of the plan, so a pin cannot attach a script to text
   * the client never sent.
   */
  async pin(payload: PinScriptRequest): Promise<ScriptDetail> {
    const body = JSON.stringify({
      scriptId: payload.scriptId,
      title: payload.title ?? null,
      ...targetFields(payload.target),
    });
    const response = await authFetch(API_BASE, { method: 'POST', headers: JSON_HEADERS, body });
    if (!response.ok) {
      const text = await response.text().catch(() => '');
      throw new ScriptLibraryError(errorText(text, response.status), response.status);
    }
    return response.json() as Promise<ScriptDetail>;
  },

  /** Rename or favourite. The script body is edited through the panel, not here. */
  async patch(id: string, changes: { title?: string; favorite?: boolean }): Promise<ScriptDetail> {
    const body = JSON.stringify({
      title: changes.title ?? null,
      favorite: changes.favorite ?? null,
    });
    const response = await authFetch(`${API_BASE}/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: JSON_HEADERS,
      body,
    });
    if (!response.ok) {
      const text = await response.text().catch(() => '');
      throw new ScriptLibraryError(errorText(text, response.status), response.status);
    }
    return response.json() as Promise<ScriptDetail>;
  },

  async remove(id: string): Promise<void> {
    const response = await authFetch(`${API_BASE}/${encodeURIComponent(id)}`, { method: 'DELETE' });
    if (!response.ok) {
      const text = await response.text().catch(() => '');
      throw new ScriptLibraryError(errorText(text, response.status), response.status);
    }
  },
};

async function get<T>(url: string, signal?: AbortSignal): Promise<T> {
  // `authFetch` rather than `fetchJson`: the page tells "gone" (404) from "no database" (503), and
  // `fetchJson` flattens the status into a message that a JSON error body would swallow.
  const response = await authFetch(url, signal ? { signal } : undefined);
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new ScriptLibraryError(errorText(text, response.status), response.status);
  }
  return response.json() as Promise<T>;
}

/** A plan is reusable without a second planning call only while this digest still matches. */
export function isReused(script: SpeechScript | null): boolean {
  return script?.reused === true;
}

/** Total characters the voice will actually say — the list shows "1200 → 1350". */
export function spokenCharsOf(segments: SpeechSegment[]): number {
  return segments.reduce((total, segment) => total + segment.spokenText.length, 0);
}

/**
 * Why the library list is empty.
 *
 * Three genuinely different situations read the same on screen otherwise: a deployment without a
 * database, a session that expired, and a request that failed. Only the status separates them.
 */
export function listErrorText(error: unknown): string {
  if (error instanceof ScriptLibraryError) {
    if (error.status === 503) {
      return i18n('The read-aloud library needs the database (easyai.r2dbc.enabled)');
    }
    if (error.status === 401) return i18n('Sign in again to use read-aloud');
  }
  return i18n('Could not load the read-aloud library');
}

/** Why one stored script could not be opened; "deleted elsewhere" is not an error to retry. */
export function detailErrorText(error: unknown): string {
  if (error instanceof ScriptLibraryError) {
    if (error.status === 404) return i18n('This read-aloud script no longer exists');
    if (error.status === 503) return i18n('The read-aloud library needs the database');
    if (error.status === 401) return i18n('Sign in again to use read-aloud');
  }
  return i18n('Could not load this read-aloud script');
}
