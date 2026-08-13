import { fetchJson, fetchVoid, JSON_HEADERS } from './api-client';
import type { MemoryEntry, MemoryConfig, CreateMemoryRequest, UpdateMemoryRequest } from '@/types/memory';

function buildParams(params: Record<string, string | undefined>): URLSearchParams {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value != null && value !== '') search.set(key, value);
  }
  return search;
}

class MemoryService {
  private baseUrl = '/api/memories';

  async listMemories(
    scope: 'global' | 'project',
    projectPath?: string | null,
    type?: string,
    maturity?: string | null
  ): Promise<MemoryEntry[]> {
    const params = buildParams({ scope, projectPath: projectPath ?? undefined, type, maturity: maturity ?? undefined });
    return fetchJson<MemoryEntry[]>(`${this.baseUrl}?${params}`);
  }

  async getMemory(name: string, scope: 'global' | 'project', projectPath?: string | null): Promise<MemoryEntry> {
    const params = buildParams({ scope, projectPath: projectPath ?? undefined });
    return fetchJson<MemoryEntry>(`${this.baseUrl}/${encodeURIComponent(name)}?${params}`);
  }

  async createOrUpdateMemory(request: CreateMemoryRequest): Promise<MemoryEntry> {
    return fetchJson<MemoryEntry>(this.baseUrl, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async updateMemory(
    name: string,
    scope: 'global' | 'project',
    projectPath: string | null,
    request: UpdateMemoryRequest
  ): Promise<MemoryEntry> {
    const params = buildParams({ scope, projectPath: projectPath ?? undefined });
    return fetchJson<MemoryEntry>(`${this.baseUrl}/${encodeURIComponent(name)}?${params}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async deleteMemory(name: string, scope: 'global' | 'project', projectPath?: string | null): Promise<void> {
    const params = buildParams({ scope, projectPath: projectPath ?? undefined });
    return fetchVoid(`${this.baseUrl}/${encodeURIComponent(name)}?${params}`, { method: 'DELETE' });
  }

  async deleteAllMemories(scope: 'global' | 'project', projectPath?: string | null): Promise<{ deleted: number }> {
    const params = buildParams({ scope, projectPath: projectPath ?? undefined });
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
