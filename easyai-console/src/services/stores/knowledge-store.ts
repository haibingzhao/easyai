import { create } from 'zustand';
import { knowledgeService } from '@/services/knowledge-service';
import { readErrorMessage } from '@/services/api-client';
import type {
  UploadResponseDto, KnowledgeArticleDto, KnowledgeFolderDto,
  SaveArticleRequest, UpdateArticleRequest, PublishArticleRequest,
  SetArticleRagRequest, MoveArticleRequest, CreateFolderRequest,
  ArticleStatus, AiComposeRequest,
} from '@/services/knowledge-service';

/** Polling interval (ms) for checking indexing progress after upload/publish. */
const INDEXING_POLL_INTERVAL_MS = 3_000;
/** Maximum polling duration (ms) before giving up. */
const INDEXING_POLL_MAX_MS = 5 * 60_000;
/**
 * Ids per /index-status request. The endpoint accepts 200, but ids are 36-char UUIDs and this is a
 * GET: 200 of them push the request line past the server's 4KB default and the whole batch fails.
 */
const INDEX_STATUS_BATCH = 50;

interface KnowledgeStore {
  /** DB-backed article list (from /articles). */
  articles: KnowledgeArticleDto[];
  /** Folder tree rows (from /folders). */
  folders: KnowledgeFolderDto[];
  articleLoading: boolean;
  folderLoading: boolean;
  /** Content fetch state for a single article; separate so the list spinner does not flash. */
  articleContentLoading: boolean;
  /** Last article operation failure. */
  articleError: string | null;
  /** Folder-tree load failure; kept apart so a successful article load cannot erase it. */
  folderError: string | null;
  uploadResult: UploadResponseDto | null;
  /** Ids of recently uploaded/published articles still being indexed by EasyRAG. */
  indexingIds: Set<string>;
  /** Derived progress: { done, total } — only meaningful when indexingIds is non-empty. */
  indexingProgress: { done: number; total: number } | null;
  /** AI compose state. */
  composing: boolean;
  composeResult: string | null;
  composeError: string | null;

  loadArticles: (category?: string, status?: ArticleStatus) => Promise<void>;
  loadFolders: () => Promise<void>;
  loadArticleContent: (id: string) => Promise<string>;
  saveToKnowledge: (request: SaveArticleRequest) => Promise<KnowledgeArticleDto>;
  updateArticle: (request: UpdateArticleRequest, options?: { reload?: boolean }) => Promise<KnowledgeArticleDto>;
  publishArticle: (request: PublishArticleRequest) => Promise<KnowledgeArticleDto>;
  unpublishArticle: (id: string) => Promise<KnowledgeArticleDto>;
  setArticleRag: (request: SetArticleRagRequest) => Promise<KnowledgeArticleDto>;
  moveArticle: (request: MoveArticleRequest) => Promise<KnowledgeArticleDto>;
  deleteArticle: (id: string) => Promise<void>;
  createFolder: (request: CreateFolderRequest) => Promise<KnowledgeFolderDto>;
  renameFolder: (id: string, name: string) => Promise<KnowledgeFolderDto>;
  deleteFolder: (id: string) => Promise<void>;
  upload: (files: File[], paths: string[], folderId?: string, category?: string) => Promise<UploadResponseDto>;
  aiCompose: (request: AiComposeRequest) => Promise<string>;
  clearComposeResult: () => void;
  /** Start polling for indexing progress of the given ids. */
  startIndexingPoll: (ids: string[]) => void;
  /** Stop polling and clear indexing state. */
  stopIndexingPoll: () => void;
  clearArticleError: () => void;
  clearFolderError: () => void;
  clearUploadResult: () => void;
}

