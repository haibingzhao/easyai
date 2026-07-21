import { authFetch } from '@/services/api-client';

export interface Project {
  id: string;
  name: string;
  path: string;
  description: string | null;
  memoryAutoGeneration: boolean;
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

    const response = await authFetch(url);
    if (!response.ok) {
      const error = await response.text().catch(() => '');
      throw new Error(`Failed to list projects: ${error || `HTTP ${response.status}`}`);
    }
    return response.json();
  }

  /**
   * Create a new project.
   */
  async createProject(request: CreateProjectRequest): Promise<Project> {
    const response = await authFetch(API_BASE, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      const error = await response.text();
      throw new Error(`Failed to create project: ${error}`);
    }
    return response.json();
  }

  /**
   * Get a single project by ID.
   */
  async getProject(id: string): Promise<Project> {
    const response = await authFetch(`${API_BASE}/${id}`);
    if (!response.ok) {
      const error = await response.text().catch(() => '');
      throw new Error(`Failed to get project: ${error || `HTTP ${response.status}`}`);
    }
    return response.json();
  }

  /**
   * Delete a project by ID.
   */
  async deleteProject(id: string): Promise<void> {
    const response = await authFetch(`${API_BASE}/${id}`, {
      method: 'DELETE',
    });
    if (!response.ok) {
      const error = await response.text().catch(() => '');
      throw new Error(`Failed to delete project: ${error || `HTTP ${response.status}`}`);
    }
  }

  /**
   * Update a project.
   */
  async updateProject(id: string, request: UpdateProjectRequest): Promise<Project> {
    const response = await authFetch(`${API_BASE}/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      const error = await response.text().catch(() => '');
      throw new Error(`Failed to update project: ${error || `HTTP ${response.status}`}`);
    }
    return response.json();
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
