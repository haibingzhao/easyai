import { useCallback, useEffect, useRef, useState } from 'react';
import { sessionService } from '@/services/session-service';
import { getStreamingStatus, watchSession } from '@/services/chat-service';
import { getCheckpoints, getFileReviewState } from '@/services/checkpoint-service';
import { adoptSessionWorkspace } from '@/services/stores/project-store';
import { useChatStore } from '@/services/stores/chat-store';
import { handleWatchEvent } from '@/services/stores/chat/watch-handler';
import { APP_CONFIG } from '@/constants/navigation';

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
  /** Poll now *and* re-attach the watch tap. A phone returning to the foreground lost the watch
   *  connection along with its fetches, so it must re-subscribe to see the in-flight round live
   *  rather than waiting for the next committed round to reach the poll. */
  reattach: () => void;
}

/**
 * Recovers a *running* session from two complementary sources.
 *
 * The DB poll is the single source of truth for **committed** rounds: snapshots plus incremental
 * merges. It cannot show an in-flight round, because the backend commits a round only once, when
 * its LLM call returns (`AgentLoop` persists the assistant message after the whole round) — so a
 * 50s thinking phase leaves the poll with nothing new and the transcript frozen until it lands.
 *
 * To cover that gap we also attach the backend's watch tap (`POST /session/{id}/events`), which
 * broadcasts the live round. Its events are reduced into an **isolated** `livePreview` (never the
 * committed `messages`, never `commitStreamingMessage`), and rendered only while their `messageId`
 * is absent from `messages`. That keying makes a committed round and its live preview mutually
 * exclusive, so re-attaching cannot double-render committed turns — the bug a naive watch stream
 * used to cause.
 *
 * Extracted from `ChatPanel` so the mobile shell gets identical behaviour — it matters more there,
 * because iOS suspends both the fetch and the watch stream the moment the app leaves the foreground.
 */
