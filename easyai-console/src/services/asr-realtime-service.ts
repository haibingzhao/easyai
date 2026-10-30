import { authFetch, JSON_HEADERS } from '@/services/api-client';

/**
 * The browser half of the streaming recognition channel.
 *
 * Scope: exactly one socket per recording round. It owns the ticket handshake, the audio frame
 * counter and the parsed downstream frames; it owns no microphone, no text and no UI state, so the
 * hook can treat it as a byte/text pipe and keep a single round lifecycle for both channels.
 *
 * Audio only ever goes to this project's own origin. A ticket (not the JWT) is what authorises the
 * upgrade, so a long-lived credential never lands in a URL that ends up in access logs.
 */

const API_BASE = '/api/asr';
/** The backend accepts at most one 100 ms frame, i.e. 3200 bytes of PCM16. */
export const MAX_AUDIO_FRAME_BYTES = 3_200;
/** Unflushed frames may not pile up in the socket: 64 KB ≈ 2 s of audio, after which the round ends. */
export const MAX_BUFFERED_BYTES = 64_000;
/** A round that cannot reach `ready` in this budget is a failure, not something to wait out. */
export const CONNECT_TIMEOUT_MS = 10_000;

export interface AsrRealtimeTicket {
  ticket: string;
  recordingId: string;
  expiresInSeconds: number;
  webSocketPath: string;
}

export interface AsrRealtimeUsage {
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  durationSeconds: number | null;
}

/** Terminal frames are mutually exclusive; `closed` only reports the transport going away. */
export type AsrRealtimeEvent =
  | { type: 'ready'; modelId: string | null }
  | { type: 'sentence'; sentenceId: number; text: string; isFinal: boolean }
  | { type: 'done'; usage: AsrRealtimeUsage | null; notice: string | null }
  | { type: 'error'; code: string; message: string; partial: boolean }
  | { type: 'cancelled' }
  | { type: 'closed'; code: number };

export interface AsrRealtimeHandlers {
  onEvent: (event: AsrRealtimeEvent) => void;
}

/** A socket whose buffer is full fails the round: dropping the newest frame would silently lose speech. */
export class AsrBackpressureError extends Error {
  constructor() {
    super('The recognition channel is not keeping up with the microphone');
    this.name = 'AsrBackpressureError';
  }
}

/**
 * Same-origin `ws(s)` URL for the upgrade. Any `webSocketPath` the server returns is appended to the
 * page's own host — an absolute foreign URL is refused rather than dialled.
 */
export function realtimeSocketUrl(path: string, ticket: string, origin?: string): string {
  const base = origin ?? (typeof location === 'undefined' ? '' : location.origin);
  if (!base) throw new Error('Cannot derive the recognition socket origin');
  const prefix = base.startsWith('https:') ? 'wss:' : 'ws:';
  const host = base.replace(/^https?:/, '');
  const suffix = path.startsWith('/') ? path : `/${path}`;
  return `${prefix}${host}${suffix}?ticket=${encodeURIComponent(ticket)}`;
}

