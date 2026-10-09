import { create } from 'zustand';
import { capabilityService } from '@/services/capability-service';
import type { AssetKind, Capabilities } from '@/services/capability-service';

/**
 * Snapshot of `GET /api/capabilities`: which asset kinds the current login may manage in its group
 * bucket. Warmed on every auth transition by auth-store (login / register / `checkAuth` restore) and
 * dropped in `resetUserScopedState`, so it never spans a user switch.
 *
 * The console's own screens do not read it yet — group-managed assets are a host-app concern
 * (easy-home), which consumes [selectCanManage] and [capabilityService] through `lib.ts`. The probe
 * therefore has to stay wired here: a host has no auth transition of its own to hang it on.
 */
interface CapabilityState {
  capabilities: Capabilities | null;
  loaded: boolean;
  /** Fetch and cache the snapshot; called once after login / auth restore. */
  load: () => Promise<void>;
  /** Drop the snapshot on logout so it never leaks across users. */
  clear: () => void;
}

export const useCapabilityStore = create<CapabilityState>((set) => ({
  capabilities: null,
  loaded: false,

  load: async () => {
    try {
      const capabilities = await capabilityService.get();
      set({ capabilities, loaded: true });
    } catch {
      // A failed probe must not block the session; selectors fall back to "manageable"
      // and the backend write gate still enforces the real permission.
      set({ capabilities: null, loaded: true });
    }
  },

  clear: () => set({ capabilities: null, loaded: false }),
}));

/**
 * Reactive selector: may the current login manage this asset kind's group bucket? Defaults to true
 * when the snapshot is absent (group-less deployment, or the probe has not resolved yet) — the
 * backend re-checks `isGroupOwner` on every write, so an optimistic UI can never widen access.
 */
export const selectCanManage = (kind: AssetKind) => (state: CapabilityState): boolean =>
  state.capabilities?.assets[kind]?.canManage ?? true;
