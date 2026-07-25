import type { McpServerDto, McpServerCreateRequest, McpBulkImportRequest, McpToolInfo, McpPromptInfo } from '@/types/mcp';
import { fetchJson, fetchVoid, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/mcp/servers';

export class McpService {
  async listServers(): Promise<McpServerDto[]> {
    return fetchJson<McpServerDto[]>(API_BASE);
  }

  async createServer(request: McpServerCreateRequest): Promise<McpServerDto> {
    return fetchJson<McpServerDto>(API_BASE, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async bulkImport(request: McpBulkImportRequest): Promise<McpServerDto[]> {
    return fetchJson<McpServerDto[]>(`${API_BASE}/import`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async updateServer(name: string, request: McpServerCreateRequest): Promise<McpServerDto> {
    return fetchJson<McpServerDto>(`${API_BASE}/${encodeURIComponent(name)}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async deleteServer(name: string): Promise<void> {
    return fetchVoid(`${API_BASE}/${encodeURIComponent(name)}`, { method: 'DELETE' });
  }

  async connectServer(name: string): Promise<McpServerDto> {
    return fetchJson<McpServerDto>(`${API_BASE}/${encodeURIComponent(name)}/connect`, { method: 'POST' });
  }

  async disconnectServer(name: string): Promise<McpServerDto> {
    return fetchJson<McpServerDto>(`${API_BASE}/${encodeURIComponent(name)}/disconnect`, { method: 'POST' });
  }

  async getServerTools(name: string): Promise<McpToolInfo[]> {
    return fetchJson<McpToolInfo[]>(`${API_BASE}/${encodeURIComponent(name)}/tools`);
  }

  async getServerPrompts(name: string): Promise<McpPromptInfo[]> {
    return fetchJson<McpPromptInfo[]>(`${API_BASE}/${encodeURIComponent(name)}/prompts`);
  }
}

export const mcpService = new McpService();
