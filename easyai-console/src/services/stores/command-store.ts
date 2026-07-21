import { create } from 'zustand';
import { CommandService } from '@/services/command-service';
import type { UserCommand, UserCommandCreateRequest } from '@/types/command';

interface CommandStore {
  // State
  commands: UserCommand[];
  loading: boolean;
  error: string | null;

  // Actions
  loadCommands: () => Promise<void>;
  createCommand: (request: UserCommandCreateRequest) => Promise<UserCommand>;
  updateCommand: (id: string, request: UserCommandCreateRequest) => Promise<UserCommand>;
  deleteCommand: (id: string) => Promise<void>;
  clearError: () => void;
}

export const useCommandStore = create<CommandStore>((set, _get) => ({
  commands: [],
  loading: false,
  error: null,

  loadCommands: async () => {
    set({ loading: true, error: null });
    try {
      const commands = await CommandService.listUserCommands();
      set({ commands, loading: false });
    } catch (err) {
      set({ error: (err as Error).message, loading: false });
    }
  },

  createCommand: async (request) => {
    const command = await CommandService.createUserCommand(request);
    set((state) => ({ commands: [...state.commands, command] }));
    // Invalidate slash command cache so new command appears in autocomplete
    CommandService.invalidateCache();
    return command;
  },

  updateCommand: async (id, request) => {
    const updated = await CommandService.updateUserCommand(id, request);
    set((state) => ({
      commands: state.commands.map((c) => (c.id === id ? updated : c)),
    }));
    CommandService.invalidateCache();
    return updated;
  },

  deleteCommand: async (id) => {
    await CommandService.deleteUserCommand(id);
    set((state) => ({
      commands: state.commands.filter((c) => c.id !== id),
    }));
    CommandService.invalidateCache();
  },

  clearError: () => set({ error: null }),
}));
