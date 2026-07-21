import type { ModelProviderInfo, ModelProviderConfig, ModelConfigGroup, SaveModelProviderConfigRequest, SaveModelConfigGroupRequest } from '@/types/settings';
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

  // ─── Model Config Groups ─────────────────────────────────────────────────────

  /**
   * Get all model config groups with their member models.
   */
  async getGroups(): Promise<ModelConfigGroup[]> {
    const response = await authFetch(`${API_BASE}/model-groups`);
    if (!response.ok) {
      throw new Error(`Failed to fetch model groups: ${response.statusText}`);
    }
    return response.json();
  },

  /**
   * Create a new model config group.
   */
  async saveGroup(request: SaveModelConfigGroupRequest): Promise<ModelConfigGroup> {
    const response = await authFetch(`${API_BASE}/model-groups`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error(`Failed to save model group: ${response.statusText}`);
    }
    return response.json();
  },

  /**
   * Update a group's connection settings (cascades to member configs).
   */
  async updateGroup(id: string, request: SaveModelConfigGroupRequest): Promise<ModelConfigGroup> {
    const response = await authFetch(`${API_BASE}/model-groups/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error(`Failed to update model group: ${response.statusText}`);
    }
    return response.json();
  },

  /**
   * Delete a group and all its member model configs.
   */
  async deleteGroup(id: string): Promise<void> {
    const response = await authFetch(`${API_BASE}/model-groups/${id}`, {
      method: 'DELETE',
    });
    if (!response.ok) {
      throw new Error(`Failed to delete model group: ${response.statusText}`);
    }
  },
};