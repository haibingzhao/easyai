import { create } from 'zustand';
import type { TeamMemberExecution, TeamFilter } from '@/types/team';
import type { Message } from '@/types/message';
import { teamService } from '@/services/team-service';
import { sessionService } from '@/services/session-service';
import { convertSnapshot } from './chat/message-converter';

interface TeamState {
  /** Member execution records for the current team session */
  memberExecutions: TeamMemberExecution[];
  /** Currently selected member (for detail view); null = team overview */
  selectedMemberId: string | null;
  /** Session ID of the selected member (used to load messages) */
  selectedMemberSessionId: string | null;
  /** Messages of the selected member's session */
  memberMessages: Message[];
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
  /** Re-fetch messages for the currently selected member (polling while running). */
  refreshMemberMessages: () => Promise<void>;
  /** Back to team overview. */
  clearSelectedMember: () => void;
  /** Reset all team state (on session switch / clearChat). */
  resetTeam: () => void;
}

export const useTeamStore = create<TeamState>((set, get) => ({
  memberExecutions: [],
  selectedMemberId: null,
  selectedMemberSessionId: null,
  memberMessages: [],
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
      selectedMemberId: execution.memberId,
      selectedMemberSessionId: memberSessionId ?? null,
      memberMessages: [],
      memberMessagesLoading: true,
    });
    if (!memberSessionId) {
      set({ memberMessagesLoading: false });
      return;
    }
    try {
      const detail = await sessionService.getSessionDetail(memberSessionId);
      // Guard: selection may have changed during fetch
      if (get().selectedMemberSessionId !== memberSessionId) return;
      const messages = detail.messages.map(convertSnapshot);
      set({ memberMessages: messages, memberMessagesLoading: false });
    } catch {
      if (get().selectedMemberSessionId === memberSessionId) {
        set({ memberMessagesLoading: false });
      }
    }
  },

  refreshMemberMessages: async () => {
    const memberSessionId = get().selectedMemberSessionId;
    if (!memberSessionId) return;
    try {
      const detail = await sessionService.getSessionDetail(memberSessionId);
      // Guard: selection may have changed during fetch
      if (get().selectedMemberSessionId !== memberSessionId) return;
      const messages = detail.messages.map(convertSnapshot);
      set({ memberMessages: messages });
    } catch {
      // Silently ignore — will retry on next poll
    }
  },

  clearSelectedMember: () =>
    set({
      selectedMemberId: null,
      selectedMemberSessionId: null,
      memberMessages: [],
      memberMessagesLoading: false,
    }),

  resetTeam: () =>
    set({
      memberExecutions: [],
      selectedMemberId: null,
      selectedMemberSessionId: null,
      memberMessages: [],
      memberMessagesLoading: false,
      teamFilter: 'ALL',
      activeSessionId: null,
      lastRefreshAt: 0,
    }),
}));
