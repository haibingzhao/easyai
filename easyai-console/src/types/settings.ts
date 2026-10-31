/**
 * How a reply is turned into speech.
 *
 * - `direct` reads the text as written, one clip — fastest, and the right default for code.
 * - `refine` plans one validated single-voice script, so numbers and terms survive intact.
 * - `director` plans several segments and plays them back to back — better for long explanations,
 *   at the cost of waiting for a plan.
 */
export type ReadAloudMode = 'direct' | 'refine' | 'director';

/**
 * How a rewritten read-aloud should *sound*.
 *
 * Separate from [ReadAloudMode] on purpose: a mode decides whether the text is rewritten at all and
 * into how many segments, while a tone decides how it reads once there is a rewrite. `direct` never
 * reaches the planner, so the tone has no effect there. Keys mirror the backend `SpeechTone` enum —
 * the instruction text itself lives server-side, so the prompt has one owner.
 */
export type ReadAloudTone =
  | 'default'
  | 'professional'
  | 'friendly'
  | 'blunt'
  | 'imaginative'
  | 'efficient'
  | 'sarcastic'
  | 'socratic';

export interface Settings {
  language: string;
  theme: 'light' | 'dark' | 'system';
  proxyEnabled: boolean;
  proxyUrl: string;
  apiKey: Record<string, string>;
}

export interface SessionMetadata {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  messageCount: number;
}

// Model provider types
export type Protocol = 'OPENAI' | 'ANTHROPIC' | 'DASHSCOPE' | 'KLING';

/** Model population: CHAT rows drive the ReAct loop and pickers; the rest are tool-reached generation backends. */
export type ModelType = 'CHAT' | 'IMAGE' | 'VIDEO' | 'SPEECH' | 'MUSIC' | 'ASR';

export interface ModelInfo {
  id: string;
  name: string;
  isCustom: boolean;
  description?: string;
}

export interface ModelProviderInfo {
  id: string;
  name: string;
  protocol: Protocol;
  isCustom: boolean;
  models: ModelInfo[];
  description?: string;
}

export interface ModelOptions {
  /**
   * Sampling temperature. Left empty, the field is not sent at all and the provider's own
   * per-model default applies — which is not observable afterwards, so a value that matters for
   * reproducibility has to be typed in.
   */
  temperature?: number;
  maxTokens?: number;
  thinking?: boolean;
  /** One of the profile's effortLevels; the level set is model-specific. */
  effort?: string;
  maxContextTokens?: number;
  contextToken?: number;
}

/**
 * How a model exposes thinking. `UNKNOWN` keeps the controls editable and lets the adapters fall
 * back to their legacy behavior; profiles are resolved from the model id for known families.
 */
export type ReasoningMode = 'NONE' | 'TOGGLEABLE' | 'ALWAYS_ON' | 'UNKNOWN';

/** Source of a reasoning profile: vendor documentation, catalog fallback, or a user declaration. */
export type ReasoningSource = 'vendor-doc' | 'catalog' | 'manual' | 'unknown';

/** Thinking/effort matrix shared by catalog rows, provider configs and the composer picker. */
export interface ReasoningProfile {
  mode: ReasoningMode;
  /** Canonical order low→max; empty means the model exposes no effort knob. */
  effortLevels: string[];
  defaultThinking: boolean;
  /** Undefined/null means "automatic" — the provider/request default applies. */
  defaultEffort?: string;
  source: ReasoningSource;
}

export type StructuredOutputSupport = 'JSON_SCHEMA' | 'JSON_OBJECT' | 'NONE';

export type SamplingSupport = 'SETTABLE' | 'NOT_SETTABLE';

export interface ModelCapabilities {
  vision?: boolean;
  /** API-level structured output support. Undefined = undeclared (treated as NONE, prompt fallback). */
  structuredOutput?: StructuredOutputSupport;
  /**
   * Whether sampling parameters may be sent at all. Undefined = undeclared (treated as SETTABLE).
   * Mark NOT_SETTABLE for models whose endpoint rejects the field — several current reasoning
   * models return an error for any request carrying temperature, including its own default.
   */
  sampling?: SamplingSupport;
  /** Reasoning / tool-calling support. Undefined or true = capable; false marks decision models. */
  supportsToolCalling?: boolean;
  /** Thinking/effort matrix; undefined on rows not saved since profiles were introduced. */
  reasoning?: ReasoningProfile;
}

/** Per-chat overrides sent from the composer's model picker; never written to the model config. */
export interface ModelOverrides {
  effort?: string;
  contextToken?: number;
}

