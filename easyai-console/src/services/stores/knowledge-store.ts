import { create } from 'zustand';
import { knowledgeService } from '@/services/knowledge-service';
import type { KnowledgeEntryDto, KnowledgeDetailDto, UploadResponseDto } from '@/services/knowledge-service';

/** Polling interval (ms) for checking indexing progress after upload. */
const INDEXING_POLL_INTERVAL_MS = 3_000;
/** Maximum polling duration (ms) before giving up. */
const INDEXING_POLL_MAX_MS = 5 * 60_000;

interface KnowledgeStore {
  entries: KnowledgeEntryDto[];
  sources: string[];
  detail: KnowledgeDetailDto | null;
  loading: boolean;
  error: string | null;
  uploadResult: UploadResponseDto | null;
  /** Keys of recently uploaded entries that are still being indexed by EasyRAG. */
  indexingKeys: Set<string>;
  /** Derived progress: { done, total } — only meaningful when indexingKeys is non-empty. */
  indexingProgress: { done: number; total: number } | null;

  loadEntries: (source?: string, category?: string, q?: string) => Promise<void>;
  loadSources: () => Promise<void>;
  loadDetail: (key: string) => Promise<void>;
  deleteEntry: (key: string) => Promise<void>;
  deleteSource: (source: string) => Promise<void>;
  upload: (files: File[], paths: string[], source: string, category?: string) => Promise<UploadResponseDto>;
  /** Start polling for indexing progress of the given keys. */
  startIndexingPoll: (keys: string[]) => void;
  /** Stop polling and clear indexing state. */
  stopIndexingPoll: () => void;
  clearError: () => void;
  clearDetail: () => void;
  clearUploadResult: () => void;
}

export const useKnowledgeStore = create<KnowledgeStore>((set, get) => {
  let indexingTimer: ReturnType<typeof setInterval> | null = null;
  let indexingDeadline = 0;

  const stopPolling = () => {
    if (indexingTimer) {
      clearInterval(indexingTimer);
      indexingTimer = null;
    }
  };

  return {
    entries: [],
    sources: [],
    detail: null,
    loading: false,
    error: null,
    uploadResult: null,
    indexingKeys: new Set(),
    indexingProgress: null,

  loadEntries: async (source, category, q) => {
    set({ loading: true, error: null });
    try {
      const entries = await knowledgeService.list(source, category, q);
      set({ entries, loading: false });
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
    }
  },

  loadSources: async () => {
    try {
      const sources = await knowledgeService.sources();
      set({ sources });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  loadDetail: async (key) => {
    set({ loading: true, error: null });
    try {
      const detail = await knowledgeService.detail(key);
      set({ detail, loading: false });
    } catch (e) {
      set({ error: (e as Error).message, loading: false, detail: null });
    }
  },

  deleteEntry: async (key) => {
    try {
      await knowledgeService.deleteEntry(key);
      set({ detail: null });
      await get().loadEntries();
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  deleteSource: async (source) => {
    try {
      await knowledgeService.deleteSource(source);
      set({ detail: null });
      await get().loadEntries();
      await get().loadSources();
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  upload: async (files, paths, source, category) => {
    set({ loading: true, error: null, uploadResult: null });
    try {
      const result = await knowledgeService.upload(files, paths, source, category);
      set({ uploadResult: result, loading: false });
      await get().loadEntries();
      await get().loadSources();
      // Start tracking indexing progress for successfully uploaded files
      const uploadedKeys = result.results.filter((r) => r.success && r.key).map((r) => r.key!);
      if (uploadedKeys.length > 0) {
        get().startIndexingPoll(uploadedKeys);
      }
      return result;
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
      throw e;
    }
  },

  startIndexingPoll: (keys) => {
    stopPolling();
    indexingDeadline = Date.now() + INDEXING_POLL_MAX_MS;
    set({ indexingKeys: new Set(keys), indexingProgress: { done: 0, total: keys.length } });

    indexingTimer = setInterval(async () => {
      const { indexingKeys } = get();
      if (indexingKeys.size === 0 || Date.now() > indexingDeadline) {
        stopPolling();
        return;
      }
      try {
        // Refresh entries silently (no loading spinner) to pick up chunksCount changes
        const entries = await knowledgeService.list();
        const remaining = new Set<string>();
        let done = 0;
        for (const key of indexingKeys) {
          const entry = entries.find((e) => e.key === key);
          if (entry && entry.chunksCount != null) {
            done++;
          } else {
            remaining.add(key);
          }
        }
        set({ entries, indexingKeys: remaining, indexingProgress: { done, total: keys.length } });
        if (remaining.size === 0) {
          stopPolling();
        }
      } catch {
        // Transient failure — keep polling
      }
    }, INDEXING_POLL_INTERVAL_MS);
  },

  stopIndexingPoll: () => {
    stopPolling();
    set({ indexingKeys: new Set(), indexingProgress: null });
  },

  clearError: () => set({ error: null }),
  clearDetail: () => set({ detail: null }),
  clearUploadResult: () => set({ uploadResult: null }),
  };
});
