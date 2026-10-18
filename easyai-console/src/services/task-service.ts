import { fetchJson, fetchVoid } from './api-client';
import type { AsyncTask, TaskFilters } from '@/types/task';

function buildParams(params: Record<string, string | number | undefined>): URLSearchParams {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value != null && value !== '') search.set(key, String(value));
  }
  return search;
}

/**
 * Client for the durable `/api/tasks` surface — the read/control plane for app-scoped async tasks.
 * Execution is server-side and session-independent; this service only lists/inspects and issues
 * cancel / retry / delete commands.
 */
class TaskService {
  private baseUrl = '/api/tasks';

  async listTasks(filters: TaskFilters = {}): Promise<AsyncTask[]> {
    const params = buildParams({
      type: filters.type,
      status: filters.status,
      sessionId: filters.sessionId,
      limit: filters.limit,
    });
    const query = params.toString();
    return fetchJson<AsyncTask[]>(`${this.baseUrl}${query ? `?${query}` : ''}`);
  }

  async getTask(id: string): Promise<AsyncTask> {
    return fetchJson<AsyncTask>(`${this.baseUrl}/${encodeURIComponent(id)}`);
  }

  async cancelTask(id: string): Promise<{ cancelled: boolean }> {
    return fetchJson<{ cancelled: boolean }>(`${this.baseUrl}/${encodeURIComponent(id)}/cancel`, { method: 'POST' });
  }

  async retryTask(id: string): Promise<AsyncTask> {
    return fetchJson<AsyncTask>(`${this.baseUrl}/${encodeURIComponent(id)}/retry`, { method: 'POST' });
  }

  async deleteTask(id: string): Promise<void> {
    return fetchVoid(`${this.baseUrl}/${encodeURIComponent(id)}`, { method: 'DELETE' });
  }

  async clearTasks(status?: string): Promise<{ deleted: number }> {
    const params = buildParams({ status });
    const query = params.toString();
    return fetchJson<{ deleted: number }>(`${this.baseUrl}${query ? `?${query}` : ''}`, { method: 'DELETE' });
  }
}

export const taskService = new TaskService();
