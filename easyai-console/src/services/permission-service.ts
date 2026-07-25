import type { PermissionRuleDto, ToolPermissionInfo, PermissionSettingsDto, UpdateSettingRequest, FileNodeDto } from '../types/permission';
import { fetchJson, fetchVoid, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/permission';

/**
 * Fetch all permission rules for a project.
 */
export async function fetchPermissionRules(projectId: string): Promise<PermissionRuleDto[]> {
  return fetchJson<PermissionRuleDto[]>(`${API_BASE}/rules/${projectId}`);
}

/**
 * Save all permission rules for a project (full replacement).
 */
export async function savePermissionRules(
  projectId: string,
  rules: PermissionRuleDto[]
): Promise<void> {
  return fetchVoid(`${API_BASE}/rules/${projectId}`, {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify({ rules }),
  });
}

/**
 * Add a single permission rule to a project.
 */
export async function addPermissionRule(
  projectId: string,
  rule: PermissionRuleDto
): Promise<void> {
  return fetchVoid(`${API_BASE}/rules/${projectId}`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(rule),
  });
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
  return fetchVoid(`${API_BASE}/rules/${projectId}?${params}`, { method: 'DELETE' });
}

/**
 * Get all tools with their current permission status for a project.
 */
export async function fetchToolPermissions(
  projectId: string
): Promise<ToolPermissionInfo[]> {
  return fetchJson<ToolPermissionInfo[]>(`${API_BASE}/tools/${projectId}`);
}

/**
 * Get effective permission settings for a project (defaults + user rules).
 */
export async function fetchPermissionSettings(
  projectId: string
): Promise<PermissionSettingsDto> {
  return fetchJson<PermissionSettingsDto>(`${API_BASE}/settings/${projectId}`);
}

/**
 * Update a single permission setting (checkbox toggle).
 */
export async function updatePermissionSetting(
  projectId: string,
  request: UpdateSettingRequest
): Promise<void> {
  return fetchVoid(`${API_BASE}/settings/${projectId}`, {
    method: 'PATCH',
    headers: JSON_HEADERS,
    body: JSON.stringify(request),
  });
}

/**
 * Get project directory structure for the file browser dropdown.
 */
export async function fetchProjectStructure(
  projectId: string
): Promise<FileNodeDto[]> {
  return fetchJson<FileNodeDto[]>(`${API_BASE}/project-structure/${projectId}`);
}

/**
 * Search files/folders by name within a project directory (recursive, real-time).
 * Returns a flat list of matching entries with absolute paths.
 */
export async function searchFiles(projectId: string, query: string): Promise<FileNodeDto[]> {
  const params = new URLSearchParams({ projectId, query });
  return fetchJson<FileNodeDto[]>(`${API_BASE}/search-files?${params}`);
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
  return fetchJson<FileNodeDto[]>(`${API_BASE}/browse-directory?${params}`);
}
