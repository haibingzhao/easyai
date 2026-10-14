import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface MobileUiState {
  /** Tasks sheet ("☰") — Goal / Todo / Swarm / References / Team. */
  tasksSheetOpen: boolean;
  /** Composer "+" menu, opened from the composer or a host item. */
  composerPlusOpen: boolean;
  /** Message the long-press action sheet targets (transient; the text is what got copied/quoted). */
  actionSheetTarget: { text: string; messageId?: string } | null;
  homeScreenHintDismissed: boolean;
  /**
   * Count badges keyed by tab id. Lives here rather than on the registry entry because the tab bar
   * only re-renders on navigation: a `badge: () => number` would keep showing the count from the
   * last screen change. A host that polls writes through `setTabBadge` and the bar updates live.
   */
  tabBadges: Record<string, number>;
  openTasksSheet: () => void;
  closeTasksSheet: () => void;
  setComposerPlusOpen: (open: boolean) => void;
  setActionSheetTarget: (target: { text: string; messageId?: string } | null) => void;
  setTabBadge: (tabId: string, count: number) => void;
  dismissHomeScreenHint: () => void;
  /** Called on logout so the next user does not inherit an open sheet. */
  resetMobileUi: () => void;
}

/**
 * Mobile panel state.
 *
 * Deliberately a **separate store with a separate persist key** from the desktop
 * `nav-store` (`easyai-nav`): the desktop keys are panel widths and which side panel was
 * last open, and a phone has neither. Sharing the key would let a phone visit rewrite a
 * desktop host's layout state, since both run against the same origin's localStorage.
 */
export const useMobileUiStore = create<MobileUiState>()(
  persist(
    (set) => ({
      tasksSheetOpen: false,
      composerPlusOpen: false,
      actionSheetTarget: null,
      homeScreenHintDismissed: false,
      tabBadges: {},
      openTasksSheet: () => set({ tasksSheetOpen: true }),
      closeTasksSheet: () => set({ tasksSheetOpen: false }),
      setComposerPlusOpen: (open) => set({ composerPlusOpen: open }),
      setActionSheetTarget: (target) => set({ actionSheetTarget: target }),
      setTabBadge: (tabId, count) =>
        set((state) =>
          // The tab bar subscribes to this object, so an unchanged count must not produce a new one
          // — a host polling every minute would otherwise re-render the bar for nothing.
          state.tabBadges[tabId] === count
            ? state
            : { tabBadges: { ...state.tabBadges, [tabId]: count } },
        ),
      dismissHomeScreenHint: () => set({ homeScreenHintDismissed: true }),
      resetMobileUi: () =>
        set({
          tasksSheetOpen: false,
          composerPlusOpen: false,
          actionSheetTarget: null,
          tabBadges: {},
        }),
    }),
    {
      name: 'easyai-mobile',
      // Sheet/target state is per-visit noise; only the dismissed-hint flag earns persistence.
      partialize: (s) => ({ homeScreenHintDismissed: s.homeScreenHintDismissed }),
    }
  )
);
