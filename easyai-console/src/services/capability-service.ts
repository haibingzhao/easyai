import { fetchJson } from '@/services/api-client';

/**
 * The asset kinds the probe reports. `model`…`knowledge` are group-shareable A-class assets gated by
 * group ownership; `rag` / `integrations` / `database` are platform-level configs gated by whether a
 * deployment pinned them from Spring properties (`canManage` false when pinned read-only).
 */
export type AssetKind =
  | 'model' | 'aux' | 'mcp' | 'agent' | 'skill' | 'storage' | 'knowledge'
  | 'rag' | 'integrations' | 'database';

/** Whether the current login may read and/or manage one asset kind's group bucket. */
export interface AssetCapability {
  canRead: boolean;
  canManage: boolean;
  /** The shared bucket id assets are written under, or null when group-less / deployment-managed. */
  ownerId: string | null;
}

/** The capability snapshot the console caches for the session (GET /api/capabilities). */
export interface Capabilities {
  groupId: string | null;
  groupUserId: string | null;
  isGroupOwner: boolean;
  /** `static` when a deployment-wide storage layer is pinned, otherwise `database`. */
  setupMode: string;
  assets: Record<string, AssetCapability>;
}

export const capabilityService = {
  async get(): Promise<Capabilities> {
    return fetchJson<Capabilities>('/api/capabilities');
  },
};
