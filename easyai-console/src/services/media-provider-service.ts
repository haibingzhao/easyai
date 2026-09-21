import type {
  MediaProviderConfig,
  MediaProviderTestResult,
  MediaServiceKind,
  SaveMediaProviderRequest,
} from '@/types/settings';
import { fetchJson, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/media/providers';

export const mediaProviderService = {
  /**
   * List all service kinds for the current user, secrets masked, each with the layer in force.
   */
  async list(): Promise<MediaProviderConfig[]> {
    return fetchJson<MediaProviderConfig[]>(API_BASE);
  },

  /**
   * Get one service kind's configuration with the layer in force.
   */
  async getConfig(kind: MediaServiceKind): Promise<MediaProviderConfig> {
    return fetchJson<MediaProviderConfig>(`${API_BASE}/${kind}`);
  },

  /**
   * Save one kind; a valid enabled draft takes effect for the next generation call, no restart.
   */
  async saveConfig(kind: MediaServiceKind, request: SaveMediaProviderRequest): Promise<MediaProviderConfig> {
    return fetchJson<MediaProviderConfig>(`${API_BASE}/${kind}`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },

  /**
   * Probe a draft without persisting anything (blank secrets fall back to the stored ones).
   */
  async testConfig(kind: MediaServiceKind, request: SaveMediaProviderRequest): Promise<MediaProviderTestResult> {
    return fetchJson<MediaProviderTestResult>(`${API_BASE}/${kind}/test`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },
};
