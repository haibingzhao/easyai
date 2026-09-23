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
export type Protocol = 'OPENAI' | 'ANTHROPIC';

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

// Media-generation provider settings types (Settings → Media)

/** Which service a credential row drives. One row per (user, kind). */
export type MediaServiceKind = 'speech' | 'image' | 'video';

/** Which layer is in force right now: the user's row, the shared system row, or nothing configured. */
export type MediaProviderEffectiveSource = 'user' | 'system' | 'none';

export interface MediaProviderConfig {
  serviceKind: MediaServiceKind;
  enabled: boolean;
  /** Vendor adapter selector: openai | dashscope | kling. */
  providerType: string;
  baseUrl: string;
  region: string;
  /** Masked by the server; null means nothing stored yet. */
  apiKey: string | null;
  accessKeyId: string;
  /** Masked by the server; null means nothing stored yet. */
  accessKeySecret: string | null;
  defaultModel: string;
  options: string;
  timeoutSeconds: number;
  effectiveSource: MediaProviderEffectiveSource;
}

/** Save draft; a null/blank apiKey/accessKeySecret keeps the stored credential. */
export interface SaveMediaProviderRequest {
  enabled?: boolean;
  providerType?: string;
  baseUrl?: string;
  region?: string;
  apiKey?: string;
  accessKeyId?: string;
  accessKeySecret?: string;
  defaultModel?: string;
  options?: string;
  timeoutSeconds?: number;
}

export interface MediaProviderTestResult {
  success: boolean;
  message: string;
}
