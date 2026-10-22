import type { McpServerDto, McpServerCreateRequest, McpBulkImportRequest, McpToolInfo, McpPromptInfo } from '@/types/mcp';
import { fetchJson, fetchVoid, scopeQuery, JSON_HEADERS } from '@/services/api-client';
import type { AssetScope } from '@/services/api-client';

const API_BASE = '/api/mcp/servers';

export class McpService {
  async listServers(): Promise<McpServerDto[]> {
    return fetchJson<McpServerDto[]>(API_BASE);
  }

  async createServer(request: McpServerCreateRequest, scope?: AssetScope): Promise<McpServerDto> {
    return fetchJson<McpServerDto>(`${API_BASE}${scopeQuery(scope)}`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async bulkImport(request: McpBulkImportRequest, scope?: AssetScope): Promise<McpServerDto[]> {
    return fetchJson<McpServerDto[]>(`${API_BASE}/import${scopeQuery(scope)}`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async updateServer(name: string, request: McpServerCreateRequest, scope?: AssetScope): Promise<McpServerDto> {
    return fetchJson<McpServerDto>(`${API_BASE}/${encodeURIComponent(name)}${scopeQuery(scope)}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async deleteServer(name: string, scope?: AssetScope): Promise<void> {
    return fetchVoid(`${API_BASE}/${encodeURIComponent(name)}${scopeQuery(scope)}`, { method: 'DELETE' });
  }

  async connectServer(name: string, scope?: AssetScope): Promise<McpServerDto> {
    return fetchJson<McpServerDto>(`${API_BASE}/${encodeURIComponent(name)}/connect${scopeQuery(scope)}`, { method: 'POST' });
  }

  async disconnectServer(name: string, scope?: AssetScope): Promise<McpServerDto> {
    return fetchJson<McpServerDto>(`${API_BASE}/${encodeURIComponent(name)}/disconnect${scopeQuery(scope)}`, { method: 'POST' });
  }

  async getServerTools(name: string, scope?: AssetScope): Promise<McpToolInfo[]> {
    return fetchJson<McpToolInfo[]>(`${API_BASE}/${encodeURIComponent(name)}/tools${scopeQuery(scope)}`);
  }

  async getServerPrompts(name: string, scope?: AssetScope): Promise<McpPromptInfo[]> {
    return fetchJson<McpPromptInfo[]>(`${API_BASE}/${encodeURIComponent(name)}/prompts${scopeQuery(scope)}`);
  }
}

export const mcpService = new McpService();

/**
 * The bucket a listed row targets. A server name repeats across the personal and group buckets, so
 * every row-addressed call must carry this explicitly instead of letting the backend guess.
 * Missing `ownerId` means an older backend that cannot address buckets per row; those rows are
 * treated as personal so personal management keeps working, while the management page hides the
 * group target and keeps same-named rows read-only instead of silently guessing.
 */
export function mcpServerScope(
  server: Pick<McpServerDto, 'ownerId'>,
  currentUserId: string | undefined,
): AssetScope {
  if (currentUserId == null || !server.ownerId || server.ownerId === currentUserId) return 'personal';
  return 'group';
}

/**
 * Stable row identity: `name` alone is not unique across the personal and group buckets.
 * Legacy responses without `ownerId` share a `legacy` prefix, so callers that need per-row identity
 * must add their own discriminator.
 */
export function mcpServerKey(server: Pick<McpServerDto, 'ownerId' | 'name'>): string {
  return `${server.ownerId || 'legacy'}:${server.name}`;
}

export interface McpServerEditDraft {
  url: string;
  headers: Record<string, string>;
  command: string[];
  env: Record<string, string>;
  timeoutSeconds: number;
}

function normalizeCommand(command: string[] | null | undefined): string[] {
  return (command ?? []).map(line => line.trim()).filter(line => line.length > 0);
}

function normalizeMap(map: Record<string, string> | null | undefined): Record<string, string> {
  const normalized: Record<string, string> = {};
  for (const [key, value] of Object.entries(map ?? {})) {
    const trimmed = key.trim();
    if (trimmed) normalized[trimmed] = value;
  }
  return normalized;
}

function sameMap(a: Record<string, string>, b: Record<string, string>): boolean {
  const aKeys = Object.keys(a);
  if (aKeys.length !== Object.keys(b).length) return false;
  return aKeys.every((key) => Object.prototype.hasOwnProperty.call(b, key) && a[key] === b[key]);
}

function sameArray(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

/**
 * Fields the Edit dialog must PUT: only those whose normalized draft value differs from the row
 * snapshot the dialog opened with. Untouched fields stay out so `{...latest, ...changes}` keeps a
 * value another client refreshed while the dialog was open.
 */
export function changedMcpServerFields(
  server: McpServerDto,
  draft: McpServerEditDraft,
): Partial<McpServerDto> {
  const changes: Partial<McpServerDto> = {};
  if (server.type === 'remote') {
    const url = draft.url.trim();
    if (url !== (server.url ?? '').trim()) changes.url = url;
    const headers = normalizeMap(draft.headers);
    if (!sameMap(headers, normalizeMap(server.headers))) changes.headers = headers;
  } else {
    const command = normalizeCommand(draft.command);
    if (!sameArray(command, normalizeCommand(server.command))) changes.command = command;
    const env = normalizeMap(draft.env);
    if (!sameMap(env, normalizeMap(server.env))) changes.env = env;
  }
  if (draft.timeoutSeconds !== (server.timeoutSeconds ?? 120)) {
    changes.timeoutSeconds = draft.timeoutSeconds;
  }
  return changes;
}

/**
 * One row per server name, ranked the way the runtime resolves MCP tools: `{server}__{tool}` carries
 * no owner, and the runtime shadows by name among *connected* rows only. A connected row therefore
 * beats a same-named failed/disconnected one even when that one belongs to the caller; owner
 * priority only breaks a tie. Without this, the picker could show an empty personal row while the
 * model actually calls the connected group server.
 */
export function dedupeMcpServers(
  servers: McpServerDto[],
  currentUserId: string | undefined,
): McpServerDto[] {
  const rank = (server: McpServerDto): [number, number] => [
    server.status === 'connected' ? 0 : 1,
    currentUserId != null && server.ownerId === currentUserId ? 0 : 1,
  ];
  const better = (a: McpServerDto, b: McpServerDto): boolean => {
    const [aConnected, aOwner] = rank(a);
    const [bConnected, bOwner] = rank(b);
    return aConnected < bConnected || (aConnected === bConnected && aOwner < bOwner);
  };
  // Keep the backend's list order (first appearance of each name) and only swap the row behind a name.
  const bestByName = new Map<string, McpServerDto>();
  for (const server of servers) {
    const current = bestByName.get(server.name);
    if (!current || better(server, current)) bestByName.set(server.name, server);
  }
  return [...bestByName.values()];
}
