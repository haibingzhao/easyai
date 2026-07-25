import type { ModelProviderInfo, ModelProviderConfig, ModelConfigGroup, SaveModelProviderConfigRequest, SaveModelConfigGroupRequest } from '@/types/settings';
import { fetchJson, fetchVoid, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/chat';

export const modelConfigService = {
  /**
   * Get all available model providers from server.
   */
  async getAvailableProviders(): Promise<ModelProviderInfo[]> {
    return fetchJson<ModelProviderInfo[]>(`${API_BASE}/model-providers`);
  },

  /**
   * Get models for a specific provider.
   */
  async getModelsForProvider(providerId: string): Promise<ModelProviderInfo['models']> {
    return fetchJson<ModelProviderInfo['models']>(`${API_BASE}/model-providers/${providerId}/models`);
  },

  /**
   * Get user's saved provider configurations.
   */
  async getUserConfigurations(): Promise<ModelProviderConfig[]> {
    return fetchJson<ModelProviderConfig[]>(`${API_BASE}/model-configs`);
  },

  /**
   * Save a new provider configuration.
   */
  async saveConfiguration(request: SaveModelProviderConfigRequest): Promise<ModelProviderConfig> {
    return fetchJson<ModelProviderConfig>(`${API_BASE}/model-configs`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },

  /**
   * Delete a provider configuration.
   */
  async deleteConfiguration(id: string): Promise<void> {
    return fetchVoid(`${API_BASE}/model-configs/${id}`, { method: 'DELETE' });
  },

  // ─── Model Config Groups ─────────────────────────────────────────────────────

  /**
   * Get all model config groups with their member models.
   */
  async getGroups(): Promise<ModelConfigGroup[]> {
    return fetchJson<ModelConfigGroup[]>(`${API_BASE}/model-groups`);
  },

  /**
   * Create a new model config group.
   */
  async saveGroup(request: SaveModelConfigGroupRequest): Promise<ModelConfigGroup> {
    return fetchJson<ModelConfigGroup>(`${API_BASE}/model-groups`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },

  /**
   * Update a group's connection settings (cascades to member configs).
   */
  async updateGroup(id: string, request: SaveModelConfigGroupRequest): Promise<ModelConfigGroup> {
    return fetchJson<ModelConfigGroup>(`${API_BASE}/model-groups/${id}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },

  /**
   * Delete a group and all its member model configs.
   */
  async deleteGroup(id: string): Promise<void> {
    return fetchVoid(`${API_BASE}/model-groups/${id}`, { method: 'DELETE' });
  },
};