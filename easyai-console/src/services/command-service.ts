import type { SlashCommand, UserCommand, UserCommandCreateRequest } from '@/types/command';
import { authFetch, fetchJson, fetchVoid, JSON_HEADERS } from '@/services/api-client';
import { useProjectStore } from '@/services/stores/project-store';

export class CommandService {
  static async fetchCommands(agentId?: string | null, projectId: string | null | undefined = useProjectStore.getState().currentProject?.id, signal?: AbortSignal): Promise<SlashCommand[]> {
    const params = new URLSearchParams();
    if (projectId) params.set('projectId', projectId);
    if (agentId) params.set('agentId', agentId);
    return fetchJson<SlashCommand[]>(`/api/commands?${params}`, { signal });
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
