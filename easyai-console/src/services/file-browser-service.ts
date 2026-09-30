import { authFetch, getAccessToken } from '@/services/api-client';

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

/** Image file extensions served by the media endpoint */
export const IMAGE_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'avif', 'ico']);
/** Audio file extensions */
export const AUDIO_EXTS = new Set(['mp3', 'wav', 'ogg', 'oga', 'aac', 'm4a', 'flac']);
/** Video file extensions */
export const VIDEO_EXTS = new Set(['mp4', 'webm', 'mov', 'm4v', 'avi', 'mkv']);

/**
 * URL for streaming a media file (image/audio/video) from the project filesystem.
 * The backend answers Range requests, so browsers fetch only the bytes they need.
 * Auth travels as the `token` query parameter because media elements cannot set headers.
 */
export function mediaFileUrl(absolutePath: string, projectId: string): string {
  const params = new URLSearchParams({ path: absolutePath, projectId });
  const token = getAccessToken();
  if (token) {
    params.set('token', token);
  }
  return `${API_BASE}/serve-media?${params.toString()}`;
}
