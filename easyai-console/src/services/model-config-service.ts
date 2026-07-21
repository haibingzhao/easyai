import type { ModelProviderInfo, ModelProviderConfig, SaveModelProviderConfigRequest } from '@/types/settings';
import { authFetch } from '@/services/api-client';

const API_BASE = '/api/chat';

export const modelConfigService = {
  /**
   * Get all available model providers from server.
   */
  async getAvailableProviders(): Promise<ModelProviderInfo[]> {
    const response = await authFetch(`${API_BASE}/model-providers`);
    if (!response.ok) {
      throw new Error(`Failed to fetch model providers: ${response.statusText}`);
    }
    return response.json();
  },

  /**
   * Get models for a specific provider.
   */
  async getModelsForProvider(providerId: string): Promise<ModelProviderInfo['models']> {
    const response = await authFetch(`${API_BASE}/model-providers/${providerId}/models`);
    if (!response.ok) {
      throw new Error(`Failed to fetch models for provider ${providerId}: ${response.statusText}`);
    }
    return response.json();
  },

  /**
   * Get user's saved provider configurations.
   */
  async getUserConfigurations(): Promise<ModelProviderConfig[]> {
    const response = await authFetch(`${API_BASE}/model-configs`);
    if (!response.ok) {
      throw new Error(`Failed to fetch user configurations: ${response.statusText}`);
    }
    return response.json();
  },

  /**
   * Save a new provider configuration.
   */
  async saveConfiguration(request: SaveModelProviderConfigRequest): Promise<ModelProviderConfig> {
    const response = await authFetch(`${API_BASE}/model-configs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error(`Failed to save configuration: ${response.statusText}`);
    }
    return response.json();
  },

  /**
   * Delete a provider configuration.
   */
  async deleteConfiguration(id: string): Promise<void> {
    const response = await authFetch(`${API_BASE}/model-configs/${id}`, {
      method: 'DELETE',
    });
    if (!response.ok) {
      throw new Error(`Failed to delete configuration: ${response.statusText}`);
    }
  },
};