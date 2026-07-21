import { create } from 'zustand';
import type { SessionMetadata } from '@/types/settings';
import type { SessionListItem } from '@/services/session-service';
import { storageService } from '@/services/storage-service';
import { sessionService } from '@/services/session-service';

interface SessionState {
  sessions: SessionMetadata[];
  currentSessionId: string | null;
  remoteSessions: SessionListItem[];
  remoteSessionOffset: number;
  remoteSessionHasMore: boolean;
  remoteSessionLoading: boolean;

  setSessions: (sessions: SessionMetadata[]) => void;
  setCurrentSessionId: (id: string | null) => void;
  addSession: (session: SessionMetadata) => void;
  removeSession: (id: string) => void;
  loadSessions: () => void;
  saveSessions: () => void;

  setRemoteSessions: (sessions: SessionListItem[]) => void;
  loadRemoteSessions: (limit?: number, append?: boolean, projectId?: string) => Promise<void>;
  loadMoreRemoteSessions: (limit?: number, projectId?: string) => Promise<void>;
  deleteRemoteSession: (id: string) => Promise<void>;
}

export const useSessionStore = create<SessionState>((set, get) => ({
  sessions: [],
  currentSessionId: null,
  remoteSessions: [],
  remoteSessionOffset: 0,
  remoteSessionHasMore: false,
  remoteSessionLoading: false,

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

  loadRemoteSessions: async (limit = 10, append = false, projectId?: string) => {
    const state = get();
    if (state.remoteSessionLoading) return;

    const offset = append ? state.remoteSessionOffset : 0;
    set({ remoteSessionLoading: true });

    try {
      const result = await sessionService.listSessions(limit, offset, projectId);
      set({
        remoteSessions: append ? [...state.remoteSessions, ...result.sessions] : result.sessions,
        remoteSessionOffset: offset + result.sessions.length,
        remoteSessionHasMore: result.hasMore,
      });
    } catch (e) {
      console.error('Failed to load remote sessions:', e);
    } finally {
      set({ remoteSessionLoading: false });
    }
  },

  loadMoreRemoteSessions: async (limit = 10, projectId?: string) => {
    return get().loadRemoteSessions(limit, true, projectId);
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
}));
