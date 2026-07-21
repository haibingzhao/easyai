import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { useChatStore } from './chat-store';
import { useNavStore } from './nav-store';
import type { Project } from '@/services/project-service';
import type { UpdateProjectRequest } from '@/services/project-service';
import { projectService } from '@/services/project-service';

interface ProjectState {
  currentProject: Project | null;
  projects: Project[];
  projectsLoading: boolean;
  userSelected: boolean;

  setCurrentProject: (project: Project | null) => void;
  setProjects: (projects: Project[]) => void;
  loadProjects: () => Promise<void>;
  loadRecentProjects: (limit: number) => Promise<void>;
  searchProjects: (query: string) => Promise<Project[]>;
  selectProject: (project: Project) => void;
  createProject: (name: string, path: string, description?: string) => Promise<Project>;
  updateProject: (id: string, data: UpdateProjectRequest) => Promise<Project>;
  deleteProject: (id: string) => Promise<void>;
}

export const useProjectStore = create<ProjectState>()(
  persist(
    (set, get) => ({
      currentProject: null,
      projects: [],
      projectsLoading: false,
      userSelected: false,

      setCurrentProject: (project) => set({ currentProject: project, userSelected: project != null }),

      setProjects: (projects) => set({ projects }),

      loadProjects: async () => {
        set({ projectsLoading: true });
        try {
          const projects = await projectService.listProjects();
          set({ projects });

          const { currentProject } = get();

          // Validate: if currentProject is stale (not in server list), clear it
          if (currentProject && !projects.some((p) => p.id === currentProject.id)) {
            set({ currentProject: null, userSelected: false });
          }

        } catch (e) {
          console.error('Failed to load projects:', e);
        } finally {
          set({ projectsLoading: false });
          // Auto-open right panel with Files tab whenever a valid project exists (covers both fresh selection and page refresh)
          if (get().currentProject) {
            const nav = useNavStore.getState();
            nav.setRightPanelOpen(true);
            nav.setRightPanelTab('files');
          }
        }
      },

      loadRecentProjects: async (limit: number) => {
        try {
          const projects = await projectService.listProjects({ limit });
          set({ projects });
        } catch (e) {
          console.error('Failed to load recent projects:', e);
        }
      },

      searchProjects: async (query: string): Promise<Project[]> => {
        try {
          return await projectService.listProjects({ search: query });
        } catch (e) {
          console.error('Failed to search projects:', e);
          return [];
        }
      },

      selectProject: (project: Project) => {
        const { isStreaming } = useChatStore.getState();
        if (isStreaming) {
          alert('Cannot switch project while streaming. Please wait for the response to complete.');
          return;
        }

        const { currentProject } = get();
        if (currentProject?.id === project.id) {
          return;
        }

        useChatStore.getState().setSessionId(null);
        useChatStore.getState().clearChat();
        set({ currentProject: project, userSelected: true });
        const nav = useNavStore.getState();
        nav.setRightPanelOpen(true);
        nav.setRightPanelTab('files');
      },

      createProject: async (name: string, path: string, description?: string) => {
        const project = await projectService.createProject({ name, path, description });
        set((state) => ({
          projects: [...state.projects, project],
          currentProject: state.currentProject ?? project,
        }));
        return project;
      },

      updateProject: async (id: string, data: UpdateProjectRequest) => {
        const updated = await projectService.updateProject(id, data);
        set((state) => ({
          projects: state.projects.map((p) => (p.id === id ? updated : p)),
          currentProject: state.currentProject?.id === id ? updated : state.currentProject,
        }));
        return updated;
      },

      deleteProject: async (id: string) => {
        await projectService.deleteProject(id);
        set((state) => {
          const newProjects = state.projects.filter((p) => p.id !== id);
          const isCurrentDeleted = state.currentProject?.id === id;
          return {
            projects: newProjects,
            currentProject: isCurrentDeleted
              ? (newProjects[0] ?? null)
              : state.currentProject,
            userSelected: isCurrentDeleted ? false : state.userSelected,
          };
        });
      },
    }),
    {
      name: 'easyai-project',
      partialize: (state) => ({ currentProject: state.currentProject }),
    }
  )
);
