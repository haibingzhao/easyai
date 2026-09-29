import type { AuxModelConfig, AuxModelTaskKey, SaveAuxModelRequest } from '@/types/settings';
import { fetchJson, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/aux-models';

export const auxModelConfigService = {
  /**
   * List every auxiliary-model task with the current user's choice and the layer in force.
   */
  async list(): Promise<AuxModelConfig[]> {
    return fetchJson<AuxModelConfig[]>(API_BASE);
  },

  /**
   * Set (or, with a blank modelConfigId, clear) the model for one task; takes effect immediately.
   */
  async save(taskKey: AuxModelTaskKey, request: SaveAuxModelRequest): Promise<AuxModelConfig> {
    return fetchJson<AuxModelConfig>(`${API_BASE}/${taskKey}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },
};
