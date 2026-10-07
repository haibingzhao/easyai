import { authFetch, fetchJson, fetchVoid, JSON_HEADERS } from '@/services/api-client';

export interface Project {
  id: string;
  name: string;
  path: string;
  description: string | null;
  memoryAutoGeneration: boolean;
  /** 'temp' marks a system-managed per-session workspace; it is absent from project lists. */
  kind?: 'user' | 'temp';
  createdAt: number;
  updatedAt: number;
}

export interface CreateProjectRequest {
  name: string;
  path: string;
  description?: string;
}

export interface UpdateProjectRequest {
  name?: string;
  description?: string;
  memoryAutoGeneration?: boolean;
}

/** Workspace fields a session detail carries about the project (or temporary workspace) it runs in. */
export interface SessionWorkspaceInfo {
  workspaceProjectId?: string | null;
  workspacePath?: string | null;
  workspaceKind?: string | null;
}

/**
 * Extract the project-store adoption payload from a session detail.
 * Returns null when the backend reported no workspace (older backend, or a session that
 * never ran a turn).
 */
export function sessionWorkspace(detail: SessionWorkspaceInfo): { projectId: string; path: string; kind: string } | null {
  if (!detail.workspaceProjectId || !detail.workspacePath) return null;
  return {
    projectId: detail.workspaceProjectId,
    path: detail.workspacePath,
    kind: detail.workspaceKind ?? 'user',
  };
}

/**
 * Extract the workspace payload of a `session_context` SSE handshake, which carries the same
 * information under its event field names.
 */
export function eventWorkspace(event: { projectId?: string; projectPath?: string; projectKind?: 'user' | 'temp' }): { projectId: string; path: string; kind: string } | null {
  if (!event.projectId || !event.projectPath) return null;
  return { projectId: event.projectId, path: event.projectPath, kind: event.projectKind ?? 'user' };
}

const API_BASE = '/api/projects';

export class ProjectService {
  /**
   * List all projects.
   */
  async listProjects(options?: { limit?: number; search?: string }): Promise<Project[]> {
    const params = new URLSearchParams();
    if (options?.limit) params.set('limit', String(options.limit));
    if (options?.search) params.set('search', options.search);
    const query = params.toString();
    const url = query ? `${API_BASE}?${query}` : API_BASE;
    return fetchJson<Project[]>(url);
  }

  /**
   * Create a new project.
   */
  async createProject(request: CreateProjectRequest): Promise<Project> {
    return fetchJson<Project>(API_BASE, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  /**
   * Get a single project by ID.
   */
  async getProject(id: string): Promise<Project> {
    return fetchJson<Project>(`${API_BASE}/${id}`);
  }

  /**
   * Delete a project by ID.
   */
  async deleteProject(id: string): Promise<void> {
    return fetchVoid(`${API_BASE}/${id}`, { method: 'DELETE' });
  }

  /**
   * Update a project.
   */
  async updateProject(id: string, request: UpdateProjectRequest): Promise<Project> {
    return fetchJson<Project>(`${API_BASE}/${id}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  /**
   * List subdirectories under a given path.
   * If path is omitted, defaults to the system user home directory.
   */
  async listDirectories(path?: string): Promise<{ currentPath: string; directories: string[]; permissionDenied?: boolean }> {
    const params = new URLSearchParams();
    if (path) params.set('path', path);
    const response = await authFetch(`${API_BASE}/list-directories?${params}`);
    if (!response.ok) return { currentPath: '', directories: [] };
    return response.json();
  }
}

export const projectService = new ProjectService();
