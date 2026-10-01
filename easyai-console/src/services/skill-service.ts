import type { SkillInfo } from '@/types/agent';
import { authFetch, fetchJson, fetchVoid, JSON_HEADERS } from './api-client';

const API_BASE = '/api/skills';

/** The owner whose rows form the read-only shared layer every user sees. */
export const SHARED_OWNER_ID = 'system';

/**
 * Outcome of an install request. The server answers 409 for "name taken", 400 for "not a valid
 * skill" and 413 for "too large" — all with a readable message the caller must show, so these
 * statuses are results rather than thrown errors.
 */
export interface SkillInstallResult {
  status: number;
  skill: SkillInfo | null;
  message: string | null;
  warning: string | null;
}

export interface DirectoryInstallRequest {
  name: string;
  sourcePath: string;
  shared: boolean;
}

export interface UploadInstallRequest {
  name: string;
  shared: boolean;
  /** A zip archive, or the folder's files paired with their paths relative to the skill directory. */
  archive?: File;
  files?: File[];
  paths?: string[];
}

interface SkillAddResponseBody {
  skill?: SkillInfo;
  message?: string;
  warning?: string;
}

export class SkillService {
  static async list(signal?: AbortSignal): Promise<SkillInfo[]> {
    return fetchJson<SkillInfo[]>(API_BASE, { signal });
  }

  static async installFromDirectory(request: DirectoryInstallRequest): Promise<SkillInstallResult> {
    return sendInstall(API_BASE, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  static async installFromUpload(request: UploadInstallRequest): Promise<SkillInstallResult> {
    const form = new FormData();
    form.append('name', request.name);
    form.append('shared', String(request.shared));
    if (request.archive) {
      form.append('archive', request.archive);
    } else {
      (request.files ?? []).forEach((file) => form.append('files', file));
      form.append('paths', JSON.stringify(request.paths ?? []));
    }
    // No headers: the browser must set Content-Type with the multipart boundary.
    return sendInstall(`${API_BASE}/upload`, { method: 'POST', body: form });
  }

  static async setEnabled(name: string, enabled: boolean): Promise<{ name: string; enabled: boolean; indexSynced: boolean }> {
    return fetchJson(`${API_BASE}/enabled`, {
      method: 'PATCH',
      headers: JSON_HEADERS,
      body: JSON.stringify({ name, enabled }),
    });
  }

  static async remove(name: string): Promise<void> {
    return fetchVoid(`${API_BASE}/${encodeURIComponent(name)}`, { method: 'DELETE' });
  }
}

async function sendInstall(url: string, init: RequestInit): Promise<SkillInstallResult> {
  const response = await authFetch(url, init);
  const body = await response.json().catch(() => null) as SkillAddResponseBody | null;
  if (response.ok) {
    return { status: response.status, skill: body?.skill ?? null, message: null, warning: body?.warning ?? null };
  }
  // 401 is an expired session, not a verdict on the skill: let it throw like every other service.
  const refusal = (response.status >= 400 && response.status < 500 && response.status !== 401 && response.status !== 404)
    ? response.status
    : 0;
  if (refusal) {
    return {
      status: refusal,
      skill: null,
      message: body?.message || defaultMessage(refusal),
      warning: null,
    };
  }
  throw new Error(body?.message || `Install failed: ${response.status}`);
}

function defaultMessage(status: number): string {
  if (status === 403) return 'Only the shared system owner can publish shared skills';
  if (status === 413) return 'The skill package is too large';
  return 'The skill could not be installed';
}
