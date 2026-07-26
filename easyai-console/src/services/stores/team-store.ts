import { create } from 'zustand';
import type { TeamMemberExecution, TeamFilter } from '@/types/team';
import type { Message } from '@/types/message';
import { teamService } from '@/services/team-service';
import { sessionService } from '@/services/session-service';
import type { MessageSnapshot } from '@/services/session-service';
import { convertSnapshot } from './chat/message-converter';
import { mergeToolResults } from './chat/session-loader';

interface TeamState {
  /** Member execution records for the current team session */
  memberExecutions: TeamMemberExecution[];
  /** Currently selected execution (for detail view); null = team overview */
  selectedExecutionId: string | null;
  /** Session ID of the selected member (used to load messages) */
  selectedMemberSessionId: string | null;
  /** Messages of the selected member's session */
  memberMessages: Message[];
  /** Raw snapshots of the selected member's session (watermark source for incremental polling). */
  memberSnapshots: MessageSnapshot[];
  /** Loading state for member messages */
  memberMessagesLoading: boolean;
  /** Active filter tab */
  teamFilter: TeamFilter;
  /** Session id that the panel is currently tracking (guards against stale fetches). */
  activeSessionId: string | null;
  /** Last refresh timestamp (for polling dedup) */
  lastRefreshAt: number;

  setTeamFilter: (filter: TeamFilter) => void;
  /** Fetch latest executions from backend. */
  refreshExecutions: (sessionId: string) => Promise<void>;
  /** Select a member card → load its session messages for the detail view. */
  selectMember: (execution: TeamMemberExecution) => Promise<void>;
  /** Incrementally fetch new messages for the currently selected member (polling while running). */
  refreshMemberMessages: () => Promise<void>;
  /** Back to team overview. */
  clearSelectedMember: () => void;
  /** Reset all team state (on session switch / clearChat). */
  resetTeam: () => void;
}

/**
 * Full load of a member session's messages (initial selection + compaction fallback).
 * Silently ignores errors — the next poll will retry.
 */
async function loadFullMessages(
  memberSessionId: string,
  set: (partial: Partial<TeamState>) => void,
  get: () => TeamState
): Promise<void> {
  try {
    const detail = await sessionService.getSessionDetail(memberSessionId);
    // Guard: selection may have changed during fetch
    if (get().selectedMemberSessionId !== memberSessionId) return;
    set({
      memberSnapshots: detail.messages,
      memberMessages: mergeToolResults(detail.messages.map(convertSnapshot)),
      memberMessagesLoading: false,
    });
  } catch {
    if (get().selectedMemberSessionId === memberSessionId) {
      set({ memberMessagesLoading: false });
    }
  }
}

export const useTeamStore = create<TeamState>((set, get) => ({
  memberExecutions: [],
  selectedExecutionId: null,
  selectedMemberSessionId: null,
  memberMessages: [],
  memberSnapshots: [],
  memberMessagesLoading: false,
  teamFilter: 'ALL',
  activeSessionId: null,
  lastRefreshAt: 0,

  setTeamFilter: (filter) => set({ teamFilter: filter }),

  refreshExecutions: async (sessionId: string) => {
    // Mark which session this refresh belongs to (latest call wins).
    set({ activeSessionId: sessionId });
    try {
      const executions = await teamService.getExecutions(sessionId);
      // Guard: skip if the session changed while this fetch was in flight,
      // so a stale response never overwrites the new session's data.
      if (get().activeSessionId !== sessionId) return;
      set({ memberExecutions: executions, lastRefreshAt: Date.now() });
    } catch {
      // Silently ignore — panel will retry on next poll
    }
  },

  selectMember: async (execution: TeamMemberExecution) => {
    const memberSessionId = execution.memberSessionId;
    set({
      selectedExecutionId: execution.id,
      selectedMemberSessionId: memberSessionId ?? null,
      memberMessages: [],
      memberSnapshots: [],
      memberMessagesLoading: true,
    });
    if (!memberSessionId) {
      set({ memberMessagesLoading: false });
      return;
    }
    await loadFullMessages(memberSessionId, set, get);
  },

  refreshMemberMessages: async () => {
    const memberSessionId = get().selectedMemberSessionId;
    if (!memberSessionId) return;

    const snapshots = get().memberSnapshots;
    const watermark = snapshots.reduce((max, s) => Math.max(max, s.timestamp), 0);

    // No baseline yet (e.g. initial load failed) → full load
    if (watermark <= 0) {
      await loadFullMessages(memberSessionId, set, get);
      return;
    }

    try {
      // Overlap by 1ms so same-timestamp messages are never missed; dedup by id below.
      const resp = await sessionService.getSessionMessagesAfter(memberSessionId, watermark - 1);
      // Guard: selection may have changed during fetch
      if (get().selectedMemberSessionId !== memberSessionId) return;

      if (resp.compactionOccurredAfter || resp.contentUpdatedAt > watermark) {
        // History may have been rewritten (compaction / in-place edit) → full reload
        await loadFullMessages(memberSessionId, set, get);
        return;
      }

      if (resp.messages.length === 0) return;

      // Deduplicate by message id (overlap window may return already-known messages)
      const existingIds = new Set(snapshots.map((s) => s.id).filter(Boolean));
      const newSnapshots = resp.messages.filter((s) => !s.id || !existingIds.has(s.id));
      if (newSnapshots.length === 0) return;

      const merged = [...snapshots, ...newSnapshots];
      set({
        memberSnapshots: merged,
        memberMessages: mergeToolResults(merged.map(convertSnapshot)),
      });
    } catch {
      // Silently ignore — will retry on next poll
    }
  },

  clearSelectedMember: () =>
    set({
      selectedExecutionId: null,
      selectedMemberSessionId: null,
      memberMessages: [],
      memberSnapshots: [],
      memberMessagesLoading: false,
    }),

  resetTeam: () =>
    set({
      memberExecutions: [],
      selectedExecutionId: null,
      selectedMemberSessionId: null,
      memberMessages: [],
      memberSnapshots: [],
      memberMessagesLoading: false,
      teamFilter: 'ALL',
      activeSessionId: null,
      lastRefreshAt: 0,
    }),
}));