export function useSessionRecovery(runningSessionId: string | null): SessionRecovery {
  const pollRef = useRef<(() => Promise<void>) | null>(null);
  /** Bumped by {@link SessionRecovery.reattach} to force the watch effect to re-subscribe. */
  const [attachNonce, setAttachNonce] = useState(0);

  const pollNow = useCallback(() => {
    void pollRef.current?.();
  }, []);

  const reattach = useCallback(() => {
    pollNow();
    setAttachNonce((n) => n + 1);
  }, [pollNow]);

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
      // The cursor is the newest *seq* the store holds, not the newest timestamp: two rows can
      // share a millisecond, and the one that lost the comparison was never fetched again.
      const cursor = state.messageCursor ?? 0;
      const knownRevision = state.contentRevision;
      // No Review tab means no consumer for either payload — don't poll the snapshot system.
      //
      // The tab's visibility is `showWorkspacePanels && snapshotEnabled && snapshotCapability`
      // (see RightPanel); probing the host flag alone is not enough. Hosts that register a no-op
      // snapshot service (trading does, in TradingSnapshotConfiguration) keep the host flag true
      // for the Files tab while the global probe reports false, so this used to fire two requests
      // every poll for a payload nothing could render.
      const reviewVisible = APP_CONFIG.showWorkspacePanels && state.snapshotCapability;
      const [checkpointResponse, groupedTodos, reviewState] = await Promise.all([
        // `null` = the request failed. Only a real listing may be applied: a failure must not
        // masquerade as "no files were changed", which is what the edit gate reads.
        reviewVisible ? getCheckpoints(sid).catch(() => null) : Promise.resolve(null),
        sessionService.getGroupedTodos(sid).catch(() => ({ main: [], subAgents: [] })),
        reviewVisible ? getFileReviewState(sid).catch(() => null) : Promise.resolve(null),
      ]);
      if (cancelled) return false;

      const checkpoints = checkpointResponse?.status === 'ok' ? checkpointResponse.checkpoints : undefined;
      /** Publish the listing (including `disabled`/`failed`) after the message load has landed. */
      const applyCheckpoints = () => {
        if (checkpointResponse) useChatStore.getState().applyCheckpointResponse(checkpointResponse);
      };

      /** Full reload — the only safe path once history itself has been rewritten. */
      const reloadAll = async (): Promise<boolean | null> => {
        const detail = await sessionService.getSessionDetail(sid);
        if (cancelled) return null;
        if (!detail) return null;
        const s = useChatStore.getState();
        s.loadSessionMessages(detail.messages, detail.pendingPermission, checkpoints, detail.endReason, detail.variables, detail.modelContextLength, sid);
        s.setSnapshotEnabled(detail.snapshotEnabled !== false);
        s.applySessionCursors({
          newestSeq: detail.newestMessageSeq,
          contentRevision: detail.contentRevision,
        });
        adoptSessionWorkspace(detail);
        return !!detail.pendingPermission;
      };

      let hasPendingPermission = false;
      try {
        const afterResponse = await sessionService.getSessionMessagesAfterSeq(sid, cursor);
        if (cancelled) return false;

        hasPendingPermission = !!afterResponse.pendingPermission;

        // A rewrite (edit / delete / truncate / compact / revert) means the rows we hold are no
        // longer a prefix of the transcript, so a delta could be stitched onto a lie. The
        // revision only moves on rewrites, making it the precise test the timestamp stood in for.
        const rewritten = afterResponse.compactionOccurredAfter
          || (afterResponse.revision != null && knownRevision != null && afterResponse.revision !== knownRevision);

        if (rewritten) {
          const reloaded = await reloadAll();
          if (cancelled) return false;
          if (reloaded !== null) hasPendingPermission = reloaded;
        } else {
          // Safe to use incremental merge — pass pendingPermission from backend
          useChatStore.getState().loadSessionMessagesIncremental(
            afterResponse.messages, afterResponse.pendingPermission ?? null, checkpoints, afterResponse.endReason, undefined, undefined, sid,
          );
          useChatStore.getState().applySessionCursors({
            newestSeq: afterResponse.newestSeq,
            contentRevision: afterResponse.revision,
          });
        }
      } catch {
        // Incremental fetch failed — fall back to full reload
        const reloaded = await reloadAll();
        if (cancelled) return false;
        if (reloaded !== null) hasPendingPermission = reloaded;
      }

      applyCheckpoints();

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
    //
    // The capability check lives inside the tick, not around `setInterval`: the probe that fills
    // `snapshotCapability` is async (useChatBootstrap), so at effect time it still holds the
    // optimistic default. Checking per tick means a host with a no-op snapshot service stops
    // issuing requests as soon as the probe lands, without tearing the timer down.
    const checkpointTimer = APP_CONFIG.showWorkspacePanels
      ? setInterval(async () => {
          if (isStalePoll()) return;
          if (!useChatStore.getState().snapshotCapability) return;
          try {
            const response = await getCheckpoints(sid);
            if (cancelled) return;
            // A disabled or failed listing carries no usable entries; the poll's own load is
            // what publishes those states, so this best-effort upsert only acts on `ok`.
            if (response.status !== 'ok') return;
            const state = useChatStore.getState();
            for (const cp of response.checkpoints) {
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
        }, CHECKPOINT_REFRESH_MS)
      : null;

    return () => {
      cancelled = true;
      pollRef.current = null;
      if (timer) clearTimeout(timer);
      if (checkpointTimer) clearInterval(checkpointTimer);
    };
  }, [runningSessionId]);

  // Watch tap: attach to the backend's live broadcast for the in-flight round while a recovery is
  // active. Gated on `local` — the tap only exists on the instance running the session, and the
  // backend answers a non-local attach with an immediate `not_streaming` done anyway. The poll stays
  // the backbone (committed rounds, checkpoints, todos, permissions) and the fallback if the watch
  // is unavailable or drops; on any watch ending we clear the preview and let the poll reconcile.
  useEffect(() => {
    if (!runningSessionId) return;
    const sid = runningSessionId;
    let cancelled = false;
    let handle: { abort: () => void } | null = null;

    void (async () => {
      try {
        const status = await getStreamingStatus(sid);
        if (cancelled || !status.local) return;
        const watch = watchSession(sid, {
          onEvent: (event) => {
            if (!cancelled) handleWatchEvent(event);
          },
          onDone: () => {
            if (cancelled) return;
            useChatStore.getState().clearLivePreview();
            pollNow();
          },
          onError: () => {
            if (cancelled) return;
            useChatStore.getState().clearLivePreview();
            pollNow();
          },
        });
        handle = watch;
        // The cleanup can run while `getStreamingStatus` is still in flight and find no handle to
        // close, so the attach is released here too — otherwise it leaks one SSE stream per effect
        // that unmounted before its status request came back.
        if (cancelled) watch.abort();
      } catch {
        /* watch unavailable — poll-only recovery continues */
      }
    })();

    return () => {
      cancelled = true;
      handle?.abort();
      useChatStore.getState().clearLivePreview();
    };
  }, [runningSessionId, attachNonce, pollNow]);

  return { pollNow, reattach };
}
