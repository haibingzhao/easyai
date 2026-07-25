import type { SlashCommand, UserCommand, UserCommandCreateRequest } from '@/types/command';
import { authFetch, fetchJson, fetchVoid, JSON_HEADERS } from '@/services/api-client';

export class CommandService {
  private static cache: SlashCommand[] | null = null;
  private static cacheAgentId: string | null | undefined = undefined;

  static async fetchCommands(agentId?: string | null): Promise<SlashCommand[]> {
    // Return cached result if agentId hasn't changed
    if (this.cache !== null && this.cacheAgentId === agentId) {
      return this.cache;
    }

    const params = agentId ? `?agentId=${encodeURIComponent(agentId)}` : '';
    const resp = await authFetch(`/api/commands${params}`);
    if (!resp.ok) return [];
    const commands: SlashCommand[] = await resp.json();
    this.cache = commands;
    this.cacheAgentId = agentId;
    return commands;
  }

  static invalidateCache(): void {
    this.cache = null;
    this.cacheAgentId = undefined;
  }

  // ─── User Command CRUD ─────────────────────────────────────────────

  private static readonly USER_COMMANDS_API = '/api/user-commands';

  static async listUserCommands(): Promise<UserCommand[]> {
    return fetchJson<UserCommand[]>(this.USER_COMMANDS_API);
  }

  static async getUserCommand(id: string): Promise<UserCommand> {
    return fetchJson<UserCommand>(`${this.USER_COMMANDS_API}/${encodeURIComponent(id)}`);
  }

  static async createUserCommand(request: UserCommandCreateRequest): Promise<UserCommand> {
    const resp = await authFetch(this.USER_COMMANDS_API, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
    if (!resp.ok) {
      const msg = resp.status === 409 ? 'Command name already exists' : `Failed to create command: ${resp.status}`;
      throw new Error(msg);
    }
    return resp.json();
  }

  static async updateUserCommand(id: string, request: UserCommandCreateRequest): Promise<UserCommand> {
    const resp = await authFetch(`${this.USER_COMMANDS_API}/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
    if (!resp.ok) {
      const msg = resp.status === 409 ? 'Command name already exists' : `Failed to update command: ${resp.status}`;
      throw new Error(msg);
    }
    return resp.json();
  }

  static async deleteUserCommand(id: string): Promise<void> {
    return fetchVoid(`${this.USER_COMMANDS_API}/${encodeURIComponent(id)}`, { method: 'DELETE' });
  }
}
