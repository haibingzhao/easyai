import { useMemo } from 'react';
import { useChatStore } from '@/services/stores/chat-store';
import { useSessionStore } from '@/services/stores/session-store';
import type { SessionListItem } from '@/services/session-service';

/**
 * The session list as it should be displayed: the backend's page of sessions, with the
 * currently open chat session's live streaming state overlaid on top.
 *
 * Two effects this covers that a plain backend fetch cannot:
 * - the "Running" dot appears the moment the user sends, without waiting for a refetch;
 * - a brand-new session is not in the list yet, so it is synthesized at the top from its first
 *   user message and pinned there while it streams.
 *
 * Extracted from `SessionsTab` because the mobile shell needs the same view in two places
 * (history page and the header's quick-switch sheet), and two copies of this rule would drift.
 */
export function useDisplaySessions(): SessionListItem[] {
  const remoteSessions = useSessionStore((state) => state.remoteSessions);
  const activeTagFilter = useSessionStore((state) => state.activeTagFilter);
  const chatSessionId = useChatStore((state) => state.sessionId);
  const isStreaming = useChatStore((state) => state.isStreaming);
  const messages = useChatStore((state) => state.messages);

  const filterActive = activeTagFilter.length > 0;

  return useMemo<SessionListItem[]>(() => {
    if (!chatSessionId) return remoteSessions;

    const exists = remoteSessions.some((s) => s.id === chatSessionId);
    let list = remoteSessions.map((s) => (s.id === chatSessionId ? { ...s, streaming: isStreaming } : s));

    // While a tag filter is active, don't inject an untagged brand-new session that
    // the server-side filter would have excluded.
    if (!exists && !filterActive) {
      const firstUser = messages.find((m) => m.role === 'user' || m.role === 'user-with-attachments');
      const rawTitle =
        firstUser && 'content' in firstUser ? firstUser.content.replace(/\s+/g, ' ').trim().slice(0, 40) : '';
      const now = Date.now();
      list = [
        {
          id: chatSessionId,
          title: rawTitle || null,
          createdAt: now,
          updatedAt: now,
          messageCount: messages.length,
          streaming: isStreaming,
        },
        ...list,
      ];
    } else if (isStreaming) {
      // Pin the actively running session to the top so its state is immediately visible.
      const idx = list.findIndex((s) => s.id === chatSessionId);
      if (idx > 0) {
        const [active] = list.splice(idx, 1);
        list = [active, ...list];
      }
    }

    return list;
  }, [remoteSessions, chatSessionId, isStreaming, messages, filterActive]);
}
