import { create } from 'zustand';
import { memoryService } from '@/services/memory-service';
import type { MemoryEntry, MemoryConfig, CreateMemoryRequest } from '@/types/memory';

interface MemoryStore {
  // State
  memories: MemoryEntry[];
  config: MemoryConfig | null;
  loading: boolean;
  error: string | null;

  // Actions
  loadMemories: (scope?: 'global' | 'project') => Promise<void>;
  loadConfig: () => Promise<void>;
  createOrUpdate: (request: CreateMemoryRequest) => Promise<MemoryEntry>;
  deleteMemory: (name: string, scope: 'global' | 'project') => Promise<void>;
  deleteAll: (scope: 'global' | 'project') => Promise<number>;
  updateConfig: (config: { enabled?: boolean }) => Promise<void>;
  clearError: () => void;
}

export const useMemoryStore = create<MemoryStore>((set, get) => ({
  memories: [],
  config: null,
  loading: false,
  error: null,

  loadMemories: async (scope) => {
    set({ loading: true, error: null });
    try {
      // Load both global and project memories, then merge
      if (scope) {
        const memories = await memoryService.listMemories(scope);
        set({ memories, loading: false });
      } else {
        const [globalMems, projectMems] = await Promise.all([
          memoryService.listMemories('global'),
          memoryService.listMemories('project'),
        ]);
        set({ memories: [...globalMems, ...projectMems], loading: false });
      }
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
    const entry = await memoryService.createOrUpdateMemory(request);
    // Reload to get fresh list
    await get().loadMemories();
    return entry;
  },

  deleteMemory: async (name, scope) => {
    try {
      await memoryService.deleteMemory(name, scope);
      set((state) => ({
        memories: state.memories.filter(
          (m) => !(m.name === name && m.scope === scope)
        ),
      }));
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },

  deleteAll: async (scope) => {
    try {
      const result = await memoryService.deleteAllMemories(scope);
      await get().loadMemories();
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
