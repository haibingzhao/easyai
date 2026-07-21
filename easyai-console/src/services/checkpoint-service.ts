import type { FileDiff, RevertResponse, UnrevertResponse, RevertStateInfo, CheckpointInfo, FileReviewResponse, FileReviewStateResponse, EditMessageResponse, BatchFileReviewResponse, CommitChangeInfo } from '../types/checkpoint';
import { authFetch } from '@/services/api-client';

const API_BASE = '/api/chat';

/**
 * Get checkpoint summaries for a session.
 * Used to restore file change information when loading a historical session.
 */
export async function getCheckpoints(sessionId: string): Promise<CheckpointInfo[]> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/checkpoints`);
  if (!response.ok) {
    throw new Error(`Get checkpoints failed: ${response.status}`);
  }
  return response.json();
}

/**
 * Revert files to the state before the specified message's changes.
 */
export async function revertToMessage(sessionId: string, messageId: string): Promise<RevertResponse> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/revert`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messageId }),
  });
  if (!response.ok) {
    throw new Error(`Revert failed: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

/**
 * Unrevert: restore files to the state before the revert.
 */
export async function unrevert(sessionId: string): Promise<UnrevertResponse> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/unrevert`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
  });
  if (!response.ok) {
    throw new Error(`Unrevert failed: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

/**
 * Get session-level file diff summary.
 */
export async function getSessionDiff(sessionId: string): Promise<FileDiff[]> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/diff`);
  if (!response.ok) {
    throw new Error(`Get session diff failed: ${response.status}`);
  }
  return response.json();
}

/**
 * Get diff for a specific checkpoint.
 */
export async function getCheckpointDiff(sessionId: string, messageId: string): Promise<FileDiff[]> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/checkpoint/${messageId}/diff`);
  if (!response.ok) {
    throw new Error(`Get checkpoint diff failed: ${response.status}`);
  }
  return response.json();
}

/**
 * Get the current revert state for a session.
 */
export async function getRevertState(sessionId: string): Promise<RevertStateInfo | null> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/revert-state`);
  if (!response.ok) {
    throw new Error(`Get revert state failed: ${response.status}`);
  }
  return response.json();
}

/**
 * Accept a file: persist review state (file remains unchanged).
 */
export async function acceptFile(sessionId: string, filePath: string): Promise<FileReviewResponse> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/file-accept`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filePath }),
  });
  if (!response.ok) {
    throw new Error(`Accept file failed: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

/**
 * Reject a file: persist review state + restore file from git history.
 */
export async function rejectFile(sessionId: string, filePath: string): Promise<FileReviewResponse> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/file-reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filePath }),
  });
  if (!response.ok) {
    throw new Error(`Reject file failed: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

/**
 * Batch accept multiple files atomically.
 * All review states are updated in a single read-modify-write operation.
 */
export async function batchAcceptFiles(sessionId: string, filePaths: string[]): Promise<BatchFileReviewResponse> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/file-accept-batch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filePaths }),
  });
  if (!response.ok) {
    throw new Error(`Batch accept files failed: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

/**
 * Batch reject multiple files atomically.
 * File restoration + review state update in a single operation.
 */
export async function batchRejectFiles(sessionId: string, filePaths: string[]): Promise<BatchFileReviewResponse> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/file-reject-batch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filePaths }),
  });
  if (!response.ok) {
    throw new Error(`Batch reject files failed: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

/**
 * Get file review states for a session.
 */
export async function getFileReviewState(sessionId: string): Promise<FileReviewStateResponse | null> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/file-review`);
  if (!response.ok) {
    throw new Error(`Get file review state failed: ${response.status}`);
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
  const response = await authFetch(`${API_BASE}/session/${sessionId}/edit-message`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messageId }),
  });
  if (!response.ok) {
    throw new Error(`Edit message failed: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

/**
 * Get commit history for a session with per-commit diffs and author attribution.
 * Used by the per-commit view in the Review panel.
 */
export async function getSessionCommitHistory(sessionId: string): Promise<CommitChangeInfo[]> {
  const response = await authFetch(`${API_BASE}/session/${sessionId}/commit-history`);
  if (!response.ok) throw new Error(`Get commit history failed: ${response.status}`);
  return response.json();
}
