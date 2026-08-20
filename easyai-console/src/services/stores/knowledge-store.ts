import { create } from 'zustand';
import { knowledgeService } from '@/services/knowledge-service';
import type { KnowledgeEntryDto, KnowledgeDetailDto, UploadResponseDto } from '@/services/knowledge-service';

interface KnowledgeStore {
  entries: KnowledgeEntryDto[];
  sources: string[];
  detail: KnowledgeDetailDto | null;
  loading: boolean;
  error: string | null;
  uploadResult: UploadResponseDto | null;

  loadEntries: (source?: string, category?: string, q?: string) => Promise<void>;
  loadSources: () => Promise<void>;
  loadDetail: (key: string) => Promise<void>;
  deleteEntry: (key: string) => Promise<void>;
  deleteSource: (source: string) => Promise<void>;
  upload: (files: File[], paths: string[], source: string, category?: string) => Promise<UploadResponseDto>;
  clearError: () => void;
  clearDetail: () => void;
  clearUploadResult: () => void;
}

export const useKnowledgeStore = create<KnowledgeStore>((set, get) => ({
  entries: [],
  sources: [],
  detail: null,
  loading: false,
  error: null,
  uploadResult: null,

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
      return result;
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
      throw e;
    }
  },

  clearError: () => set({ error: null }),
  clearDetail: () => set({ detail: null }),
  clearUploadResult: () => set({ uploadResult: null }),
}));
