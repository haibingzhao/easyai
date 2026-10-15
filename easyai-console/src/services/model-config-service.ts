import type { ModelProviderInfo, ModelProviderConfig, ModelConfigGroup, SaveModelProviderConfigRequest, SaveModelConfigGroupRequest, ModelType, ModelConfigTestResult, ModelCatalogEntry, CatalogRefreshResult } from '@/types/settings';
import { fetchJson, fetchVoid, scopeQuery, JSON_HEADERS } from '@/services/api-client';
import type { AssetScope } from '@/services/api-client';

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
   * Get user's saved provider configurations of one type (CHAT when omitted).
   */
  async getUserConfigurations(modelType?: ModelType): Promise<ModelProviderConfig[]> {
    const query = modelType ? `?modelType=${modelType}` : '';
    return fetchJson<ModelProviderConfig[]>(`${API_BASE}/model-configs${query}`);
  },

  /**
   * Save a new provider configuration. A `group` scope writes the shared bucket (group owner only).
   */
  async saveConfiguration(request: SaveModelProviderConfigRequest, scope?: AssetScope): Promise<ModelProviderConfig> {
    return fetchJson<ModelProviderConfig>(`${API_BASE}/model-configs${scopeQuery(scope)}`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },

  /**
   * Structural probe of a generation draft; never persists. Pass the same `scope` as the intended
   * save so the probe resolves against the same bucket.
   */
  async testGenerationConfig(request: SaveModelProviderConfigRequest, scope?: AssetScope): Promise<ModelConfigTestResult> {
    return fetchJson<ModelConfigTestResult>(`${API_BASE}/model-configs/test${scopeQuery(scope)}`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },

  /**
   * Delete a provider configuration.
   */
  async deleteConfiguration(id: string, scope?: AssetScope): Promise<void> {
    return fetchVoid(`${API_BASE}/model-configs/${id}${scopeQuery(scope)}`, { method: 'DELETE' });
  },

  // ─── Model Config Groups ─────────────────────────────────────────────────────

  /**
   * Get groups with members of one type (CHAT when omitted).
   */
  async getGroups(modelType?: ModelType): Promise<ModelConfigGroup[]> {
    const query = modelType ? `?modelType=${modelType}` : '';
    return fetchJson<ModelConfigGroup[]>(`${API_BASE}/model-groups${query}`);
  },

  /**
   * Create a new model config group.
   */
  async saveGroup(request: SaveModelConfigGroupRequest, scope?: AssetScope): Promise<ModelConfigGroup> {
    return fetchJson<ModelConfigGroup>(`${API_BASE}/model-groups${scopeQuery(scope)}`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },

  /**
   * Update a group's connection settings (cascades to member configs).
   */
  async updateGroup(id: string, request: SaveModelConfigGroupRequest, scope?: AssetScope): Promise<ModelConfigGroup> {
    return fetchJson<ModelConfigGroup>(`${API_BASE}/model-groups/${id}${scopeQuery(scope)}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },

  /**
   * Delete a group and all its member model configs.
   */
  async deleteGroup(id: string, scope?: AssetScope): Promise<void> {
    return fetchVoid(`${API_BASE}/model-groups/${id}${scopeQuery(scope)}`, { method: 'DELETE' });
  },

  // ─── Model Catalog (global, vendor-published facts) ───────────────────────────

  /**
   * Exact catalog lookup for add-model prefill; null when the catalog does not carry the id.
   */
  async lookupModelCatalog(modelId: string): Promise<ModelCatalogEntry | null> {
    const data = await fetchJson<{ entry?: ModelCatalogEntry }>(
      `${API_BASE}/model-catalog/lookup?modelId=${encodeURIComponent(modelId)}`
    );
    return data.entry ?? null;
  },

  /**
   * Every catalog row, for the Model Catalog management page.
   */
  async listModelCatalog(): Promise<ModelCatalogEntry[]> {
    return fetchJson<ModelCatalogEntry[]>(`${API_BASE}/model-catalog`);
  },

  /**
   * Pull the full Bailian model marketplace into the catalog (initializes it on first use).
   */
  async refreshModelCatalog(): Promise<CatalogRefreshResult> {
    return fetchJson<CatalogRefreshResult>(`${API_BASE}/model-catalog/refresh`, { method: 'POST' });
  },
};