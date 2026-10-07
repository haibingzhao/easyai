import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { useChatStore } from './chat-store';
import { useNavStore } from './nav-store';
import type { Project } from '@/services/project-service';
import type { UpdateProjectRequest, SessionWorkspaceInfo } from '@/services/project-service';
import { projectService, sessionWorkspace, eventWorkspace } from '@/services/project-service';
import { i18n } from '@/utils/i18n';

/** Name carried by a temporary workspace project; the UI renders a localized label instead. */
const TEMP_WORKSPACE_NAME = 'temp';

interface ProjectState {
  currentProject: Project | null;
  projects: Project[];
  projectsLoading: boolean;
  userSelected: boolean;
  /** The user chose to chat without a project; each session then gets a temporary workspace. */
  workspaceOnly: boolean;

  setCurrentProject: (project: Project | null) => void;
  setProjects: (projects: Project[]) => void;
  loadProjects: () => Promise<void>;
  loadRecentProjects: (limit: number) => Promise<void>;
  searchProjects: (query: string) => Promise<Project[]>;
  selectProject: (project: Project) => void;
  startWithoutProject: () => void;
  createProject: (name: string, path: string, description?: string) => Promise<Project>;
  updateProject: (id: string, data: UpdateProjectRequest) => Promise<Project>;
  deleteProject: (id: string) => Promise<void>;
  /** Adopt the backend-reported workspace of a session (temporary or user project) as active. */
  adoptWorkspace: (workspace: { projectId: string; path: string; kind: string; name?: string } | null | undefined) => void;
  /** Clear an adopted temporary workspace (a real project is never dropped here). */
  releaseWorkspace: () => void;
  /** Clear project state on user switch (logout/login) to avoid cross-user project leakage */
  resetForUserSwitch: () => void;
}

export const useProjectStore = create<ProjectState>()(
  persist(
    (set, get) => ({
      currentProject: null,
      projects: [],
      projectsLoading: false,
      userSelected: false,
      workspaceOnly: false,

      setCurrentProject: (project) => set({ currentProject: project, userSelected: project != null }),

      setProjects: (projects) => set({ projects }),

      loadProjects: async () => {
        set({ projectsLoading: true });
        try {
          const projects = await projectService.listProjects();
          set({ projects });

          const { currentProject } = get();

          // Validate: if currentProject is stale (not in server list), clear it.
          // Temporary workspaces are excluded from the list by design, so they never appear here.
          if (currentProject && currentProject.kind !== 'temp'
            && !projects.some((p) => p.id === currentProject.id)) {
            set({ currentProject: null, userSelected: false });
          }

        } catch (e) {
          console.error('Failed to load projects:', e);
        } finally {
          set({ projectsLoading: false });
          // Auto-open right panel with Files tab whenever a real project exists (covers both
          // fresh selection and page refresh). Temporary workspaces stay out of the way —
          // they are usually empty scratch directories tied to a single session.
          const active = get().currentProject;
          if (active && active.kind !== 'temp') {
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
          alert(i18n('Cannot switch project while streaming. Please wait for the response to complete.'));
          return;
        }

        const { currentProject } = get();
        if (currentProject?.id === project.id) {
          return;
        }

        useChatStore.getState().setSessionId(null);
        useChatStore.getState().clearChat();
        set({ currentProject: project, userSelected: true, workspaceOnly: false });
        const nav = useNavStore.getState();
        nav.setRightPanelOpen(true);
        nav.setRightPanelTab('files');
      },

      startWithoutProject: () => {
        const { isStreaming } = useChatStore.getState();
        if (isStreaming) {
          alert(i18n('Cannot switch project while streaming. Please wait for the response to complete.'));
          return;
        }
        useChatStore.getState().setSessionId(null);
        useChatStore.getState().clearChat();
        set({ currentProject: null, userSelected: false, workspaceOnly: true });
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

      resetForUserSwitch: () => set({ currentProject: null, projects: [], userSelected: false, workspaceOnly: false }),

      adoptWorkspace: (workspace) => {
        if (!workspace?.projectId) return;
        const { currentProject, projects } = get();
        if (workspace.kind === 'temp') {
          // A temporary workspace belongs to one session only: never let it shadow a project
          // the user picked for this browser session.
          if (currentProject && currentProject.kind !== 'temp') return;
          if (currentProject?.id === workspace.projectId && currentProject.path === workspace.path) return;
          set({
            workspaceOnly: true,
            currentProject: {
              id: workspace.projectId,
              name: TEMP_WORKSPACE_NAME,
              path: workspace.path,
              description: null,
              memoryAutoGeneration: false,
              kind: 'temp',
              createdAt: 0,
              updatedAt: 0,
            },
          });
          return;
        }
        const known = projects.find((p) => p.id === workspace.projectId)
          ?? (currentProject?.id === workspace.projectId ? currentProject : null);
        if (known) {
          if (currentProject?.id !== known.id) set({ currentProject: known, userSelected: true });
        } else {
          set({
            currentProject: {
              id: workspace.projectId,
              name: workspace.name ?? workspace.projectId,
              path: workspace.path,
              description: null,
              memoryAutoGeneration: true,
              kind: 'user',
              createdAt: 0,
              updatedAt: 0,
            },
            userSelected: true,
          });
        }
      },

      releaseWorkspace: () => {
        if (get().currentProject?.kind === 'temp') {
          set({ currentProject: null, userSelected: false });
        }
      },
    }),
    {
      name: 'easyai-project',
      partialize: (state) => ({ currentProject: state.currentProject, workspaceOnly: state.workspaceOnly }),
    }
  )
);

/**
 * Point the active project at the workspace of a loaded session: the project (or temporary
 * workspace) the agent actually worked in. A session that reports none clears a stale
 * temporary workspace instead of leaving the previous session's directory active.
 */
export function adoptSessionWorkspace(detail: SessionWorkspaceInfo | null | undefined): void {
  const workspace = detail ? sessionWorkspace(detail) : null;
  const store = useProjectStore.getState();
  if (workspace) {
    store.adoptWorkspace(workspace);
  } else {
    store.releaseWorkspace();
  }
}

/**
 * Adopt the workspace reported by a `session_context` SSE handshake. Called as soon as a turn
 * starts, so the file panel and mentions point at the scratch directory the agent is already
 * writing into — instead of only after the reply lands.
 */
export function adoptHandshakeWorkspace(event: { projectId?: string; projectPath?: string; projectKind?: 'user' | 'temp' }): void {
  const workspace = eventWorkspace(event);
  if (workspace) {
    useProjectStore.getState().adoptWorkspace(workspace);
  }
}
