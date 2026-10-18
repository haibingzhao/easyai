import { create } from 'zustand';
import { taskService } from '@/services/task-service';
import type { AsyncTask, TaskFilters } from '@/types/task';

interface TaskStore {
  tasks: AsyncTask[];
  loading: boolean;
  error: string | null;
  filters: TaskFilters;

  setFilters: (filters: TaskFilters) => void;
  /** Reload the list. `silent` refreshes rows without flipping `loading` (background polling). */
  loadTasks: (filters?: TaskFilters, options?: { silent?: boolean }) => Promise<void>;
  cancelTask: (id: string) => Promise<void>;
  retryTask: (id: string) => Promise<void>;
  deleteTask: (id: string) => Promise<void>;
  clearTasks: (status?: string) => Promise<void>;
  clearError: () => void;
}

export const useTaskStore = create<TaskStore>((set, get) => ({
  tasks: [],
  loading: false,
  error: null,
  filters: {},

  setFilters: (filters) => set({ filters }),

  loadTasks: async (filters, options) => {
    const effective = filters ?? get().filters;
    // A poll refreshes rows in place; flipping `loading` every few seconds spins the header icon and
    // swaps the list for a "Loading…" placeholder the user never asked to see.
    set(options?.silent ? { filters: effective } : { loading: true, error: null, filters: effective });
    try {
      const tasks = await taskService.listTasks(effective);
      set({ tasks, loading: false });
    } catch (err) {
      set({ error: (err as Error).message, loading: false });
    }
  },

  cancelTask: async (id) => {
    try {
      await taskService.cancelTask(id);
      await get().loadTasks();
    } catch (err) {
      set({ error: (err as Error).message });
    }
  },

  retryTask: async (id) => {
    try {
      await taskService.retryTask(id);
      await get().loadTasks();
    } catch (err) {
      set({ error: (err as Error).message });
    }
  },

  deleteTask: async (id) => {
    try {
      await taskService.deleteTask(id);
      set((state) => ({ tasks: state.tasks.filter((t) => t.id !== id) }));
    } catch (err) {
      set({ error: (err as Error).message });
    }
  },

  clearTasks: async (status) => {
    try {
      await taskService.clearTasks(status);
      // The reload is the authority on what is left; filtering locally first would only blank rows
      // the server may have kept (a task that settled during the delete, or another tab's change).
      await get().loadTasks();
    } catch (err) {
      set({ error: (err as Error).message });
    }
  },

  clearError: () => set({ error: null }),
}));
