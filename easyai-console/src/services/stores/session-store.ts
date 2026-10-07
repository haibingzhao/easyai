import { create } from 'zustand';
import type { SessionMetadata } from '@/types/settings';
import type { SessionListItem } from '@/services/session-service';
import { storageService } from '@/services/storage-service';
import { sessionService } from '@/services/session-service';

/**
 * Monotonic token for remote session list loads. Rapid filter/project changes can
 * overlap requests; only the latest one may write results, so a stale response can
 * never clobber the list that matches the currently-selected filter chips.
 */
let loadSeq = 0;

interface SessionState {
  sessions: SessionMetadata[];
  currentSessionId: string | null;
  remoteSessions: SessionListItem[];
  remoteSessionOffset: number;
  remoteSessionHasMore: boolean;
  remoteSessionLoading: boolean;
  /** Project id used by the last remote load — reused to refresh available tags after an edit. */
  remoteSessionProjectId?: string;
  /** Whether the last remote load was scoped to project-less (temporary workspace) sessions. */
  remoteSessionTempWorkspace?: boolean;
  /** Distinct tags in use, for autocomplete + the History filter chip row. */
  availableTags: string[];
  /** Currently active OR tag filter (empty = no filtering). */
  activeTagFilter: string[];

  setSessions: (sessions: SessionMetadata[]) => void;
  setCurrentSessionId: (id: string | null) => void;
  addSession: (session: SessionMetadata) => void;
  removeSession: (id: string) => void;
  loadSessions: () => void;
  saveSessions: () => void;

  setRemoteSessions: (sessions: SessionListItem[]) => void;
  loadRemoteSessions: (limit?: number, append?: boolean, projectId?: string, tempWorkspace?: boolean) => Promise<void>;
  loadMoreRemoteSessions: (limit?: number, projectId?: string, tempWorkspace?: boolean) => Promise<void>;
  deleteRemoteSession: (id: string) => Promise<void>;
  loadSessionTags: (projectId?: string, tempWorkspace?: boolean) => Promise<void>;
  setTagFilter: (tags: string[]) => void;
  updateRemoteSessionTags: (id: string, tags: string[]) => Promise<void>;
}

export const useSessionStore = create<SessionState>((set, get) => ({
  sessions: [],
  currentSessionId: null,
  remoteSessions: [],
  remoteSessionOffset: 0,
  remoteSessionHasMore: false,
  remoteSessionLoading: false,
  availableTags: [],
  activeTagFilter: [],

  setSessions: (sessions) => set({ sessions }),

  setCurrentSessionId: (id) => set({ currentSessionId: id }),

  addSession: (session) => set((state) => ({
    sessions: [session, ...state.sessions]
  })),

  removeSession: (id) => set((state) => ({
    sessions: state.sessions.filter(s => s.id !== id)
  })),

  loadSessions: () => {
    const sessions = storageService.getSessions();
    set({ sessions });
  },

  saveSessions: () => {
    const { sessions } = get();
    storageService.saveSessions(sessions);
  },

  setRemoteSessions: (sessions) => set({ remoteSessions: sessions }),

  loadRemoteSessions: async (limit = 10, append = false, projectId?: string, tempWorkspace?: boolean) => {
    const state = get();
    // Pagination guard: never start a second "load more" while one is in flight
    // (it would fetch the same offset twice). Replace-loads are allowed to overlap
    // so the newest filter always wins — resolved via the loadSeq token below.
    if (append && state.remoteSessionLoading) return;

    const offset = append ? state.remoteSessionOffset : 0;
    const tags = state.activeTagFilter.length > 0 ? state.activeTagFilter : undefined;
    const seq = ++loadSeq;
    set({ remoteSessionLoading: true, remoteSessionProjectId: projectId, remoteSessionTempWorkspace: tempWorkspace });

    try {
      const result = await sessionService.listSessions(limit, offset, projectId, tags, tempWorkspace);
      if (seq !== loadSeq) return; // superseded by a newer request
      set({
        remoteSessions: append ? [...get().remoteSessions, ...result.sessions] : result.sessions,
        remoteSessionOffset: offset + result.sessions.length,
        remoteSessionHasMore: result.hasMore,
      });
    } catch (e) {
      if (seq === loadSeq) console.error('Failed to load remote sessions:', e);
    } finally {
      if (seq === loadSeq) set({ remoteSessionLoading: false });
    }
  },

  loadMoreRemoteSessions: async (limit = 10, projectId?: string, tempWorkspace?: boolean) => {
    return get().loadRemoteSessions(limit, true, projectId, tempWorkspace);
  },

  deleteRemoteSession: async (id: string) => {
    try {
      await sessionService.deleteSession(id);
      const { remoteSessions } = get();
      set({
        remoteSessions: remoteSessions.filter(s => s.id !== id),
        // Reset pagination state after delete
        remoteSessionOffset: 0,
        remoteSessionHasMore: false,
      });
    } catch (e) {
      console.error('Failed to delete remote session:', e);
    }
  },

  loadSessionTags: async (projectId?: string, tempWorkspace?: boolean) => {
    try {
      const tags = await sessionService.listSessionTags(projectId, tempWorkspace);
      set({ availableTags: tags });
    } catch (e) {
      console.error('Failed to load session tags:', e);
    }
  },

  setTagFilter: (tags) => set({
    activeTagFilter: tags,
    remoteSessionOffset: 0,
    remoteSessionHasMore: false,
  }),

  updateRemoteSessionTags: async (id: string, tags: string[]) => {
    const prevSessions = get().remoteSessions;
    const prevTags = get().availableTags;
    // Optimistic: update the row immediately and merge any new tags into the chip row.
    set({
      remoteSessions: prevSessions.map(s => (s.id === id ? { ...s, tags } : s)),
      availableTags: Array.from(new Set([...prevTags, ...tags])).sort(),
    });
    try {
      await sessionService.updateSessionTags(id, tags);
      // Refresh the authoritative tag set so removed-but-now-unused tags drop off the chip row.
      await get().loadSessionTags(get().remoteSessionProjectId, get().remoteSessionTempWorkspace);
      // Under an active tag filter the edited session may no longer match — reload the list
      // so it disappears instead of lingering with stale (optimistic) contents.
      if (get().activeTagFilter.length > 0) {
        await get().loadRemoteSessions(20, false, get().remoteSessionProjectId, get().remoteSessionTempWorkspace);
      }
    } catch (e) {
      console.error('Failed to update session tags:', e);
      set({ remoteSessions: prevSessions, availableTags: prevTags });
      throw e;
    }
  },
}));
