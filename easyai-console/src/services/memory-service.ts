import { authFetch } from './api-client';
import type { MemoryEntry, MemoryConfig, CreateMemoryRequest } from '@/types/memory';

class MemoryService {
  private baseUrl = '/api/memories';

  async listMemories(scope: 'global' | 'project', type?: string): Promise<MemoryEntry[]> {
    const params = new URLSearchParams({ scope });
    if (type) params.set('type', type);
    const response = await authFetch(`${this.baseUrl}?${params}`);
    if (!response.ok) throw new Error(`Failed to list memories: ${response.statusText}`);
    return response.json();
  }

  async getMemory(name: string, scope: 'global' | 'project'): Promise<MemoryEntry> {
    const params = new URLSearchParams({ scope });
    const response = await authFetch(`${this.baseUrl}/${encodeURIComponent(name)}?${params}`);
    if (!response.ok) throw new Error(`Failed to get memory: ${response.statusText}`);
    return response.json();
  }

  async createOrUpdateMemory(request: CreateMemoryRequest): Promise<MemoryEntry> {
    const response = await authFetch(this.baseUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(`Failed to create memory: ${response.statusText}`);
    return response.json();
  }

  async deleteMemory(name: string, scope: 'global' | 'project'): Promise<void> {
    const params = new URLSearchParams({ scope });
    const response = await authFetch(`${this.baseUrl}/${encodeURIComponent(name)}?${params}`, {
      method: 'DELETE',
    });
    if (!response.ok) throw new Error(`Failed to delete memory: ${response.statusText}`);
  }

  async deleteAllMemories(scope: 'global' | 'project'): Promise<{ deleted: number }> {
    const params = new URLSearchParams({ scope });
    const response = await authFetch(`${this.baseUrl}?${params}`, {
      method: 'DELETE',
    });
    if (!response.ok) throw new Error(`Failed to delete all memories: ${response.statusText}`);
    return response.json();
  }

  async getConfig(): Promise<MemoryConfig> {
    const response = await authFetch(`${this.baseUrl}/config`);
    if (!response.ok) throw new Error(`Failed to get memory config: ${response.statusText}`);
    return response.json();
  }

  async updateConfig(config: { enabled?: boolean }): Promise<MemoryConfig> {
    const response = await authFetch(`${this.baseUrl}/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config),
    });
    if (!response.ok) throw new Error(`Failed to update memory config: ${response.statusText}`);
    return response.json();
  }
}

export const memoryService = new MemoryService();
