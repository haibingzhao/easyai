import { useCallback, useEffect, useRef } from 'react';
import { sessionService } from '@/services/session-service';
import { getStreamingStatus } from '@/services/chat-service';
import { getCheckpoints, getFileReviewState } from '@/services/checkpoint-service';
import { adoptSessionWorkspace } from '@/services/stores/project-store';
import { useChatStore } from '@/services/stores/chat-store';
import type { CheckpointInfo } from '@/types/checkpoint';

/** Poll cadence while a session is still running. */
const RUNNING_POLL_MS = 3000;
/** Poll cadence after a failure. */
const RETRY_POLL_MS = 5000;
/** Checkpoint refresh cadence — surfaces team member file changes that never reach the parent stream. */
const CHECKPOINT_REFRESH_MS = 8000;

export interface SessionRecovery {
  /** Re-run one poll immediately. iOS suspends in-flight fetches in the background, so a phone
   *  returning to the foreground must not wait out the 3s tick. */
  pollNow: () => void;
}

/**
 * Polls a *running* session back into shape from the backend's committed state.
 *
 * This is the single source of truth for recovery: DB snapshots plus incremental merges, never a
 * re-attached SSE stream. Attaching a watch stream here would double-render committed turns
 * (messages copy plus streaming copy of the same turn), which is exactly the bug this path replaced.
 *
 * Extracted from `ChatPanel` so the mobile shell gets identical behaviour — it matters more there,
 * because iOS suspends the stream the moment the app leaves the foreground.
 */
export function useSessionRecovery(runningSessionId: string | null): SessionRecovery {
  const pollRef = useRef<(() => Promise<void>) | null>(null);

  const pollNow = useCallback(() => {
    void pollRef.current?.();
  }, []);

  useEffect(() => {
    if (!runningSessionId) {
      pollRef.current = null;
      return;
    }

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const sid = runningSessionId;

    /**
     * Fetch incremental messages from the server.
     * Tries incremental fetch first; falls back to full reload if compaction,
     * in-place message updates, or errors are detected.
     * @param finalize If true, clears running state after recovery.
     * @returns true if a pending permission request is active (caller should keep polling).
     */
    const fetchMessagesIncremental = async (finalize: boolean): Promise<boolean> => {
      const state = useChatStore.getState();
      const lastTimestamp = state._lastSnapshots.reduce((max, s) => Math.max(max, s.timestamp), 0);
      const [checkpoints, groupedTodos, reviewState] = await Promise.all([
        getCheckpoints(sid).catch(() => [] as CheckpointInfo[]),
        sessionService.getGroupedTodos(sid).catch(() => ({ main: [], subAgents: [] })),
        getFileReviewState(sid).catch(() => null),
      ]);
      if (cancelled) return false;

      let hasPendingPermission = false;
      try {
        const afterResponse = await sessionService.getSessionMessagesAfter(sid, lastTimestamp);
        if (cancelled) return false;

        hasPendingPermission = !!afterResponse.pendingPermission;

        if (afterResponse.compactionOccurredAfter || afterResponse.contentUpdatedAt > lastTimestamp) {
          // Compaction or in-place message update detected — fall back to full reload
          const detail = await sessionService.getSessionDetail(sid);
          if (cancelled) return false;
          hasPendingPermission = !!detail?.pendingPermission;
          if (detail) {
            const s = useChatStore.getState();
            s.loadSessionMessages(detail.messages, detail.pendingPermission, checkpoints, detail.endReason, detail.variables, detail.modelContextLength, sid);
            s.setSnapshotEnabled(detail.snapshotEnabled !== false);
            adoptSessionWorkspace(detail);
          }
        } else {
          // Safe to use incremental merge — pass pendingPermission from backend
          state.loadSessionMessagesIncremental(afterResponse.messages, afterResponse.pendingPermission ?? null, checkpoints, afterResponse.endReason, undefined, undefined, sid);
        }
      } catch {
        // Incremental fetch failed — fall back to full reload
        const detail = await sessionService.getSessionDetail(sid);
        if (cancelled) return false;
        hasPendingPermission = !!detail?.pendingPermission;
        if (detail) {
          const s = useChatStore.getState();
          s.loadSessionMessages(detail.messages, detail.pendingPermission, checkpoints, detail.endReason, detail.variables, detail.modelContextLength, sid);
          s.setSnapshotEnabled(detail.snapshotEnabled !== false);
          adoptSessionWorkspace(detail);
        }
      }

      state.setTodos(groupedTodos.main);
      state.setAllSubAgentTodos(
        Object.fromEntries(groupedTodos.subAgents.map((g) => [g.agentName, { todos: g.todos, toolCallId: g.agentName }]))
      );
      if (reviewState?.reviews) {
        state.setFileReviewOverrides(reviewState.reviews);
      }
      if (finalize && !hasPendingPermission) {
        // Only finalize when there is no pending permission.
        // When the SSE stream ended but the agent is paused for a permission request,
        // we must keep polling so the PermissionBar stays interactive.
        state.setRunningSessionId(null);
        state.setStreaming(false);
      }
      return hasPendingPermission;
    };

    const poll = async (): Promise<void> => {
      try {
        const status = await getStreamingStatus(sid);
        if (cancelled) return;

        if (!status.streaming && !status.local) {
          // Session is completely done — final fetch and stop polling
          await fetchMessagesIncremental(true);
          return;
        }

        if (status.local && status.streaming) {
          // Still running — fetch incremental messages and poll again
          await fetchMessagesIncremental(false);
          if (!cancelled) timer = setTimeout(poll, RUNNING_POLL_MS);
          return;
        }

        // e.g. local but not streaming (permission pause) —
        // fetch final messages, keep polling only if there's a pending permission
        const hasPendingPermission = await fetchMessagesIncremental(true);
        if (hasPendingPermission && !cancelled) {
          // Agent is paused for permission — keep polling to detect state changes
          timer = setTimeout(poll, RUNNING_POLL_MS);
        }
      } catch (e) {
        console.warn('[SSE Recovery] Poll failed:', e);
        if (!cancelled) timer = setTimeout(poll, RETRY_POLL_MS);
      }
    };

    pollRef.current = poll;
    void poll();

    // Ownership guard: once the user switches sessions, the store's runningSessionId no
    // longer matches the session polled by this effect.
    const isStalePoll = () => cancelled || useChatStore.getState().runningSessionId !== sid;

    // Periodic checkpoint refresh — surfaces team member file changes
    // that don't flow through the parent's stream (members use isolated session IDs).
    const checkpointTimer = setInterval(async () => {
      if (isStalePoll()) return;
      try {
        const checkpoints = await getCheckpoints(sid);
        if (cancelled) return;
        const state = useChatStore.getState();
        for (const cp of checkpoints) {
          const key = cp.assistantMessageId || cp.messageId;
          if (!key) continue;
          // Upsert synthetic member checkpoints (their content grows as members write more files);
          // only add genuinely new keys for real checkpoints.
          if (!state.checkpointsByMessageId[key] || key.startsWith('member:')) {
            state.addCheckpoint(key, cp);
          }
        }
      } catch {
        /* best-effort */
      }
    }, CHECKPOINT_REFRESH_MS);

    return () => {
      cancelled = true;
      pollRef.current = null;
      if (timer) clearTimeout(timer);
      clearInterval(checkpointTimer);
    };
  }, [runningSessionId]);

  return { pollNow };
}
