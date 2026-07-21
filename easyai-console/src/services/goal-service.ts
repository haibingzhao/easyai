/**
 * Goal management API service.
 *
 * Provides functions for managing goals via REST API:
 * - pause/resume active goals
 * - update goal objective text
 * - delete goals
 * - get current goal state
 */

import { authFetch } from './api-client';
import type { GoalStatusEvent } from '@/types/socket-event';

const API_BASE = '/api/chat/session';

/**
 * Get the current goal state for a session.
 * Returns null if no goal is set (404 response).
 */
export async function getGoal(sessionId: string): Promise<GoalStatusEvent | null> {
  const response = await authFetch(`${API_BASE}/${sessionId}/goal`);
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new Error(`Failed to get goal: ${response.statusText}`);
  }
  return response.json();
}

/**
 * Pause the active goal for a session.
 * Sets the goal status to PAUSED.
 */
export async function pauseGoal(sessionId: string): Promise<{ status: string; objective: string }> {
  const response = await authFetch(`${API_BASE}/${sessionId}/goal/pause`, {
    method: 'PUT',
  });
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Failed to pause goal: ${errorText || response.statusText}`);
  }
  return response.json();
}

/**
 * Resume a paused goal for a session.
 * Sets the goal status back to ACTIVE.
 */
export async function resumeGoal(sessionId: string): Promise<{ status: string; objective: string }> {
  const response = await authFetch(`${API_BASE}/${sessionId}/goal/resume`, {
    method: 'PUT',
  });
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Failed to resume goal: ${errorText || response.statusText}`);
  }
  return response.json();
}

/**
 * Update the goal objective text.
 * Allows refining the goal while it's active.
 */
export async function updateGoal(
  sessionId: string,
  objective: string
): Promise<{ status: string; objective: string }> {
  const response = await authFetch(`${API_BASE}/${sessionId}/goal`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ objective }),
  });
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Failed to update goal: ${errorText || response.statusText}`);
  }
  return response.json();
}

/**
 * Delete the goal for a session.
 * Removes the goal entirely.
 */
export async function deleteGoal(sessionId: string): Promise<{ status: string }> {
  const response = await authFetch(`${API_BASE}/${sessionId}/goal`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Failed to delete goal: ${errorText || response.statusText}`);
  }
  return response.json();
}
