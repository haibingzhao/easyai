import { fetchJson, fetchVoid } from './api-client';
import type { Watch, WatchFindingTask, WatchStatus, WatchUpdatePayload } from '@/types/watch';

/**
 * Client for `/api/watches`: definitions, the findings timeline, and the manual controls.
 * Findings are task rows read by session on the server, so this service never touches `/api/tasks`.
 */
class WatchApi {
  private baseUrl = '/api/watches';

  async list(status?: WatchStatus | 'all'): Promise<Watch[]> {
    const query = status && status !== 'all' ? `?status=${encodeURIComponent(status)}` : '';
    return fetchJson<Watch[]>(`${this.baseUrl}${query}`);
  }

  async get(id: string): Promise<Watch> {
    return fetchJson<Watch>(`${this.baseUrl}/${encodeURIComponent(id)}`);
  }

  async findings(id: string, limit = 50): Promise<WatchFindingTask[]> {
    return fetchJson<WatchFindingTask[]>(
      `${this.baseUrl}/${encodeURIComponent(id)}/findings?limit=${limit}`,
    );
  }

  async update(id: string, payload: WatchUpdatePayload): Promise<Watch> {
    return fetchJson<Watch>(`${this.baseUrl}/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  }

  async pause(id: string): Promise<Watch> {
    return fetchJson<Watch>(`${this.baseUrl}/${encodeURIComponent(id)}/pause`, { method: 'POST' });
  }

  async resume(id: string): Promise<Watch> {
    return fetchJson<Watch>(`${this.baseUrl}/${encodeURIComponent(id)}/resume`, { method: 'POST' });
  }

  async cancel(id: string): Promise<Watch> {
    return fetchJson<Watch>(`${this.baseUrl}/${encodeURIComponent(id)}/cancel`, { method: 'POST' });
  }

  async runNow(id: string): Promise<{ taskId: string; status: string }> {
    return fetchJson<{ taskId: string; status: string }>(
      `${this.baseUrl}/${encodeURIComponent(id)}/run-now`,
      { method: 'POST' },
    );
  }

  async remove(id: string): Promise<void> {
    return fetchVoid(`${this.baseUrl}/${encodeURIComponent(id)}`, { method: 'DELETE' });
  }
}

export const watchService = new WatchApi();
