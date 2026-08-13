import { create } from 'zustand';
import { memoryService } from '@/services/memory-service';
import { useProjectStore } from '@/services/stores/project-store';
import type { MemoryEntry, MemoryConfig, CreateMemoryRequest, UpdateMemoryRequest } from '@/types/memory';

interface MemoryStore {
  // State
  entries: MemoryEntry[];
  config: MemoryConfig | null;
  loading: boolean;
  error: string | null;

  // Actions
  /** Load GLOBAL memories plus each project's memories (parallel, non-blocking per project). */
  loadAll: () => Promise<void>;
  loadConfig: () => Promise<void>;
  createOrUpdate: (request: CreateMemoryRequest) => Promise<MemoryEntry>;
  updateMemory: (
    name: string,
    scope: 'global' | 'project',
    projectPath: string | null,
    request: UpdateMemoryRequest
  ) => Promise<MemoryEntry>;
  deleteMemory: (name: string, scope: 'global' | 'project', projectPath: string | null) => Promise<void>;
  deleteAll: (scope: 'global' | 'project', projectPath: string | null) => Promise<number>;
  updateConfig: (config: { enabled?: boolean }) => Promise<void>;
  clearError: () => void;
}

export const useMemoryStore = create<MemoryStore>((set, get) => ({
  entries: [],
  config: null,
  loading: false,
  error: null,

  loadAll: async () => {
    set({ loading: true, error: null });
    const projects = useProjectStore.getState().projects;
    const globalRequest = memoryService.listMemories('global', null).then((list) =>
      list.map((m) => ({ ...m, projectPath: null }))
    );
    const projectRequests = projects.map(async (project) => {
      try {
        const list = await memoryService.listMemories('project', project.path);
        return list.map((m) => ({ ...m, projectPath: project.path }));
      } catch (e) {
        // One project failing must not block the whole view
        set({ error: (e as Error).message });
        return [] as MemoryEntry[];
      }
    });
    try {
      const [globalMems, ...projectLists] = await Promise.all([globalRequest, ...projectRequests]);
      set({ entries: [...globalMems, ...projectLists.flat()], loading: false });
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
    }
  },

  loadConfig: async () => {
    try {
      const config = await memoryService.getConfig();
      set({ config });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  createOrUpdate: async (request) => {
    try {
      const entry = await memoryService.createOrUpdateMemory(request);
      // Reload to get fresh list
      await get().loadAll();
      return entry;
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },

  updateMemory: async (name, scope, projectPath, request) => {
    try {
      const entry = await memoryService.updateMemory(name, scope, projectPath, request);
      await get().loadAll();
      return entry;
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },

  deleteMemory: async (name, scope, projectPath) => {
    try {
      await memoryService.deleteMemory(name, scope, projectPath);
      await get().loadAll();
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },

  deleteAll: async (scope, projectPath) => {
    try {
      const result = await memoryService.deleteAllMemories(scope, projectPath);
      await get().loadAll();
      return result.deleted;
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },

  updateConfig: async (configUpdate) => {
    try {
      const newConfig = await memoryService.updateConfig(configUpdate);
      set({ config: newConfig });
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },

  clearError: () => set({ error: null }),
}));