export interface ModelProviderConfig {
  id: string;
  name: string;
  protocol: Protocol;
  isCustom: boolean;
  baseUrl?: string;
  apiKey?: string;
  modelId: string;
  modelName?: string;
  isCustomModel: boolean;
  enabled: boolean;
  options?: ModelOptions;
  /** HTTP timeout in seconds for LLM API calls. Defaults to 600 (10 minutes). */
  timeoutSeconds?: number;
  capabilities?: ModelCapabilities;
  /** Group ID this config belongs to. Null for ungrouped configs. */
  groupId?: string;
  /** Model population; omitted/unset means CHAT. */
  modelType?: ModelType;
  /** Generation parameters as a raw JSON object string (generation rows only; never parsed as ModelOptions). */
  mediaOptions?: string;
  /** True for at most one entry per owner + modelType: the fallback when a tool names no model. */
  isDefault?: boolean;
}

export interface SaveModelProviderConfigRequest {
  id?: string;
  name: string;
  protocol: Protocol;
  isCustom: boolean;
  baseUrl?: string;
  apiKey?: string;
  modelId: string;
  modelName?: string;
  isCustomModel: boolean;
  enabled: boolean;
  options?: ModelOptions;
  /** HTTP timeout in seconds for LLM API calls. Defaults to 600 (10 minutes). */
  timeoutSeconds?: number;
  capabilities?: ModelCapabilities;
  /** Group ID to associate this config with. */
  groupId?: string;
  modelType?: ModelType;
  mediaOptions?: string;
  isDefault?: boolean;
}

export interface ModelConfigGroup {
  id: string;
  name: string;
  protocol: Protocol;
  isCustom: boolean;
  baseUrl?: string;
  apiKey?: string;
  timeoutSeconds?: number;
  models: ModelProviderConfig[];
}

export interface SaveModelConfigGroupRequest {
  id?: string;
  name: string;
  protocol: Protocol;
  isCustom: boolean;
  baseUrl?: string;
  apiKey?: string;
  timeoutSeconds?: number;
}

// Model catalog types (global, vendor-published facts — Models page "Model Catalog" tab)

/** Capability flags as published by the vendor; mirrors the backend CatalogCapabilities. */
export interface CatalogCapabilities {
  vision?: boolean;
  structuredOutput?: StructuredOutputSupport;
  supportsToolCalling?: boolean;
  /** Whether the model supports reasoning/thinking; a selection hint, never auto-enabled on prefills. */
  thinking?: boolean;
}

/** Suggested token limits from the vendor for a fresh model config. */
export interface CatalogOptions {
  maxTokens?: number;
  maxContextTokens?: number;
}

/** Vendor pricing per one million tokens in `currency`, for cost-aware model selection. */
export interface ModelPricing {
  currency: string;
  inputPerMTok?: number;
  outputPerMTok?: number;
  cachedInputPerMTok?: number;
}

export interface ModelCatalogEntry {
  modelId: string;
  vendor: string;
  displayName: string;
  description?: string;
  capabilities?: CatalogCapabilities;
  defaultOptions?: CatalogOptions;
  /** Speech-synthesis facts for TTS models; absent for every other kind and for hand-added rows. */
  speechCapabilities?: SpeechCapabilities;
  /** Resolved thinking matrix; the lookup endpoint also returns one for ids the catalog lacks. */
  reasoningProfile?: ReasoningProfile;
  pricing?: ModelPricing;
  /** Which pipeline wrote the row: 'bailian-refresh' today. */
  source: string;
  /** Vendor item JSON verbatim (unmapped fields like maxInputTokens live here). */
  rawPayload?: string;
  createdAt: number;
  updatedAt: number;
}

export interface CatalogRefreshResult {
  /** Failures ride a 200 with the upstream message — the gateway answers HTTP 200 even on business errors. */
  success: boolean;
  message: string;
  inserted: number;
  updated: number;
}

/** One `optgroup` of the vendor's voice table, in publication order. */
export interface VoiceGroup {
  key: string;
  label: string;
  count: number;
}

/**
 * One system voice. `traits` / `scenarios` are the vendor's own wording and are the only text a
 * model can use to choose a voice it cannot hear; `ssml` / `instruction` / `timestamp` are per-voice
 * conclusions and must not be inferred from the model-level flags.
 */
export interface VoiceSpec {
  /** The exact `voice` parameter value. */
  voice: string;
  name: string;
  group?: string;
  groupLabel?: string;
  /** 'female' | 'male' | 'child'; absent when the vendor does not say. */
  gender?: string;
  age?: number;
  traits: string[];
  scenarios: string[];
  accent?: string;
  /** BCP-47 language tags. */
  languages: string[];
  languageLabel?: string;
  /** The vendor did not state a language: the tags above are an inference, and the UI must say so. */
  languageInferred?: boolean;
  localName?: string;
  /** Dialects reachable through `instruction` for this voice. */
  dialects: string[];
  ssml?: boolean;
  instruction?: boolean;
  timestamp?: boolean;
}

