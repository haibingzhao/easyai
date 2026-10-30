import { authFetch, fetchJson, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/asr';

/** Which channel this round must use; the backend decides, the console only obeys. */
export type AsrTransport = 'WEBSOCKET' | 'HTTP' | 'NONE';

/** What the configured model can do; used for copy only, never for routing. */
export type AsrCapability = 'REALTIME_STREAMING' | 'REALTIME_MESSAGE' | 'HTTP_SEGMENT' | 'NONE';

/** Why the microphone is unavailable. `BACKEND_TOO_OLD` is synthesised here, every other value arrives from the server. */
export type AsrUnavailableReason =
  | 'NOT_CONFIGURED'
  | 'DISABLED'
  | 'INCOMPLETE_CONFIG'
  | 'INVALID_MODEL_OPTIONS'
  | 'UNVERIFIED_ENDPOINT'
  | 'UNSUPPORTED_PROVIDER'
  | 'UNSUPPORTED_MODEL'
  | 'FILE_ONLY_MODEL'
  | 'UNSUPPORTED_REALTIME_VARIANT'
  | 'BACKEND_TOO_OLD';

export interface AsrAvailability {
  configured: boolean;
  transport: AsrTransport;
  capability: AsrCapability;
  capabilitySource: 'CATALOG' | 'BUILTIN' | null;
  modelId: string | null;
  reason: AsrUnavailableReason | null;
  hint: string | null;
  refineReady: boolean;
}

/**
 * Microphone-disabled copy per reason. Only `BACKEND_TOO_OLD` is synthesised by the console; every
 * other value comes from the server, so the text can stay generic without becoming wrong.
 */
const REASON_NOTICES: Record<AsrUnavailableReason, string> = {
  NOT_CONFIGURED: 'Please configure an ASR model in Settings → Task Models',
  DISABLED: 'The configured ASR model is disabled',
  INCOMPLETE_CONFIG: 'The configured ASR model is missing its endpoint, key or model id',
  INVALID_MODEL_OPTIONS: 'The ASR model options are invalid; fix them in Settings → Task Models',
  UNVERIFIED_ENDPOINT: 'The ASR endpoint is not a trusted Bailian host',
  UNSUPPORTED_PROVIDER: 'The ASR provider is not supported for dictation',
  UNSUPPORTED_MODEL: 'The selected model is not an ASR model',
  FILE_ONLY_MODEL: 'This model only transcribes uploaded files; pick a realtime ASR model',
  UNSUPPORTED_REALTIME_VARIANT: 'This realtime model variant is not supported yet; pick another ASR model',
  BACKEND_TOO_OLD: 'Realtime voice input needs a newer server; please upgrade and try again',
};

/**
 * Why the microphone is off, in one sentence the UI can show verbatim. A failed read is treated like
 * "not configured": claiming the mic might work would advertise a configuration the server refused.
 */
export function asrUnavailableNotice(availability: AsrAvailability | null): string {
  if (!availability) return REASON_NOTICES.NOT_CONFIGURED;
  return REASON_NOTICES[availability.reason ?? 'NOT_CONFIGURED'];
}

/**
 * A backend without `GET /api/asr/availability` cannot say which channel to use, and guessing from
 * the model id would fork the capability table into a second, stale copy. So a 404/405 disables the
 * microphone and asks for an upgrade instead of falling back.
 */
const BACKEND_TOO_OLD: AsrAvailability = {
  configured: false,
  transport: 'NONE',
  capability: 'NONE',
  capabilitySource: null,
  modelId: null,
  reason: 'BACKEND_TOO_OLD',
  hint: null,
  refineReady: false,
};

const TRANSPORTS: readonly AsrTransport[] = ['WEBSOCKET', 'HTTP', 'NONE'];

/** Keep a malformed or newer-than-us response from routing audio somewhere unsupported. */
function normalize(data: Partial<AsrAvailability>): AsrAvailability {
  const transport = TRANSPORTS.find((value) => value === data.transport) ?? 'NONE';
  return {
    configured: data.configured === true,
    transport,
    capability: data.capability ?? 'NONE',
    capabilitySource: data.capabilitySource ?? null,
    modelId: typeof data.modelId === 'string' ? data.modelId : null,
    reason: data.reason ?? (transport === 'NONE' ? 'NOT_CONFIGURED' : null),
    hint: data.hint ?? null,
    refineReady: data.refineReady === true,
  };
}

/**
 * Dictation backend calls: one WAV segment in, transcription text out;
 * a dictation round plus its editor context in, the rewritten text out.
 */
export const asrService = {
  /**
   * Which channel this round should use. Called again at the start of every round — it is a local
   * computation on the server (no upstream call, no ticket, no billing), so a stale read would only
   * misroute audio after the user changed models.
   */
  async availability(): Promise<AsrAvailability> {
    const response = await authFetch(`${API_BASE}/availability`);
    if (response.status === 404 || response.status === 405) return BACKEND_TOO_OLD;
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return normalize((await response.json()) as Partial<AsrAvailability>);
  },

  async transcribeSegment(blob: Blob, language?: string): Promise<string> {
    const form = new FormData();
    form.append('file', blob, 'segment.wav');
    if (language) form.append('language', language);
    const response = await authFetch(`${API_BASE}/transcribe`, { method: 'POST', body: form });
    if (!response.ok) {
      // Callers prefix their own sentence to this, so keep it to the one signal that survives
      // the wire: Spring withholds ResponseStatusException reasons from the body.
      throw new Error(`HTTP ${response.status}`);
    }
    const data = (await response.json()) as { text: string };
    return data.text ?? '';
  },

  async refineText(text: string, contextBefore: string, contextAfter: string): Promise<string> {
    const data = await fetchJson<{ text: string }>(`${API_BASE}/refine`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ text, contextBefore, contextAfter }),
    });
    return data.text ?? '';
  },
};
