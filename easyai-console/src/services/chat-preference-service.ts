import type { ChatPreference, SaveChatPreferenceRequest } from '@/types/agent';
import { fetchJson, JSON_HEADERS, scopeQuery } from '@/services/api-client';
import type { AssetScope } from '@/services/api-client';

const API_BASE = '/api/chat-preferences';

export const chatPreferenceService = {
  /**
   * Without a scope: the effective Chat preference for this login (personal → group → built-in).
   * With a scope: that bucket's own row, so an editor can show and save the exact layer it targets.
   */
  async get(scope?: AssetScope): Promise<ChatPreference> {
    // Deliberately not `scopeQuery()`: an unscoped read means *effective*, while `scopeQuery()` falls
    // back to the host's default write scope, which would silently downgrade it to one bucket's row.
    return fetchJson<ChatPreference>(`${API_BASE}${scope ? `?scope=${scope}` : ''}`);
  },

  /**
   * Replace the preference. `scope=group` stores it in the shared bucket (group owner only);
   * an empty payload deletes the row and restores built-in defaults.
   */
  async save(request: SaveChatPreferenceRequest, scope?: AssetScope): Promise<ChatPreference> {
    return fetchJson<ChatPreference>(`${API_BASE}${scopeQuery(scope)}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  },
};
