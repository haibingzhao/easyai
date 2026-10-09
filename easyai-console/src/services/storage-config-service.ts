import type { StorageConfig, SaveStorageConfigRequest, StorageTestResult } from '@/types/settings';
import { fetchJson, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/storage';

/** Which ownership bucket a write targets; `group` writes the shared bucket (group owner only). */
export type AssetScope = 'personal' | 'group';

function scopeQuery(scope?: AssetScope): string {
  return scope ? `?scope=${scope}` : '';
}

export const storageConfigService = {
  /**
   * Get the storage configuration with the layer in force. Pass `scope='group'` to read the shared
   * bucket's row for editing (group owner); the default reads the caller's personal row.
   */
  async getConfig(scope?: AssetScope): Promise<StorageConfig> {
    return fetchJson<StorageConfig>(`${API_BASE}/config${scopeQuery(scope)}`);
  },

  /**
   * Save a configuration; a valid one takes effect for the next storage operation, no restart.
   * A `group` scope writes the shared bucket and is refused (403) for non-owners.
   */
  async saveConfig(request: SaveStorageConfigRequest, scope?: AssetScope): Promise<StorageConfig> {
    return fetchJson<StorageConfig>(`${API_BASE}/config${scopeQuery(scope)}`, {
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
