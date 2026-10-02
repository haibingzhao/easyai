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

/** MIME types for image extensions served by the media endpoint */
const IMAGE_MIME_BY_EXT: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  svg: 'image/svg+xml',
  avif: 'image/avif',
  ico: 'image/x-icon',
};

/**
 * Load a project file from the server filesystem as a browser File object,
 * so it can flow through the same attachment pipeline as a locally picked file.
 * Images are fetched as binary via the media endpoint; other files are read as text.
 */
export async function pathToFile(absolutePath: string, projectId: string): Promise<File> {
  const name = absolutePath.split('/').pop() || absolutePath;
  const ext = name.includes('.') ? name.split('.').pop()!.toLowerCase() : '';
  const imageMime = IMAGE_MIME_BY_EXT[ext];
  if (imageMime) {
    const response = await authFetch(mediaFileUrl(absolutePath, projectId));
    if (!response.ok) throw new Error(`Failed to read file: ${response.status}`);
    return new File([await response.blob()], name, { type: imageMime });
  }
  const { content, mimeType } = await readFileContent(absolutePath, projectId);
  return new File([content], name, { type: mimeType || 'text/plain' });
}
