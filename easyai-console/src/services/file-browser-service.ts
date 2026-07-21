import { authFetch } from '@/services/api-client';

const API_BASE = '/api/permission';

export interface FileContentResponse {
  content: string;
  mimeType: string;
  size: number;
}

/**
 * Read file content from the server filesystem.
 * Path must be within the specified project directory.
 * Limited to files ≤ 1MB on the backend.
 */
export async function readFileContent(absolutePath: string, projectId: string): Promise<FileContentResponse> {
  const response = await authFetch(
    `${API_BASE}/read-file-content?path=${encodeURIComponent(absolutePath)}&projectId=${encodeURIComponent(projectId)}`
  );
  if (!response.ok) {
    throw new Error(`Failed to read file: ${response.status}`);
  }
  return response.json();
}
