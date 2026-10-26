import { create } from 'zustand';
import { readErrorMessage } from '@/services/api-client';
import { watchService } from '@/services/watch-service';
import type {
  Watch,
  WatchFindingTask,
  WatchStatus,
  WatchUpdatePayload,
} from '@/types/watch';

interface WatchState {
  watches: Watch[];
  loading: boolean;
  error: string | null;
  detail: Watch | null;
  detailLoading: boolean;
  findings: WatchFindingTask[];
  findingsLoading: boolean;

  load: (status?: WatchStatus | 'all', options?: { silent?: boolean }) => Promise<void>;
  loadDetail: (id: string, options?: { silent?: boolean }) => Promise<void>;
  loadFindings: (id: string, options?: { silent?: boolean }) => Promise<void>;
  updateWatch: (id: string, payload: WatchUpdatePayload) => Promise<Watch | null>;
  pause: (id: string) => Promise<Watch | null>;
  resume: (id: string) => Promise<Watch | null>;
  cancel: (id: string) => Promise<Watch | null>;
  runNow: (id: string) => Promise<string | null>;
  remove: (id: string) => Promise<boolean>;
  clearDetail: () => void;
  clearError: () => void;
}

/**
 * Watch list + detail state. Polling belongs to the pages (30s while visible, active watches only):
 * the store only knows how to fetch and mutate, so a host can drive it without inheriting a timer.
 */
export const useWatchStore = create<WatchState>((set, get) => ({
  watches: [],
  loading: false,
  error: null,
  detail: null,
  detailLoading: false,
  findings: [],
  findingsLoading: false,

  load: async (status = 'all', options) => {
    if (!options?.silent) set({ loading: true, error: null });
    try {
      const watches = await watchService.list(status);
      set({ watches, loading: false, error: null });
    } catch (error) {
      set({ loading: false, error: readErrorMessage(error) });
    }
  },

  loadDetail: async (id, options) => {
    if (!options?.silent) set({ detailLoading: true, error: null });
    try {
      const detail = await watchService.get(id);
      set({ detail, detailLoading: false, error: null });
      // A silent poll must not overwrite the list's copy with a stale one; keep both in sync.
      set({
        watches: get().watches.map((watch) => (watch.id === id ? detail : watch)),
      });
    } catch (error) {
      set({ detailLoading: false, error: readErrorMessage(error) });
    }
  },

  loadFindings: async (id, options) => {
    if (!options?.silent) set({ findingsLoading: true });
    try {
      const findings = await watchService.findings(id);
      set({ findings, findingsLoading: false, error: null });
    } catch (error) {
      set({ findingsLoading: false, error: readErrorMessage(error) });
    }
  },

  updateWatch: async (id, payload) => {
    try {
      const watch = await watchService.update(id, payload);
      set({
        detail: watch,
        watches: get().watches.map((candidate) => (candidate.id === id ? watch : candidate)),
        error: null,
      });
      return watch;
    } catch (error) {
      set({ error: readErrorMessage(error) });
      return null;
    }
  },

  pause: async (id) => {
    try {
      const watch = await watchService.pause(id);
      set({
        detail: get().detail?.id === id ? watch : get().detail,
        watches: get().watches.map((candidate) => (candidate.id === id ? watch : candidate)),
        error: null,
      });
      return watch;
    } catch (error) {
      set({ error: readErrorMessage(error) });
      return null;
    }
  },

  resume: async (id) => {
    try {
      const watch = await watchService.resume(id);
      set({
        detail: get().detail?.id === id ? watch : get().detail,
        watches: get().watches.map((candidate) => (candidate.id === id ? watch : candidate)),
        error: null,
      });
      return watch;
    } catch (error) {
      set({ error: readErrorMessage(error) });
      return null;
    }
  },

  cancel: async (id) => {
    try {
      const watch = await watchService.cancel(id);
      set({
        detail: get().detail?.id === id ? watch : get().detail,
        watches: get().watches.map((candidate) => (candidate.id === id ? watch : candidate)),
        error: null,
      });
      return watch;
    } catch (error) {
      set({ error: readErrorMessage(error) });
      return null;
    }
  },

  runNow: async (id) => {
    try {
      const task = await watchService.runNow(id);
      set({ error: null });
      return task.taskId;
    } catch (error) {
      set({ error: readErrorMessage(error) });
      return null;
    }
  },

  remove: async (id) => {
    try {
      await watchService.remove(id);
      set({
        watches: get().watches.filter((watch) => watch.id !== id),
        detail: get().detail?.id === id ? null : get().detail,
        error: null,
      });
      return true;
    } catch (error) {
      set({ error: readErrorMessage(error) });
      return false;
    }
  },

  clearDetail: () => set({ detail: null, findings: [], error: null }),
  clearError: () => set({ error: null }),
}));
