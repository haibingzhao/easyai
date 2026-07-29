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
  maxContextTokens?: number;
  contextToken?: number;
}

export interface ModelCapabilities {
  vision?: boolean;
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
