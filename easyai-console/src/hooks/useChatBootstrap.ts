import { useEffect } from 'react';
import { useAgentStore } from '@/services/stores/agent-store';
import { useChatStore } from '@/services/stores/chat-store';
import { getSnapshotCapability } from '@/services/checkpoint-service';

/**
 * Loads everything a conversation view needs before it can render meaningfully: the agent list,
 * the Chat agent preference (default + hidden), the tool catalogue, the backend's snapshot
 * capability, and the active session's goal.
 *
 * The snapshot capability is probed once at mount — it gates the Review panel from startup,
 * including the no-session state — and a failed probe keeps the optimistic default rather than
 * silently disabling file review.
 *
 * Shared by `ChatPanel` and `MobileChatPage` so a second surface cannot forget one of these loads.
 */
export function useChatBootstrap(): void {
  const loadAgents = useAgentStore((state) => state.loadAgents);
  const loadTools = useAgentStore((state) => state.loadTools);
  const loadChatPreference = useAgentStore((state) => state.loadChatPreference);
  const sessionId = useChatStore((state) => state.sessionId);
  const refreshGoal = useChatStore((state) => state.refreshGoal);

  useEffect(() => {
    loadAgents();
    loadTools();
    loadChatPreference();
  }, [loadAgents, loadTools, loadChatPreference]);

  useEffect(() => {
    getSnapshotCapability()
      .then((v) => useChatStore.getState().setSnapshotCapability(v))
      .catch(() => { /* probe unavailable — keep the optimistic default */ });
  }, []);

  useEffect(() => {
    if (!sessionId) return;
    refreshGoal(sessionId).catch(() => {
      // Silently ignore — no goal for this session
    });
  }, [sessionId, refreshGoal]);
}
