import { create } from 'zustand';
import type { SkillInfo } from '@/types/agent';
import {
  SkillService,
  type DirectoryInstallRequest,
  type SkillInstallResult,
  type UploadInstallRequest,
} from '@/services/skill-service';

const INDEX_POLL_INTERVAL_MS = 3_000;
const INDEX_POLL_MAX_MS = 5 * 60_000;

/** A row the retrieval index has not caught up with, or whose directory still awaits restore. */
function isPending(skill: SkillInfo): boolean {
  // A disabled row is deliberately delisted, so it never becomes indexed again until it is enabled.
  return skill.enabled && (!skill.indexed || !skill.installedOnDisk);
}

interface SkillStore {
  skills: SkillInfo[];
  loading: boolean;
  /** Name of the row whose toggle or delete is in flight. */
  busyName: string | null;
  error: string | null;
  /** Non-fatal server remark about the last install, e.g. it shadows a shared skill. */
  notice: string | null;
  indexing: boolean;
  load: () => Promise<void>;
  clearError: () => void;
  clearNotice: () => void;
  installFromDirectory: (request: DirectoryInstallRequest) => Promise<SkillInstallResult>;
  installFromUpload: (request: UploadInstallRequest) => Promise<SkillInstallResult>;
  setEnabled: (name: string, enabled: boolean) => Promise<void>;
  remove: (name: string) => Promise<void>;
  stopIndexPolling: () => void;
}

let pollTimer: ReturnType<typeof setInterval> | null = null;
let pollDeadline = 0;

function stopPolling(): void {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

export const useSkillStore = create<SkillStore>((set, get) => {
  /**
   * Indexing is asynchronous on the server, so a fresh install keeps polling until every row reports
   * it is indexed. Transient failures are swallowed — the next tick or a manual reload retries.
   */
  const pollUntilSettled = () => {
    if (!get().skills.some(isPending)) {
      stopPolling();
      set({ indexing: false });
      return;
    }
    pollDeadline = Date.now() + INDEX_POLL_MAX_MS;
    if (pollTimer) return;
    set({ indexing: true });
    pollTimer = setInterval(async () => {
      if (Date.now() > pollDeadline) {
        stopPolling();
        set({ indexing: false });
        return;
      }
      try {
        const skills = await SkillService.list();
        set({ skills });
        if (!skills.some(isPending)) {
          stopPolling();
          set({ indexing: false });
        }
      } catch {
        // Keep polling: a dropped refresh is not a reason to stop tracking progress.
      }
    }, INDEX_POLL_INTERVAL_MS);
  };

  return {
    skills: [],
    loading: false,
    busyName: null,
    error: null,
    notice: null,
    indexing: false,

    load: async () => {
      set({ loading: true, error: null });
      try {
        const skills = await SkillService.list();
        set({ skills, loading: false });
        pollUntilSettled();
      } catch (err) {
        set({ error: (err as Error).message, loading: false });
      }
    },

    clearError: () => set({ error: null }),

    clearNotice: () => set({ notice: null }),

    installFromDirectory: async (request) => {
      const result = await SkillService.installFromDirectory(request);
      if (result.skill) {
        set({ notice: result.warning });
        await get().load();
      }
      return result;
    },

    installFromUpload: async (request) => {
      const result = await SkillService.installFromUpload(request);
      if (result.skill) {
        set({ notice: result.warning });
        await get().load();
      }
      return result;
    },

    setEnabled: async (name, enabled) => {
      set({ busyName: name, error: null });
      try {
        await SkillService.setEnabled(name, enabled);
        set({ skills: await SkillService.list(), busyName: null });
        pollUntilSettled();
      } catch (err) {
        set({ error: (err as Error).message, busyName: null });
      }
    },

    remove: async (name) => {
      set({ busyName: name, error: null });
      try {
        await SkillService.remove(name);
        set({ skills: await SkillService.list(), busyName: null });
        pollUntilSettled();
      } catch (err) {
        set({ error: (err as Error).message, busyName: null });
      }
    },

    stopIndexPolling: () => {
      stopPolling();
      set({ indexing: false });
    },
  };
});
