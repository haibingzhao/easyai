import { fetchJson, fetchVoid, JSON_HEADERS } from './api-client';
import type {
  MemoryEntry,
  MemoryConfig,
  CreateMemoryRequest,
  UpdateMemoryRequest,
  MemoryConsolidationSettings,
  UpdateMemoryConsolidationSettingsRequest,
  ConsolidationRequest,
  ConsolidationResponse,
} from '@/types/memory';

function buildParams(params: Record<string, string | undefined>): URLSearchParams {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value != null && value !== '') search.set(key, value);
  }
  return search;
}

/**
 * Ids per `/archived` request. The backend answers at most 200 (`MemoryController.MAX_ARCHIVE_READ`),
 * and a page of UUIDs goes in the query string, so this keeps a single-merged-entry read well inside
 * the ~8 KB header limit any reverse proxy in front of the server may impose.
 */
const ARCHIVE_READ_PAGE_SIZE = 100;

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

  /**
   * Archived (absorbed) entries by docId — the drill-down behind a merged entry's refs.
   * Normal listing never returns archived entries, so this is the only read path into history.
   *
   * Reads go up in pages ([ARCHIVE_READ_PAGE_SIZE]): the backend caps one request, and a long merged
   * entry would otherwise fail the whole drill-down with a 400.
   */
  async listArchived(
    ids: string[],
    scope: 'global' | 'project',
    projectPath?: string | null
  ): Promise<MemoryEntry[]> {
    const unique = Array.from(new Set(ids.filter(Boolean)));
    const fetched: MemoryEntry[] = [];
    for (let offset = 0; offset < unique.length; offset += ARCHIVE_READ_PAGE_SIZE) {
      const page = unique.slice(offset, offset + ARCHIVE_READ_PAGE_SIZE);
      const params = buildParams({ ids: page.join(','), scope, projectPath: projectPath ?? undefined });
      fetched.push(...(await fetchJson<MemoryEntry[]>(`${this.baseUrl}/archived?${params}`)));
    }
    return fetched;
  }

  async getConsolidationSettings(): Promise<MemoryConsolidationSettings> {
    return fetchJson<MemoryConsolidationSettings>(`${this.baseUrl}/consolidation/settings`);
  }

  async updateConsolidationSettings(
    request: UpdateMemoryConsolidationSettingsRequest
  ): Promise<MemoryConsolidationSettings> {
    return fetchJson<MemoryConsolidationSettings>(`${this.baseUrl}/consolidation/settings`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  /** Dry runs return a report inline; real runs return a durable async task id. */
  async consolidate(request: ConsolidationRequest): Promise<ConsolidationResponse> {
    return fetchJson<ConsolidationResponse>(`${this.baseUrl}/consolidation`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
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
