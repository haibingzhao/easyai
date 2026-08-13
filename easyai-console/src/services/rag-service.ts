import { fetchJson, JSON_HEADERS } from '@/services/api-client';

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
}

export const ragService = new RagService();
