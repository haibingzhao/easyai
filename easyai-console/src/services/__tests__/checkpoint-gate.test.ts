import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getCheckpoints } from '@/services/checkpoint-service';
import { ensureCheckpointsReady, evaluateEditGate, getEditRevertFiles } from '@/services/checkpoint-gate';
import { bumpSessionEpoch } from '@/services/session-epoch';
import { useChatStore } from '@/services/stores/chat-store';
import type { CheckpointInfo, CheckpointListResponse } from '@/types/checkpoint';

vi.mock('@/services/checkpoint-service', () => ({ getCheckpoints: vi.fn() }));

const getListing = vi.mocked(getCheckpoints);

function checkpoint(messageId: string, path = 'file.ts'): CheckpointInfo {
  return {
    messageId,
    assistantMessageId: `assistant-${messageId}`,
    filesChanged: [{ path, additions: 1, deletions: 0, status: 'added' }],
    additions: 1,
    deletions: 0,
    createdAt: 1,
    diffsComplete: true,
  };
}

function deferred() {
  let resolve!: (response: CheckpointListResponse) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<CheckpointListResponse>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

beforeEach(() => {
  vi.resetAllMocks();
  useChatStore.getState().clearChat();
  useChatStore.getState().setSessionId('A');
});

describe('checkpoint loading before editing', () => {
  it('uses newly loaded checkpoints for the confirmation file list, including the edited turn', async () => {
    const response = deferred();
    getListing.mockReturnValue(response.promise);
    const load = ensureCheckpointsReady('A');
    expect(useChatStore.getState().checkpointList).toEqual([]);
    response.resolve({ status: 'ok', checkpoints: [checkpoint('later'), checkpoint('edited')] });

    const state = await load;
    const checkpoints = useChatStore.getState().checkpointList;
    expect(evaluateEditGate(state, checkpoints, 'edited').allowed).toBe(true);
    expect(getEditRevertFiles(checkpoints, 'edited')).toEqual([{ path: 'file.ts', additions: 2 }]);
  });

  it('shares an in-flight request for the same session generation', async () => {
    const response = deferred();
    getListing.mockReturnValue(response.promise);
    const first = ensureCheckpointsReady('A');
    const second = ensureCheckpointsReady('A');
    expect(getListing).toHaveBeenCalledTimes(1);
    response.resolve({ status: 'ok', checkpoints: [] });
    expect(await Promise.all([first, second])).toEqual(['ok', 'ok']);
  });

  it('blocks after a failed load and allows a subsequent retry', async () => {
    getListing.mockRejectedValueOnce(new Error('offline'));
    expect(await ensureCheckpointsReady('A')).toBe('failed');
    expect(evaluateEditGate(useChatStore.getState().checkpointState, [], 'edited').allowed).toBe(false);
    expect(useChatStore.getState().checkpointError).toBe('offline');
    getListing.mockResolvedValueOnce({ status: 'ok', checkpoints: [] });
    expect(await ensureCheckpointsReady('A')).toBe('ok');
  });

  it.each(['ok', 'disabled'] as const)('reuses a trustworthy %s state', async (status) => {
    useChatStore.getState().applyCheckpointResponse({ status, checkpoints: [] });
    expect(await ensureCheckpointsReady('A')).toBe(status);
    expect(getListing).not.toHaveBeenCalled();
  });

  it('does not reuse another session listing', async () => {
    useChatStore.getState().applyCheckpointResponse({ status: 'ok', checkpoints: [] });
    expect(await ensureCheckpointsReady('B')).toBe('unknown');
    expect(getListing).not.toHaveBeenCalled();
  });

  it('discards a response after switching away and back to the same session', async () => {
    const response = deferred();
    getListing.mockReturnValue(response.promise);
    const load = ensureCheckpointsReady('A');
    useChatStore.getState().setSessionId('B');
    useChatStore.getState().setSessionId('A');
    response.resolve({ status: 'ok', checkpoints: [checkpoint('obsolete')] });
    expect(await load).toBe('unknown');
    expect(useChatStore.getState().checkpointState).toBe('unknown');
    expect(useChatStore.getState().checkpointList).toEqual([]);
  });

  it('does not clear a newer in-flight request when the old request fails', async () => {
    const oldResponse = deferred();
    const newResponse = deferred();
    getListing.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise);
    const oldLoad = ensureCheckpointsReady('A');
    bumpSessionEpoch();
    const newLoad = ensureCheckpointsReady('A');
    oldResponse.reject(new Error('old failure'));
    expect(await oldLoad).toBe('unknown');
    expect(useChatStore.getState().checkpointState).toBe('loading');
    const sharedLoad = ensureCheckpointsReady('A');
    expect(getListing).toHaveBeenCalledTimes(2);
    newResponse.resolve({ status: 'ok', checkpoints: [checkpoint('current')] });
    expect(await Promise.all([newLoad, sharedLoad])).toEqual(['ok', 'ok']);
    expect(useChatStore.getState().checkpointList[0].messageId).toBe('current');
  });
});

describe('edit rollback scope', () => {
  it('excludes older checkpoints and sorts and deduplicates affected paths', () => {
    const entries = [checkpoint('later', 'b.ts'), checkpoint('edited', 'a.ts'), checkpoint('older', 'old.ts')];
    expect(getEditRevertFiles(entries, 'edited')).toEqual([
      { path: 'a.ts', additions: 1 }, { path: 'b.ts', additions: 1 },
    ]);
  });

  it('blocks an incomplete checkpoint on the edited turn', () => {
    const entries = [{ ...checkpoint('edited'), diffsComplete: false }];
    expect(evaluateEditGate('ok', entries, 'edited').allowed).toBe(false);
    expect(evaluateEditGate('ok', entries, 'assistant-edited').allowed).toBe(false);
  });

  it('does not block for an incomplete checkpoint before the rollback range', () => {
    const entries = [checkpoint('edited'), { ...checkpoint('older'), diffsComplete: false }];
    expect(evaluateEditGate('ok', entries, 'edited').allowed).toBe(true);
  });

  it('keeps verification and confirmation conservative when the anchor is absent', () => {
    const entries = [{ ...checkpoint('older'), diffsComplete: false }];
    expect(evaluateEditGate('ok', entries, 'missing').allowed).toBe(false);
    expect(getEditRevertFiles(entries, 'missing')).toEqual([{ path: 'file.ts', additions: 1 }]);
  });
});
