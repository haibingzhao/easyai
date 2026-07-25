import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type RightPanelTab = 'summary' | 'review' | 'files' | 'sessions' | 'team';

interface NavState {
  sidebarCollapsed: boolean;
  mobileSidebarOpen: boolean;
  rightPanelOpen: boolean;
  /** Currently selected file path in the right panel (not persisted) */
  selectedFile: string | null;
  /** Sidebar width in pixels (persisted) */
  sidebarWidth: number;
  /** Right panel width in pixels (persisted) */
  rightPanelWidth: number;
  /** Active tab in the right panel (persisted) */
  rightPanelTab: RightPanelTab;
  toggleSidebar: () => void;
  setMobileSidebarOpen: (open: boolean) => void;
  toggleRightPanel: () => void;
  setRightPanelOpen: (open: boolean) => void;
  setSelectedFile: (path: string | null) => void;
  setSidebarWidth: (w: number) => void;
  setRightPanelWidth: (w: number) => void;
  setRightPanelTab: (tab: RightPanelTab) => void;
  /** Open a file in the right panel: sets selectedFile + opens the panel + switches to Files tab */
  openFile: (path: string) => void;
  /** File path to auto-expand in Review tab (not persisted, transient signal) */
  reviewFilePath: string | null;
  /** Open the Review tab and optionally select a file for diff view */
  openReviewTab: (path?: string) => void;
  /** Open the Sessions tab */
  openSessionsTab: () => void;
  /** Open the Team tab (Team Member Panel) */
  openTeamTab: () => void;
}

const DEFAULT_SIDEBAR_WIDTH = 220;
const DEFAULT_RIGHT_PANEL_WIDTH = 400;

export const useNavStore = create<NavState>()(persist(
    (set) => ({
      sidebarCollapsed: false,
      mobileSidebarOpen: false,
      rightPanelOpen: false,
      reviewFilePath: null,
      selectedFile: null,
      sidebarWidth: DEFAULT_SIDEBAR_WIDTH,
      rightPanelWidth: DEFAULT_RIGHT_PANEL_WIDTH,
      rightPanelTab: 'files' as RightPanelTab,
      toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      setMobileSidebarOpen: (open) => set({ mobileSidebarOpen: open }),
      toggleRightPanel: () => set((s) => ({ rightPanelOpen: !s.rightPanelOpen })),
      setRightPanelOpen: (open) => set({ rightPanelOpen: open }),
      setSelectedFile: (path) => set({ selectedFile: path }),
      setSidebarWidth: (w) => set({ sidebarWidth: w }),
      setRightPanelWidth: (w) => set({ rightPanelWidth: w }),
      setRightPanelTab: (tab) => set({ rightPanelTab: tab }),
      openFile: (path) => set({ selectedFile: path, rightPanelOpen: true, rightPanelTab: 'files' as RightPanelTab }),
      openReviewTab: (path) => set({
        rightPanelOpen: true,
        rightPanelTab: 'review' as RightPanelTab,
        reviewFilePath: path ?? null,
      }),
      openSessionsTab: () => set({
        rightPanelOpen: true,
        rightPanelTab: 'sessions' as RightPanelTab,
      }),
      openTeamTab: () => set({
        rightPanelOpen: true,
        rightPanelTab: 'team' as RightPanelTab,
      }),
    }),
    {
      name: 'easyai-nav',
      partialize: (s) => ({
        sidebarCollapsed: s.sidebarCollapsed,
        rightPanelOpen: s.rightPanelOpen,
        sidebarWidth: s.sidebarWidth,
        rightPanelWidth: s.rightPanelWidth,
        rightPanelTab: s.rightPanelTab,
      }),
    }
  )
);
