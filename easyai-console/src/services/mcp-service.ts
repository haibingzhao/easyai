import type { McpServerDto, McpServerCreateRequest, McpBulkImportRequest, McpToolInfo, McpPromptInfo } from '@/types/mcp';
import { authFetch } from '@/services/api-client';

const API_BASE = '/api/mcp/servers';

/** Safely parse JSON response, throwing a clear error if the response is not JSON (e.g. HTML fallback). */
async function parseJson<T>(response: Response): Promise<T> {
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('application/json')) {
    throw new Error('Backend is unreachable (received non-JSON response)');
  }
  return response.json() as Promise<T>;
}

/** Extract a meaningful error message from a failed response. */
async function extractError(response: Response, fallback: string): Promise<string> {
  try {
    const body = await response.text();
    return body || fallback;
  } catch {
    return fallback;
  }
}

export class McpService {
  async listServers(): Promise<McpServerDto[]> {
    const response = await authFetch(API_BASE);
    if (!response.ok) throw new Error(await extractError(response, 'Failed to list MCP servers'));
    return parseJson<McpServerDto[]>(response);
  }

  async createServer(request: McpServerCreateRequest): Promise<McpServerDto> {
    const response = await authFetch(API_BASE, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(await extractError(response, 'Failed to create MCP server'));
    return parseJson<McpServerDto>(response);
  }

  async bulkImport(request: McpBulkImportRequest): Promise<McpServerDto[]> {
    const response = await authFetch(`${API_BASE}/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(await extractError(response, 'Failed to import MCP servers'));
    return parseJson<McpServerDto[]>(response);
  }

  async updateServer(name: string, request: McpServerCreateRequest): Promise<McpServerDto> {
    const response = await authFetch(`${API_BASE}/${encodeURIComponent(name)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(await extractError(response, 'Failed to update MCP server'));
    return parseJson<McpServerDto>(response);
  }

  async deleteServer(name: string): Promise<void> {
    const response = await authFetch(`${API_BASE}/${encodeURIComponent(name)}`, {
      method: 'DELETE',
    });
    if (!response.ok) throw new Error(await extractError(response, 'Failed to delete MCP server'));
  }

  async connectServer(name: string): Promise<McpServerDto> {
    const response = await authFetch(`${API_BASE}/${encodeURIComponent(name)}/connect`, {
      method: 'POST',
    });
    if (!response.ok) throw new Error(await extractError(response, 'Failed to connect MCP server'));
    return parseJson<McpServerDto>(response);
  }

  async disconnectServer(name: string): Promise<McpServerDto> {
    const response = await authFetch(`${API_BASE}/${encodeURIComponent(name)}/disconnect`, {
      method: 'POST',
    });
    if (!response.ok) throw new Error(await extractError(response, 'Failed to disconnect MCP server'));
    return parseJson<McpServerDto>(response);
  }

  async getServerTools(name: string): Promise<McpToolInfo[]> {
    const response = await authFetch(`${API_BASE}/${encodeURIComponent(name)}/tools`);
    if (!response.ok) throw new Error(await extractError(response, 'Failed to get MCP server tools'));
    return parseJson<McpToolInfo[]>(response);
  }

  async getServerPrompts(name: string): Promise<McpPromptInfo[]> {
    const response = await authFetch(`${API_BASE}/${encodeURIComponent(name)}/prompts`);
    if (!response.ok) throw new Error(await extractError(response, 'Failed to get MCP server prompts'));
    return parseJson<McpPromptInfo[]>(response);
  }
}

export const mcpService = new McpService();
