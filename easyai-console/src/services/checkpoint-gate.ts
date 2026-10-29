import { getCheckpoints } from '@/services/checkpoint-service';
import { currentEpoch, isCurrentEpoch } from '@/services/session-epoch';
import { useChatStore } from '@/services/stores/chat-store';
import type { CheckpointInfo, CheckpointState } from '@/types/checkpoint';

let pendingLoad: { sessionId: string; epoch: number; promise: Promise<CheckpointState> } | null = null;

// Editing must distinguish an unloaded or failed listing from a successful empty one.
export async function ensureCheckpointsReady(sessionId: string): Promise<CheckpointState> {
  const store = useChatStore.getState();
  if (store.sessionId !== sessionId) return 'unknown';
  const epoch = currentEpoch();
  if (pendingLoad?.sessionId === sessionId && pendingLoad.epoch === epoch) {
    return pendingLoad.promise;
  }
  const state = store.checkpointState;
  if (state === 'ok' || state === 'disabled') return state;

  const current = () => isCurrentEpoch(epoch) && useChatStore.getState().sessionId === sessionId;
  store.setCheckpointState('loading');
  const promise = (async (): Promise<CheckpointState> => {
    try {
      const response = await getCheckpoints(sessionId);
      if (!current()) return 'unknown';
      useChatStore.getState().applyCheckpointResponse(response);
    } catch (error) {
      if (!current()) return 'unknown';
      useChatStore.getState().setCheckpointState(
        'failed',
        error instanceof Error ? error.message : 'Failed to load checkpoints',
      );
    }
    return useChatStore.getState().checkpointState;
  })();
  const request = { sessionId, epoch, promise };
  pendingLoad = request;
  try {
    return await promise;
  } finally {
    if (pendingLoad === request) pendingLoad = null;
  }
}

export function getEditRevertFiles(
  checkpoints: CheckpointInfo[],
  firstMessageId: string,
): { path: string; additions: number }[] {
  const files = new Map<string, number>();
  for (const checkpoint of checkpoints.slice(0, indexOfCheckpoint(checkpoints, firstMessageId) + 1)) {
    for (const file of checkpoint.filesChanged) {
      files.set(file.path, (files.get(file.path) ?? 0) + file.additions);
    }
  }
  return Array.from(files, ([path, additions]) => ({ path, additions }))
    .sort((a, b) => a.path.localeCompare(b.path));
}

export interface EditGateResult {
  allowed: boolean;
  /** Present when blocked; shown to the user verbatim. */
  reason?: string;
}

/**
 * Decide whether a history-mutating action may proceed.
 *
 * @param checkpoints the checkpoints the caller intends to use (already loaded)
 * @param firstMessageId the message being edited/reverted — every checkpoint from it onwards is
 *   part of the rollback range, so an incomplete entry anywhere in that range blocks the action
 */
export function evaluateEditGate(
  state: CheckpointState,
  checkpoints: CheckpointInfo[],
  firstMessageId?: string,
): EditGateResult {
  if (state === 'disabled') {
    // The project has no snapshot system at all: there is no rollback semantics to protect.
    return { allowed: true };
  }
  if (state === 'loading' || state === 'unknown') {
    return { allowed: false, reason: 'File change information is still loading. Please try again.' };
  }
  if (state === 'failed') {
    return { allowed: false, reason: 'Could not verify which files would be rolled back.' };
  }

  const range = firstMessageId
    ? checkpoints.slice(0, indexOfCheckpoint(checkpoints, firstMessageId) + 1)
    : checkpoints;
  const incomplete = range.find((cp) => cp.diffsComplete === false);
  if (incomplete) {
    return {
      allowed: false,
      reason: 'The list of files changed in this turn could not be verified.',
    };
  }
  return { allowed: true };
}

/**
 * Checkpoints are newest-first. When the anchor is missing from the list (e.g. it has no
 * checkpoint), fall back to the whole list — a stricter range is the safe direction.
 */
function indexOfCheckpoint(checkpoints: CheckpointInfo[], messageId: string): number {
  const index = checkpoints.findIndex(
    (cp) => cp.messageId === messageId || cp.assistantMessageId === messageId,
  );
  return index < 0 ? checkpoints.length - 1 : index;
}
