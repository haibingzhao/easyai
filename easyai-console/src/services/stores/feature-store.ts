import { create } from 'zustand';

/**
 * Backend-advertised UI capabilities, read from `GET /api/setup/status` before auth.
 * Kept out of the persisted settings store: these describe the deployment, not the user.
 */
interface FeatureState {
  /** Whether the console may offer project selection (default true for older backends). */
  projectSelectionEnabled: boolean;
  setProjectSelectionEnabled: (enabled: boolean) => void;
}

export const useFeatureStore = create<FeatureState>((set) => ({
  projectSelectionEnabled: true,
  setProjectSelectionEnabled: (enabled) => set({ projectSelectionEnabled: enabled }),
}));
