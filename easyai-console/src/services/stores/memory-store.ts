import { create } from 'zustand';
import { memoryService } from '@/services/memory-service';
import { useProjectStore } from '@/services/stores/project-store';
import type {
  MemoryEntry,
  MemoryConfig,
  CreateMemoryRequest,
  UpdateMemoryRequest,
  MemoryConsolidationSettings,
  UpdateMemoryConsolidationSettingsRequest,
  ConsolidationRequest,
  ConsolidationResponse,
} from '@/types/memory';

interface MemoryStore {
  // State
  entries: MemoryEntry[];
  config: MemoryConfig | null;
  loading: boolean;
  error: string | null;
  /** Per-user consolidation preferences; null until loaded or when the backend lacks them. */
  consolidation: MemoryConsolidationSettings | null;
  /** Archived entries fetched through refs, cached per `(scope, projectPath, docId)`. */
  archivedById: Record<string, MemoryEntry>;

  // Actions
  /** Load GLOBAL memories plus each project's memories (parallel, non-blocking per project). */
  loadAll: () => Promise<void>;
  loadConfig: () => Promise<void>;
  loadConsolidation: () => Promise<void>;
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
  /** Persist a partial consolidation-settings update and refresh the cached settings. */
  saveConsolidation: (
    request: UpdateMemoryConsolidationSettingsRequest
  ) => Promise<MemoryConsolidationSettings>;
  /** Run consolidation (dry run returns a report, a real run returns a task id). */
  consolidate: (request: ConsolidationRequest) => Promise<ConsolidationResponse>;
  /** Fetch archived entries by docId, reusing the per-store cache. */
  loadArchived: (
    ids: string[],
    scope: 'global' | 'project',
    projectPath: string | null
  ) => Promise<MemoryEntry[]>;
  clearError: () => void;
}

export const useMemoryStore = create<MemoryStore>((set, get) => ({
  entries: [],
  config: null,
  loading: false,
  error: null,
  consolidation: null,
  archivedById: {},

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

  loadConsolidation: async () => {
    try {
      const consolidation = await memoryService.getConsolidationSettings();
      set({ consolidation });
    } catch {
      // Settings require the database; without it the consolidation surface stays hidden.
      set({ consolidation: null });
    }
  },

  saveConsolidation: async (request) => {
    try {
      const consolidation = await memoryService.updateConsolidationSettings(request);
      set({ consolidation });
      return consolidation;
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },

  consolidate: async (request) => {
    try {
      return await memoryService.consolidate(request);
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },

  loadArchived: async (ids, scope, projectPath) => {
    const unique = Array.from(new Set(ids.filter(Boolean)));
    // An EasyRAG docId is derived from the entry key alone (`{type}/{name}.md`), so the same name in
    // the global slice and in a project slice — or in two projects — is the same id. Keying the cache
    // by id only would serve one slice's body when drilling down into another's.
    const prefix = `${scope}|${projectPath ?? ''}|`;
    const cache = get().archivedById;
    const missing = unique.filter((id) => cache[prefix + id] == null);
    if (missing.length > 0) {
      try {
        const fetched = await memoryService.listArchived(missing, scope, projectPath);
        set((state) => ({
          archivedById: {
            ...state.archivedById,
            ...Object.fromEntries(
              fetched
                .filter((e) => e.id)
                .map((e) => [prefix + (e.id as string), e] as const)
            ),
          },
        }));
      } catch (e) {
        set({ error: (e as Error).message });
        throw e;
      }
    }
    const resolved = get().archivedById;
    return unique
      .map((id) => resolved[prefix + id])
      .filter((e): e is MemoryEntry => e != null);
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
