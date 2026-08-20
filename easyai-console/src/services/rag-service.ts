import { fetchJson, fetchVoid, JSON_HEADERS } from '@/services/api-client';

export interface RagStatus {
  enabled: boolean;
  baseUrl: string;
  username: string | null;
  password: string | null;
  workspace: string | null;
  topK: number;
  readTimeoutMs: number;
  indexTimeoutMs: number;
  connected: boolean;
}

export interface RagUpdateRequest {
  enabled?: boolean;
  baseUrl?: string;
  username?: string;
  password?: string;
  workspace?: string;
  topK?: number;
  readTimeoutMs?: number;
  indexTimeoutMs?: number;
}

export interface RagTestResult {
  connected: boolean;
  latencyMs: number;
  message: string;
}

/**
 * Workspace-granular tenant configuration from EasyRAG.
 * All fields except `workspace` are nullable — `null` means "use global server default".
 * API keys are masked by the server (first 4 chars + `****`).
 */
export interface WorkspaceTenantConfig {
  workspace: string;
  llmModel: string | null;
  llmApiKey: string | null;
  llmBaseUrl: string | null;
  llmTemperature: number | null;
  llmMaxTokens: number | null;
  embeddingModel: string | null;
  embeddingApiKey: string | null;
  embeddingBaseUrl: string | null;
  embeddingDim: number | null;
  chunkSize: number | null;
  chunkOverlapSize: number | null;
  language: string | null;
  defaultTopK: number | null;
  rerankEnabled: boolean | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Request body for upserting workspace tenant configuration.
 * All fields except `workspace` are optional; `undefined` means "do not modify".
 * API keys sent as `"****"` or empty string preserve the existing value.
 */
export interface WorkspaceTenantConfigUpdate {
  workspace: string;
  llmModel?: string;
  llmApiKey?: string;
  llmBaseUrl?: string;
  llmTemperature?: number;
  llmMaxTokens?: number;
  embeddingModel?: string;
  embeddingApiKey?: string;
  embeddingBaseUrl?: string;
  embeddingDim?: number;
  chunkSize?: number;
  chunkOverlapSize?: number;
  language?: string;
  defaultTopK?: number;
  rerankEnabled?: boolean;
}

class RagService {
  /**
   * Get RAG configuration status (password is masked) plus live connectivity.
   */
  async getStatus(): Promise<RagStatus> {
    return fetchJson<RagStatus>('/api/system/rag/status');
  }

  /**
   * Update RAG settings (partial update semantics).
   */
  async updateSettings(request: RagUpdateRequest): Promise<{ success: boolean; message: string }> {
    return fetchJson<{ success: boolean; message: string }>('/api/system/rag', {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  /**
   * Test connectivity to the configured EasyRAG server.
   */
  async testConnection(): Promise<RagTestResult> {
    return fetchJson<RagTestResult>('/api/system/rag/test', { method: 'POST' });
  }

  /**
   * Get the workspace-granular tenant configuration from EasyRAG.
   */
  async getWorkspaceConfig(workspace: string): Promise<WorkspaceTenantConfig> {
    return fetchJson<WorkspaceTenantConfig>(
      `/api/system/rag/workspace-config?workspace=${encodeURIComponent(workspace)}`
    );
  }

  /**
   * Create or update the workspace tenant configuration in EasyRAG.
   */
  async updateWorkspaceConfig(request: WorkspaceTenantConfigUpdate): Promise<WorkspaceTenantConfig> {
    return fetchJson<WorkspaceTenantConfig>('/api/system/rag/workspace-config', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  /**
   * Delete the workspace tenant configuration in EasyRAG (revert to global defaults).
   */
  async resetWorkspaceConfig(workspace: string): Promise<void> {
    return fetchVoid(
      `/api/system/rag/workspace-config?workspace=${encodeURIComponent(workspace)}`,
      { method: 'DELETE' }
    );
  }
}

export const ragService = new RagService();
