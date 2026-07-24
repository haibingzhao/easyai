import { create } from 'zustand';
import {
  swarmService,
  type PresetInfo,
  type RunSummary,
  type RunDetailResponse,
  type SwarmEvent,
} from '@/services/swarm-service';

const TERMINAL_EVENTS = new Set([
  'swarm_run_completed',
  'swarm_run_failed',
  'swarm_run_cancelled',
  'swarm_run_paused',
]);

interface SwarmState {
  presets: PresetInfo[];
  runs: RunSummary[];
  activeRunDetail: RunDetailResponse | null;
  loading: boolean;
  swarmEnabled: boolean;

  loadPresets: () => Promise<void>;
  deletePreset: (name: string) => Promise<void>;
  loadRuns: () => Promise<void>;
  launchRun: (presetName: string, variables?: Record<string, string>, modelConfigId?: string, dryRun?: boolean) => Promise<string | null>;
  cancelRun: (id: string) => Promise<void>;
  deleteRun: (id: string) => Promise<void>;
  pauseRun: (runId: string) => Promise<void>;
  resumeRun: (runId: string) => Promise<void>;
  loadRunDetail: (id: string) => Promise<void>;
  pollActiveRun: (runId: string) => Promise<void>;
  setActiveRunDetail: (detail: RunDetailResponse | null) => void;
}

let pollTimer: ReturnType<typeof setTimeout> | null = null;
let activeEventSource: EventSource | null = null;

const closeEventSource = () => {
  if (activeEventSource) {
    activeEventSource.close();
    activeEventSource = null;
  }
};

export const useSwarmStore = create<SwarmState>((set, get) => ({
  presets: [],
  runs: [],
  activeRunDetail: null,
  loading: false,
  swarmEnabled: true,

  loadPresets: async () => {
    set({ loading: true });
    const presets = await swarmService.listPresets();
    set({ presets, swarmEnabled: presets.length > 0, loading: false });
  },

  deletePreset: async (name) => {
    try {
      const ok = await swarmService.deletePreset(name);
      if (ok) {
        set(state => ({
          presets: state.presets.filter(p => p.name !== name)
        }));
      }
    } catch (e) {
      console.error('Failed to delete preset:', e);
    }
  },

  loadRuns: async () => {
    const runs = await swarmService.listRuns();
    set({ runs });
  },

  launchRun: async (presetName, variables, modelConfigId, dryRun) => {
    const result = await swarmService.launchRun(presetName, variables, modelConfigId, dryRun);
    if (!result) return null;
    // Dry runs never appear in run history — skip refreshing the list
    if (!dryRun) {
      await get().loadRuns();
    }
    get().pollActiveRun(result.runId);
    return result.runId;
  },

  cancelRun: async (id) => {
    closeEventSource();
    if (pollTimer) {
      clearTimeout(pollTimer);
      pollTimer = null;
    }
    await swarmService.cancelRun(id);
    await get().loadRuns();
    await get().loadRunDetail(id);
  },

  deleteRun: async (id) => {
    // Cancel polling if deleting the active run
    if (get().activeRunDetail?.id === id) {
      closeEventSource();
      if (pollTimer) {
        clearTimeout(pollTimer);
        pollTimer = null;
      }
    }
    const ok = await swarmService.deleteRun(id);
    if (ok) {
      set(state => ({
        runs: state.runs.filter(r => r.id !== id),
        activeRunDetail: state.activeRunDetail?.id === id ? null : state.activeRunDetail,
      }));
    }
  },

  pauseRun: async (runId) => {
    await swarmService.pauseRun(runId);
    set(state => ({
      runs: state.runs.map(r => r.id === runId ? { ...r, status: 'PAUSED' as const } : r)
    }));
  },

  resumeRun: async (runId) => {
    await swarmService.resumeRun(runId);
    set(state => ({
      runs: state.runs.map(r => r.id === runId ? { ...r, status: 'RESUMING' as const } : r)
    }));
  },

  loadRunDetail: async (id) => {
    const detail = await swarmService.getRun(id);
    set({ activeRunDetail: detail });
  },

  pollActiveRun: async (runId) => {
    if (pollTimer) {
      clearTimeout(pollTimer);
      pollTimer = null;
    }
    closeEventSource();

    const startPolling = () => {
      const poll = async () => {
        await get().loadRunDetail(runId);
        await get().loadRuns();
        const detail = get().activeRunDetail;
        if (detail && (detail.status === 'RUNNING' || detail.status === 'PENDING' || detail.status === 'PAUSED' || detail.status === 'RESUMING')) {
          pollTimer = setTimeout(poll, 3000);
        } else {
          pollTimer = null;
        }
      };
      poll();
    };

    const handleEvent = (_event: SwarmEvent) => {
      if (TERMINAL_EVENTS.has(_event.type)) {
        closeEventSource();
        get().loadRunDetail(runId);
        get().loadRuns();
      } else {
        get().loadRunDetail(runId);
        get().loadRuns();
      }
    };

    const handleError = () => {
      closeEventSource();
      startPolling();
    };

    try {
      activeEventSource = swarmService.subscribeToRunEvents(runId, handleEvent, handleError);
    } catch {
      startPolling();
    }
  },

  setActiveRunDetail: (detail) => set({ activeRunDetail: detail }),
}));
