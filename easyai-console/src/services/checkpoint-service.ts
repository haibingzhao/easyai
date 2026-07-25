import type { FileDiff, RevertResponse, UnrevertResponse, RevertStateInfo, CheckpointInfo, FileReviewResponse, FileReviewStateResponse, EditMessageResponse, BatchFileReviewResponse, CommitChangeInfo } from '../types/checkpoint';
import { authFetch, fetchJson, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/chat';

/**
 * Get checkpoint summaries for a session.
 * Used to restore file change information when loading a historical session.
 */
export async function getCheckpoints(sessionId: string): Promise<CheckpointInfo[]> {
  return fetchJson<CheckpointInfo[]>(`${API_BASE}/session/${sessionId}/checkpoints`);
}

/**
 * Revert files to the state before the specified message's changes.
 */
export async function revertToMessage(sessionId: string, messageId: string): Promise<RevertResponse> {
  return fetchJson<RevertResponse>(`${API_BASE}/session/${sessionId}/revert`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ messageId }),
  });
}

/**
 * Unrevert: restore files to the state before the revert.
 */
export async function unrevert(sessionId: string): Promise<UnrevertResponse> {
  return fetchJson<UnrevertResponse>(`${API_BASE}/session/${sessionId}/unrevert`, {
    method: 'POST',
    headers: JSON_HEADERS,
  });
}

/**
 * Get session-level file diff summary.
 */
export async function getSessionDiff(sessionId: string): Promise<FileDiff[]> {
  return fetchJson<FileDiff[]>(`${API_BASE}/session/${sessionId}/diff`);
}

/**
 * Get diff for a specific checkpoint.
 */
export async function getCheckpointDiff(sessionId: string, messageId: string): Promise<FileDiff[]> {
  return fetchJson<FileDiff[]>(`${API_BASE}/session/${sessionId}/checkpoint/${messageId}/diff`);
}

/**
 * Get the current revert state for a session.
 */
export async function getRevertState(sessionId: string): Promise<RevertStateInfo | null> {
  return fetchJson<RevertStateInfo | null>(`${API_BASE}/session/${sessionId}/revert-state`);
}

/**
 * Accept a file: persist review state (file remains unchanged).
 */
export async function acceptFile(sessionId: string, filePath: string): Promise<FileReviewResponse> {
  return fetchJson<FileReviewResponse>(`${API_BASE}/session/${sessionId}/file-accept`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ filePath }),
  });
}

/**
 * Reject a file: persist review state + restore file from git history.
 */
export async function rejectFile(sessionId: string, filePath: string): Promise<FileReviewResponse> {
  return fetchJson<FileReviewResponse>(`${API_BASE}/session/${sessionId}/file-reject`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ filePath }),
  });
}

/**
 * Batch accept multiple files atomically.
 * All review states are updated in a single read-modify-write operation.
 */
export async function batchAcceptFiles(sessionId: string, filePaths: string[]): Promise<BatchFileReviewResponse> {
  return fetchJson<BatchFileReviewResponse>(`${API_BASE}/session/${sessionId}/file-accept-batch`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ filePaths }),
  });
}

/**
 * Batch reject multiple files atomically.
 * File restoration + review state update in a single operation.
 */
export async function batchRejectFiles(sessionId: string, filePaths: string[]): Promise<BatchFileReviewResponse> {
  return fetchJson<BatchFileReviewResponse>(`${API_BASE}/session/${sessionId}/file-reject-batch`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ filePaths }),
  });
}

/**
 * Get file review states for a session.
 */
export async function getFileReviewState(sessionId: string): Promise<FileReviewStateResponse | null> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/file-review`);
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(body || `Request failed: ${response.status}`);
  }
  // Handle empty response body (e.g. 204 No Content or 200 with no body)
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/**
 * Edit a message: delete the message and all subsequent messages, rollback files.
 * The new message content should be sent via the regular sendMessage flow afterwards.
 */
export async function editMessage(sessionId: string, messageId: string): Promise<EditMessageResponse> {
  return fetchJson<EditMessageResponse>(`${API_BASE}/session/${sessionId}/edit-message`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ messageId }),
  });
}

/**
 * Get commit history for a session with per-commit diffs and author attribution.
 * Used by the per-commit view in the Review panel.
 */
export async function getSessionCommitHistory(sessionId: string): Promise<CommitChangeInfo[]> {
  return fetchJson<CommitChangeInfo[]>(`${API_BASE}/session/${sessionId}/commit-history`);
}
