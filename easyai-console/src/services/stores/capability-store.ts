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

export const useCapabilityStore = create<CapabilityState>((set) => {
  /**
   * Monotonic request id. A load that started before a family switch must not overwrite the
   * snapshot the newer load writes when it resolves late; only the latest request may publish.
   */
  let loadSeq = 0;

  return {
    capabilities: null,
    loaded: false,

    load: async () => {
      const seq = ++loadSeq;
      try {
        const capabilities = await capabilityService.get();
        if (seq !== loadSeq) return;
        set({ capabilities, loaded: true });
      } catch {
        if (seq !== loadSeq) return;
        // A failed probe must not block the session; selectors fall back to "manageable"
        // and the backend write gate still enforces the real permission.
        set({ capabilities: null, loaded: true });
      }
    },

    clear: () => {
      // Invalidate any in-flight probe so it cannot restore the previous user's snapshot.
      loadSeq += 1;
      set({ capabilities: null, loaded: false });
    },
  };
});

/**
 * Reactive selector: may the current login manage this asset kind's group bucket? Defaults to true
 * when the snapshot is absent (group-less deployment, or the probe has not resolved yet) — the
 * backend re-checks `isGroupOwner` on every write, so an optimistic UI can never widen access.
 */
export const selectCanManage = (kind: AssetKind) => (state: CapabilityState): boolean =>
  state.capabilities?.assets[kind]?.canManage ?? true;

/**
 * Whether this login writes knowledge into a shared group bucket. In that mode `private` has no
 * meaning — every member can read/write the bucket — so the UI hides per-article visibility.
 */
export const selectKnowledgeShared = (state: CapabilityState): boolean =>
  state.capabilities?.assets.knowledge?.ownerId != null;