/** Vendor-published speech facts for one TTS model; drives the whole voice form. */
export interface SpeechCapabilities {
  supportsInstruction: boolean;
  supportsEmotionTags: boolean;
  supportsSsml: boolean;
  supportsLatex: boolean;
  supportsWordTimestamp: boolean;
  supportsHotFix: boolean;
  /** Output formats the model accepts, e.g. ['mp3','pcm','wav','opus']. */
  formats: string[];
  /** Discrete sample rates in Hz. */
  sampleRates: number[];
  /** Protocol ceiling for one `continue-task` frame. */
  maxCharsPerFrame: number;
  /** Protocol ceiling for one task across all frames. */
  maxCharsPerTask: number;
  docsUrl?: string;
  /** Control tags: they change the delivery of everything after them, e.g. `[excited]`. */
  emotionTags: string[];
  /** Paralinguistic tags: one inserted sound at their position, e.g. `[laughing]`. */
  paralinguisticTags?: string[];
  voiceGroups: VoiceGroup[];
  defaultVoice?: string;
  voices: VoiceSpec[];
  /** Recognition facts; present on ASR rows only. */
  asr?: AsrCapabilities;
}

/** Which transport an ASR model can be driven over; `none` means it cannot take a microphone. */
export type AsrChannel = 'websocket' | 'http' | 'none';

/** `enum`/`int`/`bool`/`string`/`string[]`/`map` — selects the control rendered for one parameter. */
export type AsrParamType = 'enum' | 'int' | 'bool' | 'string' | 'string[]' | 'map';

/**
 * One settable `run-task` parameter.
 *
 * `default` is the value this project sends when the row sets none, which is not always
 * `vendorDefault` (short chat dictation closes sentences sooner than the vendor default); `note`
 * explains such a difference in the form.
 */
export interface AsrParamSpec {
  type: AsrParamType;
  options?: string[];
  min?: number;
  max?: number;
  unit?: string;
  default?: unknown;
  vendorDefault?: unknown;
  note?: string;
  /** Fixed by the product: shown read-only, never written into `media_options`. */
  locked?: boolean;
  /** Documented as affecting the final result only; a form hint, not a data-flow branch. */
  finalOnly?: boolean;
  maxItems?: number;
  maxSuper?: number;
  maxMerged?: number;
}

/** Abilities that are not request parameters; recorded so the form can state them without a control. */
export interface AsrFeatures {
  context?: string;
  continueTask?: boolean;
  speakerDiarization?: boolean;
  emotion?: boolean;
}

/** Recognition facts for one ASR model; drives the whole capability form. */
export interface AsrCapabilities {
  channel: AsrChannel;
  docsUrl?: string;
  audio?: { formats: string[]; sampleRates: number[] };
  params?: Record<string, AsrParamSpec>;
  features?: AsrFeatures;
}

// Object-storage settings types (Settings → Storage)

/**
 * Which layer is in force right now: the user's row, the shared system row, a deployment-wide
 * `easyai.storage.*` layer (`static` — the form is hidden and saves are refused), or nothing.
 */
export type StorageEffectiveSource = 'user' | 'system' | 'static' | 'none';

export type StorageBackendType = 'aliyun' | 'local';

export interface StorageConfig {
  enabled: boolean;
  type: StorageBackendType;
  endpoint: string;
  bucket: string;
  accessKeyId: string;
  /** Masked by the server; null means nothing stored yet. */
  accessKeySecret: string | null;
  localDir: string;
  effectiveSource: StorageEffectiveSource;
}

/** Save draft; a null/blank accessKeySecret keeps the stored credential. */
export interface SaveStorageConfigRequest {
  enabled?: boolean;
  type?: StorageBackendType;
  endpoint?: string;
  bucket?: string;
  accessKeyId?: string;
  accessKeySecret?: string;
  localDir?: string;
}

export interface StorageTestResult {
  success: boolean;
  message: string;
}

/** Outcome of a generation-config structural probe (POST /model-configs/test). */
export interface ModelConfigTestResult {
  success: boolean;
  message: string;
}

// Auxiliary (per-task) model settings types (Settings → Task Models)

/** A background purpose that can be backed by its own model. Mirrors the backend AuxModelTask enum. */
export type AuxModelTaskKey = 'compaction' | 'session_title' | 'skill_selection' | 'asr' | 'dictation_refine' | 'memory_curation' | 'tts' | 'speech_direction';

/** Which layer is in force for a task: the user's choice, or the default (chat-session model). */
export type AuxModelEffectiveSource = 'user' | 'default';

export interface AuxModelConfig {
  taskKey: AuxModelTaskKey;
  /** Referenced model_provider_config ID; blank means "follow the default model". */
  modelConfigId: string;
  effectiveSource: AuxModelEffectiveSource;
}

/** Save draft; a null/blank modelConfigId clears the choice and reverts to the default. */
export interface SaveAuxModelRequest {
  modelConfigId?: string | null;
}