export async function requestRealtimeTicket(): Promise<AsrRealtimeTicket> {
  const response = await authFetch(`${API_BASE}/realtime/tickets`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: '{}',
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const data = (await response.json()) as Partial<AsrRealtimeTicket>;
  if (!data.ticket || !data.webSocketPath) throw new Error('Recognition ticket response is incomplete');
  return {
    ticket: data.ticket,
    recordingId: data.recordingId ?? '',
    expiresInSeconds: data.expiresInSeconds ?? 30,
    webSocketPath: data.webSocketPath,
  };
}

/** Everything the client needs from the outside world; tests replace it instead of faking globals. */
export interface AsrRealtimeDeps {
  requestTicket: () => Promise<AsrRealtimeTicket>;
  createSocket: (url: string) => WebSocket;
  connectTimeoutMs: number;
}

const defaultDeps: AsrRealtimeDeps = {
  requestTicket: requestRealtimeTicket,
  createSocket: (url) => new WebSocket(url),
  connectTimeoutMs: CONNECT_TIMEOUT_MS,
};

/**
 * One round's socket. Created only after `ready`, so callers can gate the microphone on `open()`
 * resolving — nothing is ever sent into a socket the service is not listening on yet.
 */
export class AsrRealtimeSession {
  private audioSeq = 0;
  private audioSamples = 0;
  private bufferedBytes = 0;
  private finished = false;
  /** No more audio may be sent: `finish` and `cancel` both end the uplink immediately. */
  private halted = false;
  private terminated = false;
  private disposed = false;
  private heartbeat: ReturnType<typeof setInterval> | null = null;

  private constructor(
    private readonly socket: WebSocket,
    readonly recordingId: string,
    private readonly handlers: AsrRealtimeHandlers,
  ) {}

  static async open(handlers: AsrRealtimeHandlers, overrides: Partial<AsrRealtimeDeps> = {}): Promise<AsrRealtimeSession> {
    const deps: AsrRealtimeDeps = { ...defaultDeps, ...overrides };
    const ticket = await deps.requestTicket();
    const socket = deps.createSocket(realtimeSocketUrl(ticket.webSocketPath, ticket.ticket));
    socket.binaryType = 'arraybuffer';
    const session = new AsrRealtimeSession(socket, ticket.recordingId, handlers);
    await session.handshake(deps.connectTimeoutMs);
    return session;
  }

  /** Frames sent so far; echoed in `finish` so the server can prove nothing was lost. */
  get framesSent(): number {
    return this.audioSeq;
  }

  get samplesSent(): number {
    return this.audioSamples;
  }

  get isTerminated(): boolean {
    return this.terminated;
  }

  /**
   * Send one 100 ms frame. `Int16Array` is little-endian on every platform this console targets, which
   * is what the service's `pcm_s16le` expects; the byte counter is the only size the server trusts.
   */
  sendAudio(frame: Int16Array): void {
    if (this.halted || this.terminated || this.disposed) return;
    if (frame.length === 0) return;
    const bytes = frame.byteLength;
    if (bytes > MAX_AUDIO_FRAME_BYTES) throw new RangeError(`Audio frame of ${bytes} bytes is too large`);
    // `bufferedAmount` is what the network has not flushed yet; frames the encoder has not produced
    // are bounded by the worklet's credit window, so the two together are the round's budget.
    this.bufferedBytes = this.socket.bufferedAmount;
    if (this.bufferedBytes + bytes > MAX_BUFFERED_BYTES) throw new AsrBackpressureError();
    const payload = frame.byteOffset === 0 && bytes === frame.buffer.byteLength ? frame.buffer : frame.slice().buffer;
    this.socket.send(payload);
    this.audioSeq += 1;
    this.audioSamples += frame.length;
  }

  /** Ask the service to finish the upstream task; the final sentences arrive afterwards. */
  finish(): void {
    if (this.finished || this.terminated || this.disposed) return;
    this.finished = true;
    this.halted = true;
    this.send({ type: 'finish', lastAudioSeq: this.audioSeq, totalSamples: this.audioSamples });
  }

  /** Abandon the round: the service stops reading audio and confirms with `cancelled`. */
  cancel(): void {
    if (this.terminated || this.disposed) return;
    this.halted = true;
    this.send({ type: 'cancel' });
  }

  /** Drop the socket without waiting for anything; used on unmount and after a timeout. */
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.stopHeartbeat();
    try {
      this.socket.close(1000, 'round finished');
    } catch {
      // Already closing — nothing to release.
    }
  }

  private handshake(timeoutMs: number): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      let settled = false;
      const settle = (error?: Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (error) reject(error);
        else resolve();
      };
      const timer = setTimeout(() => {
        // A socket that never says `ready` would otherwise hold the round in `connecting` forever.
        settle(new Error('The recognition channel did not become ready'));
        this.dispose();
      }, timeoutMs);

      this.socket.onmessage = (event) => {
        const frame = parseFrame(event.data);
        if (frame === null) return;
        if (frame.type === 'ready') {
          if (!settled) {
            this.bind();
            settle();
          }
          this.handlers.onEvent({ type: 'ready', modelId: typeof frame.modelId === 'string' ? frame.modelId : null });
          return;
        }
        // Anything before `ready` is a protocol violation; report it and drop the socket.
        if (!settled) {
          settle(new Error('The recognition channel sent a result before it was ready'));
          return;
        }
        this.dispatch(frame);
      };

      this.socket.onerror = () => {
        if (!settled) settle(new Error('Could not open the recognition channel'));
      };

      this.socket.onclose = (event) => {
        this.stopHeartbeat();
        if (!settled) {
          settle(new Error('The recognition channel closed before it was ready'));
          return;
        }
        // A clean close without `done` is not a success: the transcript may be missing its tail.
        if (!this.terminated && !this.disposed) {
          this.terminated = true;
          this.handlers.onEvent({ type: 'closed', code: event.code });
        }
      };
    });
  }

  private bind(): void {
    this.socket.onerror = () => undefined;
    // Application-level keepalive; it is not audio and never advances the audio counter.
    this.heartbeat = setInterval(() => this.send({ type: 'ping' }), 10_000);
  }

  private send(frame: Record<string, unknown>): void {
    if (this.socket.readyState !== 1) return;
    this.socket.send(JSON.stringify(frame));
  }

  private dispatch(frame: ParsedFrame): void {
    if (frame.type === 'pong' || frame.type === 'ready') return;
    // Terminal frames are mutually exclusive; anything after the first one is a protocol leftover.
    if (this.terminated) return;
    if (frame.type === 'sentence') {
      const sentenceId = typeof frame.sentenceId === 'number' ? frame.sentenceId : 0;
      const text = typeof frame.text === 'string' ? frame.text : '';
      if (sentenceId <= 0) return;
      this.handlers.onEvent({ type: 'sentence', sentenceId, text, isFinal: frame.isFinal === true });
      return;
    }
    if (this.terminated) return;
    this.terminated = true;
    this.stopHeartbeat();
    if (frame.type === 'done') {
      this.handlers.onEvent({
        type: 'done',
        usage: readUsage(frame.usage),
        notice: typeof frame.notice === 'string' ? frame.notice : null,
      });
      return;
    }
    if (frame.type === 'cancelled') {
      this.handlers.onEvent({ type: 'cancelled' });
      return;
    }
    // Validation failures and upstream errors both land here, with a code the UI can act on.
    if (frame.type === 'error') {
      this.handlers.onEvent({
        type: 'error',
        code: typeof frame.code === 'string' ? frame.code : 'UNAVAILABLE',
        message: typeof frame.message === 'string' ? frame.message : 'Recognition failed',
        partial: frame.partial === true,
      });
    }
  }

  private stopHeartbeat(): void {
    if (this.heartbeat === null) return;
    clearInterval(this.heartbeat);
    this.heartbeat = null;
  }
}

interface ParsedFrame {
  type: string;
  [key: string]: unknown;
}

/** Only this project's own JSON frames are expected downstream; anything else is ignored. */
function parseFrame(data: unknown): ParsedFrame | null {
  if (typeof data !== 'string') return null;
  try {
    const parsed = JSON.parse(data) as unknown;
    if (!parsed || typeof parsed !== 'object') return null;
    const type = (parsed as { type?: unknown }).type;
    if (typeof type !== 'string' || type === '') return null;
    return parsed as ParsedFrame;
  } catch {
    return null;
  }
}

function readUsage(value: unknown): AsrRealtimeUsage | null {
  if (!value || typeof value !== 'object') return null;
  const source = value as Record<string, unknown>;
  const num = (key: string): number | null => (typeof source[key] === 'number' ? (source[key] as number) : null);
  return {
    inputTokens: num('inputTokens'),
    outputTokens: num('outputTokens'),
    totalTokens: num('totalTokens'),
    durationSeconds: num('durationSeconds'),
  };
}
