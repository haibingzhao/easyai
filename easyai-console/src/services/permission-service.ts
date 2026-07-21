import type { PermissionRuleDto, ToolPermissionInfo, PermissionSettingsDto, UpdateSettingRequest, FileNodeDto } from '../types/permission';
import { authFetch } from '@/services/api-client';

const API_BASE = '/api/permission';

/**
 * Fetch all permission rules for a project.
 */
export async function fetchPermissionRules(projectId: string): Promise<PermissionRuleDto[]> {
  const response = await authFetch(`${API_BASE}/rules/${projectId}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch permission rules: ${response.status}`);
  }
  return response.json();
}

/**
 * Save all permission rules for a project (full replacement).
 */
export async function savePermissionRules(
  projectId: string,
  rules: PermissionRuleDto[]
): Promise<void> {
  const response = await authFetch(`${API_BASE}/rules/${projectId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rules }),
  });
  if (!response.ok) {
    throw new Error(`Failed to save permission rules: ${response.status}`);
  }
}

/**
 * Add a single permission rule to a project.
 */
export async function addPermissionRule(
  projectId: string,
  rule: PermissionRuleDto
): Promise<void> {
  const response = await authFetch(`${API_BASE}/rules/${projectId}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(rule),
  });
  if (!response.ok) {
    throw new Error(`Failed to add permission rule: ${response.status}`);
  }
}

/**
 * Delete a specific permission rule from a project.
 */
export async function deletePermissionRule(
  projectId: string,
  permission: string,
  pattern: string
): Promise<void> {
  const params = new URLSearchParams({ permission, pattern });
  const response = await authFetch(`${API_BASE}/rules/${projectId}?${params}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    throw new Error(`Failed to delete permission rule: ${response.status}`);
  }
}

/**
 * Get all tools with their current permission status for a project.
 */
export async function fetchToolPermissions(
  projectId: string
): Promise<ToolPermissionInfo[]> {
  const response = await authFetch(`${API_BASE}/tools/${projectId}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch tool permissions: ${response.status}`);
  }
  return response.json();
}

/**
 * Get effective permission settings for a project (defaults + user rules).
 */
export async function fetchPermissionSettings(
  projectId: string
): Promise<PermissionSettingsDto> {
  const response = await authFetch(`${API_BASE}/settings/${projectId}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch permission settings: ${response.status}`);
  }
  return response.json();
}

/**
 * Update a single permission setting (checkbox toggle).
 */
export async function updatePermissionSetting(
  projectId: string,
  request: UpdateSettingRequest
): Promise<void> {
  const response = await authFetch(`${API_BASE}/settings/${projectId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    throw new Error(`Failed to update permission setting: ${response.status}`);
  }
}

/**
 * Get project directory structure for the file browser dropdown.
 */
export async function fetchProjectStructure(
  projectId: string
): Promise<FileNodeDto[]> {
  const response = await authFetch(`${API_BASE}/project-structure/${projectId}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch project structure: ${response.status}`);
  }
  return response.json();
}

/**
 * Search files/folders by name within a project directory (recursive, real-time).
 * Returns a flat list of matching entries with absolute paths.
 */
export async function searchFiles(projectId: string, query: string): Promise<FileNodeDto[]> {
  const params = new URLSearchParams({ projectId, query });
  const response = await authFetch(`${API_BASE}/search-files?${params}`);
  if (!response.ok) {
    throw new Error(`Failed to search files: ${response.status}`);
  }
  return response.json();
}

/**
 * Browse a directory on the server filesystem.
 * Returns one level of contents (files and subdirectories) with absolute paths.
 * When projectId is provided, path is validated to be within the project directory.
 */
export async function browseDirectory(absolutePath: string, projectId?: string): Promise<FileNodeDto[]> {
  const params = new URLSearchParams({ path: absolutePath });
  if (projectId) {
    params.set('projectId', projectId);
  }
  const response = await authFetch(`${API_BASE}/browse-directory?${params}`);
  if (!response.ok) {
    throw new Error(`Failed to browse directory: ${response.status}`);
  }
  return response.json();
}
