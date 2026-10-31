import type { SpeechSegment, SpeechWarning, SpeechTargetRef } from '@/services/tts-service';

/** Lifecycle of a stored script: machine-saved, user-pinned, or soft-deleted. */
export type ScriptStatus = 'AUTO' | 'PINNED' | 'ARCHIVED';

/** Which pipeline produced the script; `refine` is a single segment, `director` is a full plan. */
export type ScriptMode = 'refine' | 'director';

/** The same values the server accepts in `target_type`; unknown values are rendered as raw text. */
export type ScriptTargetType = SpeechTargetRef['type'];

/** A list row. Deliberately carries no segments: a long plan is tens of kilobytes. */
export interface ScriptSummary {
  id: string;
  title: string;
  targetType: string;
  targetId: string | null;
  sessionId: string | null;
  mode: string;
  status: string;
  revision: number;
  segmentCount: number;
  sourceChars: number;
  spokenChars: number;
  style: string;
  mood: string | null;
  favorite: boolean;
  playCount: number;
  lastPlayedAt: number | null;
  createdAt: number;
  updatedAt: number;
}

/** The model row that produced the audio — a snapshot, so it survives renames and deletions. */
export interface ScriptProviderParams {
  modelConfigId: string;
  modelId: string;
  modelDisplayName?: string | null;
  protocol: string;
  /** Host only: enough to spot a provider change, without recording a tenant path. */
  baseUrlHost?: string | null;
}

export interface ScriptSynthesisParams {
  format: string;
  mimeType: string;
  sampleRate?: number | null;
  bitRate?: number | null;
  inputMode: string;
  timeoutSeconds: number;
}

export interface ScriptRowDefaults {
  voice?: string | null;
  wordTimestampEnabled?: boolean | null;
  /** The row's own media options, verbatim: new keys appear here without a schema change. */
  options?: Record<string, unknown>;
}

export interface ScriptOverrideParams {
  voice?: string | null;
  instruction?: string | null;
  emotionTag?: string | null;
  rate?: number | null;
  pitch?: number | null;
}

export interface ScriptSegmentParams {
  segmentId: string;
  voice: string;
  emotion?: string | null;
  rate?: number | null;
  role: string;
  pauseAfterMs: number;
  sourceChars: number;
  spokenChars: number;
}

export interface ScriptCostParams {
  maxChars: number;
  characters: number;
}

/**
 * Everything that was asked of the TTS model.
 *
 * Snapshot, not a reference: this is what makes "why did it sound like that" answerable after the
 * model row has been renamed, retyped or deleted. It never contains credentials.
 */
export interface ScriptTtsParams {
  schemaVersion: number;
  capturedAt: number;
  mode: string;
  provider: ScriptProviderParams;
  synthesis: ScriptSynthesisParams;
  rowDefaults?: ScriptRowDefaults | null;
  overrides: ScriptOverrideParams;
  perSegment: ScriptSegmentParams[];
  cost: ScriptCostParams;
}

/** The planning model, when the script was directed rather than read straight through. */
export interface ScriptDirectionParams {
  modelConfigId: string;
  modelId: string;
  protocol: string;
  samplingIntent: string;
  thinking: boolean;
  strategy: string;
  promptVersion: number;
  promptHash: string;
  schemaVersion: number;
  timeoutMs: number;
}

/** A stored script in full. `segments[].sourceText` is the authority an edit is checked against. */
export interface ScriptDetail extends ScriptSummary {
  sourceText: string;
  segments: SpeechSegment[];
  warnings: SpeechWarning[];
  ttsParams: ScriptTtsParams;
  directionParams: ScriptDirectionParams | null;
}

/** List filters; every field maps to a query parameter the server understands. */
export interface ScriptListQuery {
  type?: string;
  sessionId?: string;
  status?: ScriptStatus;
  mode?: string;
  favorite?: boolean;
  q?: string;
  sort?: 'UPDATED' | 'LAST_PLAYED' | 'PLAY_COUNT';
  limit?: number;
  offset?: number;
}

/** What a pin request sends: the plan the caller is holding, plus where it came from. */
export interface PinScriptRequest {
  scriptId: string;
  title?: string;
  target?: SpeechTargetRef;
}
