import { fetchJson, JSON_HEADERS } from '@/services/api-client';

export interface WebSearchStatus {
  configured: boolean;
  exaConfigured: boolean;
  parallelConfigured: boolean;
  exaApiKey: string | null;
  parallelApiKey: string | null;
  provider: string;
}

export interface IntegrationStatus {
  webSearch: WebSearchStatus;
}

export interface IntegrationUpdateRequest {
  exaApiKey?: string;
  parallelApiKey?: string;
  websearchProvider?: string;
}

class IntegrationService {
  /**
   * Get integration configuration status (API keys are masked).
   */
  async getStatus(): Promise<IntegrationStatus> {
    return fetchJson<IntegrationStatus>('/api/system/integrations/status');
  }

  /**
   * Update integration settings.
   */
  async updateSettings(request: IntegrationUpdateRequest): Promise<{ success: boolean; message: string }> {
    return fetchJson<{ success: boolean; message: string }>('/api/system/integrations', {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }
}

export const integrationService = new IntegrationService();
