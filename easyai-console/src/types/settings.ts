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
  temperature?: number;
  maxTokens?: number;
  thinking?: boolean;
  effort?: 'low' | 'medium' | 'high' | 'xhigh' | 'max';
  maxContextTokens?: number;
  contextToken?: number;
}

export type StructuredOutputSupport = 'JSON_SCHEMA' | 'JSON_OBJECT' | 'NONE';

export interface ModelCapabilities {
  vision?: boolean;
  /** API-level structured output support. Undefined = undeclared (treated as JSON_SCHEMA). */
  structuredOutput?: StructuredOutputSupport;
  /** Reasoning / tool-calling support. Undefined or true = capable; false marks decision models. */
  supportsToolCalling?: boolean;
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

// Object-storage settings types (Settings → Storage)

/** Which layer is in force right now: the user's row, the shared system row, or nothing configured. */
export type StorageEffectiveSource = 'user' | 'system' | 'none';

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
export type AuxModelTaskKey = 'compaction' | 'session_title' | 'skill_selection';

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