export const useKnowledgeStore = create<KnowledgeStore>((set, get) => {
  let indexingTimer: ReturnType<typeof setInterval> | null = null;
  let indexingDeadline = 0;
  /** Bumped by every stop/start, so an in-flight poll response cannot write into a newer batch. */
  let indexingEpoch = 0;

  const stopPolling = () => {
    indexingEpoch += 1;
    if (indexingTimer) {
      clearInterval(indexingTimer);
      indexingTimer = null;
    }
  };

  return {
    articles: [],
    folders: [],
    articleLoading: false,
    folderLoading: false,
    articleContentLoading: false,
    articleError: null,
    folderError: null,
    uploadResult: null,
    indexingIds: new Set(),
    indexingProgress: null,
    composing: false,
    composeResult: null,
    composeError: null,

    loadArticles: async (category, status) => {
      set({ articleLoading: true, articleError: null });
      try {
        const articles = await knowledgeService.listArticles(category, status);
        set({ articles, articleLoading: false });
      } catch (e) {
        set({ articleError: readErrorMessage(e), articleLoading: false });
        throw e;
      }
    },

    loadFolders: async () => {
      set({ folderLoading: true, folderError: null });
      try {
        const folders = await knowledgeService.listFolders();
        set({ folders, folderLoading: false });
      } catch (e) {
        set({ folderError: readErrorMessage(e), folderLoading: false });
        throw e;
      }
    },

    loadArticleContent: async (id) => {
      set({ articleContentLoading: true, articleError: null });
      try {
        const content = await knowledgeService.getArticleContent(id);
        set({ articleContentLoading: false });
        return content;
      } catch (e) {
        set({ articleError: readErrorMessage(e), articleContentLoading: false });
        throw e;
      }
    },

    saveToKnowledge: async (request) => {
      const article = await knowledgeService.saveToKnowledge(request);
      // The article is already persisted; a list-refresh failure must not turn a successful save
      // into an error for the caller.
      await get().loadArticles().catch(() => undefined);
      return article;
    },

    updateArticle: async (request, options) => {
      set({ articleLoading: true, articleError: null });
      try {
        const article = await knowledgeService.updateArticle(request);
        set({ articleLoading: false });
        // A caller that publishes right after the save reloads the list itself; skip the duplicate.
        if (options?.reload !== false) {
          await get().loadArticles().catch(() => undefined);
        }
        return article;
      } catch (e) {
        set({ articleError: readErrorMessage(e), articleLoading: false });
        throw e;
      }
    },

    publishArticle: async (request) => {
      set({ articleError: null });
      try {
        const article = await knowledgeService.publishArticle(request);
        await get().loadArticles().catch(() => undefined);
        return article;
      } catch (e) {
        set({ articleError: readErrorMessage(e) });
        throw e;
      }
    },

    unpublishArticle: async (id) => {
      set({ articleError: null });
      try {
        const article = await knowledgeService.unpublishArticle(id);
        await get().loadArticles().catch(() => undefined);
        return article;
      } catch (e) {
        set({ articleError: readErrorMessage(e) });
        throw e;
      }
    },

    setArticleRag: async (request) => {
      set({ articleError: null });
      try {
        const article = await knowledgeService.setArticleRag(request);
        await get().loadArticles().catch(() => undefined);
        return article;
      } catch (e) {
        set({ articleError: readErrorMessage(e) });
        throw e;
      }
    },

    moveArticle: async (request) => {
      set({ articleError: null });
      try {
        const article = await knowledgeService.moveArticle(request);
        await get().loadArticles().catch(() => undefined);
        return article;
      } catch (e) {
        set({ articleError: readErrorMessage(e) });
        throw e;
      }
    },

    deleteArticle: async (id) => {
      set({ articleError: null });
      try {
        await knowledgeService.deleteArticle(id);
        await get().loadArticles().catch(() => undefined);
      } catch (e) {
        set({ articleError: readErrorMessage(e) });
        throw e;
      }
    },

    createFolder: async (request) => {
      set({ articleError: null });
      try {
        const folder = await knowledgeService.createFolder(request);
        await get().loadFolders().catch(() => undefined);
        return folder;
      } catch (e) {
        set({ articleError: readErrorMessage(e) });
        throw e;
      }
    },

    renameFolder: async (id, name) => {
      set({ articleError: null });
      try {
        const folder = await knowledgeService.renameFolder(id, name);
        await get().loadFolders().catch(() => undefined);
        // A rename changes every descendant's display path, so refresh the article list too.
        await get().loadArticles().catch(() => undefined);
        return folder;
      } catch (e) {
        set({ articleError: readErrorMessage(e) });
        throw e;
      }
    },

    deleteFolder: async (id) => {
      set({ articleError: null });
      try {
        await knowledgeService.deleteFolder(id);
        await get().loadFolders().catch(() => undefined);
      } catch (e) {
        set({ articleError: readErrorMessage(e) });
        throw e;
      }
    },

    upload: async (files, paths, folderId, category) => {
      set({ articleLoading: true, articleError: null, uploadResult: null });
      try {
        const result = await knowledgeService.upload(files, paths, folderId, category);
        set({ uploadResult: result, articleLoading: false });
        // Uploads can create folders along the relative paths, so refresh both lists.
        await get().loadFolders().catch(() => undefined);
        await get().loadArticles().catch(() => undefined);
        // Pending-index files never appear in the index, so polling them would hang the progress
        // bar until the deadline; their outcome is already shown in the upload result.
        const uploadedIds = result.results
          .filter((r) => r.success && r.id && !r.indexPending)
          .map((r) => r.id as string);
        if (uploadedIds.length > 0) {
          get().startIndexingPoll(uploadedIds);
        }
        return result;
      } catch (e) {
        set({ articleError: readErrorMessage(e), articleLoading: false });
        throw e;
      }
    },

    aiCompose: async (request) => {
      set({ composing: true, composeError: null, composeResult: null });
      try {
        const result = await knowledgeService.aiCompose(request);
        set({ composing: false, composeResult: result.content });
        return result.content;
      } catch (e) {
        set({ composeError: readErrorMessage(e), composing: false });
        throw e;
      }
    },

    clearComposeResult: () => set({ composeResult: null, composeError: null }),

    startIndexingPoll: (ids) => {
      stopPolling();
      const epoch = indexingEpoch;
      indexingDeadline = Date.now() + INDEXING_POLL_MAX_MS;
      set({ indexingIds: new Set(ids), indexingProgress: { done: 0, total: ids.length } });

      indexingTimer = setInterval(async () => {
        if (epoch !== indexingEpoch) return;
        const { indexingIds } = get();
        if (indexingIds.size === 0) {
          stopPolling();
          return;
        }
        if (Date.now() > indexingDeadline) {
          // Give up without lying: drop the progress bar instead of leaving it frozen forever.
          stopPolling();
          set({ indexingIds: new Set(), indexingProgress: null });
          await get().loadArticles().catch(() => undefined);
          return;
        }
        try {
          const pending = Array.from(indexingIds);
          const counts: Record<string, number> = {};
          for (let i = 0; i < pending.length; i += INDEX_STATUS_BATCH) {
            if (epoch !== indexingEpoch) return;
            const batch = pending.slice(i, i + INDEX_STATUS_BATCH);
            const res = await knowledgeService.indexStatus(batch);
            Object.assign(counts, res.counts);
          }
          if (epoch !== indexingEpoch) return;
          const remaining = new Set<string>();
          for (const id of pending) {
            if (counts[id] == null) remaining.add(id);
          }
          // Done is cumulative against the original batch: recounting per round made the bar move
          // backwards whenever a round finished nothing.
          set({ indexingIds: remaining, indexingProgress: { done: ids.length - remaining.size, total: ids.length } });
          if (remaining.size === 0) {
            stopPolling();
            await get().loadArticles().catch(() => undefined);
          }
        } catch {
          // Transient failure — keep polling.
        }
      }, INDEXING_POLL_INTERVAL_MS);
    },

    stopIndexingPoll: () => {
      // Bumping the epoch is what cancels an in-flight round; clearing the timer alone would let
      // its response repopulate indexing state after the panel is gone.
      stopPolling();
      set({ indexingIds: new Set(), indexingProgress: null });
    },

    clearArticleError: () => set({ articleError: null }),
    clearFolderError: () => set({ folderError: null }),
    clearUploadResult: () => set({ uploadResult: null }),
  };
});
