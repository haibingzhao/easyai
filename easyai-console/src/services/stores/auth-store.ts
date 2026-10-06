import { create } from 'zustand';
import { setAccessToken, getAccessToken } from '@/services/api-client';
import { authService } from '@/services/auth-service';
import type { UserProfile } from '@/services/auth-service';
import { uploadAvatar } from '@/services/user-service';
import { useProjectStore } from './project-store';
import { useChatStore } from './chat-store';

interface AuthState {
  user: UserProfile | null;
  isAuthenticated: boolean;
  authLoading: boolean;

  checkAuth: () => Promise<boolean>;
  login: (username: string, password: string) => Promise<void>;
  register: (
    username: string,
    password: string,
    email: string,
    displayName?: string,
    avatarFile?: File,
  ) => Promise<void>;
  logout: () => Promise<void>;
  /** Write back a profile the backend just mutated, so the header updates without another /me read. */
  applyProfile: (user: UserProfile) => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,
  authLoading: true,

  checkAuth: async () => {
    try {
      // Step 1: Try /api/auth/me (works if token in memory or auth disabled)
      const headers: Record<string, string> = {};
      const existingToken = getAccessToken();
      if (existingToken) headers['Authorization'] = `Bearer ${existingToken}`;
      let res = await fetch('/api/auth/me', { credentials: 'same-origin', headers });

      // Step 2: On 401, try refreshing via httpOnly cookie
      if (res.status === 401) {
        const refreshRes = await fetch('/api/auth/refresh', {
          method: 'POST',
          credentials: 'same-origin',
        });
        if (refreshRes.ok) {
          const refreshData = await refreshRes.json();
          setAccessToken(refreshData.accessToken);

          // Retry /me with the new token
          res = await fetch('/api/auth/me', {
            credentials: 'same-origin',
            headers: { Authorization: `Bearer ${refreshData.accessToken}` },
          });
        }
      }

      if (res.ok) {
        const user: UserProfile = await res.json();
        set({ user, isAuthenticated: true, authLoading: false });
        return true;
      }
    } catch {
      // Network error or other failure
    }

    set({ user: null, isAuthenticated: false, authLoading: false });
    return false;
  },

  login: async (username, password) => {
    const response = await authService.login(username, password);
    setAccessToken(response.accessToken);
    resetUserScopedState();
    set({ user: response.user, isAuthenticated: true });
  },

  register: async (username, password, email, displayName, avatarFile) => {
    const response = await authService.register(username, password, email, displayName);
    setAccessToken(response.accessToken);
    resetUserScopedState();
    set({ user: response.user, isAuthenticated: true });
    if (!avatarFile) return;
    // The avatar rides on the account that now exists: picking one must never be a reason to fail signup,
    // and Settings offers the same upload if this call is cut short.
    try {
      set({ user: await uploadAvatar(avatarFile) });
    } catch {
      // Ignored on purpose.
    }
  },

  logout: async () => {
    try {
      await authService.logout();
    } catch {
      // Best effort
    }
    setAccessToken(null);
    resetUserScopedState();
    set({ user: null, isAuthenticated: false });
  },

  applyProfile: (user) => set({ user }),
}));

/** Reset user-scoped stores (project selection, chat) to prevent cross-user state leakage */
function resetUserScopedState() {
  useProjectStore.getState().resetForUserSwitch();
  useChatStore.getState().clearChat();
}
