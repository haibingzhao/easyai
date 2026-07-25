import { fetchJson, fetchVoid, JSON_HEADERS } from './api-client';
import type { MemoryEntry, MemoryConfig, CreateMemoryRequest } from '@/types/memory';

class MemoryService {
  private baseUrl = '/api/memories';

  async listMemories(scope: 'global' | 'project', type?: string): Promise<MemoryEntry[]> {
    const params = new URLSearchParams({ scope });
    if (type) params.set('type', type);
    return fetchJson<MemoryEntry[]>(`${this.baseUrl}?${params}`);
  }

  async getMemory(name: string, scope: 'global' | 'project'): Promise<MemoryEntry> {
    const params = new URLSearchParams({ scope });
    return fetchJson<MemoryEntry>(`${this.baseUrl}/${encodeURIComponent(name)}?${params}`);
  }

  async createOrUpdateMemory(request: CreateMemoryRequest): Promise<MemoryEntry> {
    return fetchJson<MemoryEntry>(this.baseUrl, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async deleteMemory(name: string, scope: 'global' | 'project'): Promise<void> {
    const params = new URLSearchParams({ scope });
    return fetchVoid(`${this.baseUrl}/${encodeURIComponent(name)}?${params}`, { method: 'DELETE' });
  }

  async deleteAllMemories(scope: 'global' | 'project'): Promise<{ deleted: number }> {
    const params = new URLSearchParams({ scope });
    return fetchJson<{ deleted: number }>(`${this.baseUrl}?${params}`, { method: 'DELETE' });
  }

  async getConfig(): Promise<MemoryConfig> {
    return fetchJson<MemoryConfig>(`${this.baseUrl}/config`);
  }

  async updateConfig(config: { enabled?: boolean }): Promise<MemoryConfig> {
    return fetchJson<MemoryConfig>(`${this.baseUrl}/config`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(config),
    });
  }
}

export const memoryService = new MemoryService();
