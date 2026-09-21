import type { StorageConfig, SaveStorageConfigRequest, StorageTestResult } from '@/types/settings';
import { fetchJson, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/storage';

export const storageConfigService = {
  /**
   * Get the current user's storage configuration with the layer in force.
   */
  async getConfig(): Promise<StorageConfig> {
    return fetchJson<StorageConfig>(`${API_BASE}/config`);
  },

  /**
   * Save a configuration; a valid one takes effect for the next storage operation, no restart.
   */
  async saveConfig(request: SaveStorageConfigRequest): Promise<StorageConfig> {
    return fetchJson<StorageConfig>(`${API_BASE}/config`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },

  /**
   * Probe a draft without persisting anything (blank secret falls back to the stored one).
   */
  async testConfig(request: SaveStorageConfigRequest): Promise<StorageTestResult> {
    return fetchJson<StorageTestResult>(`${API_BASE}/config/test`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },
};
