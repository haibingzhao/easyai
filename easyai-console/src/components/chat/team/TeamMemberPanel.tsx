import { useEffect, useMemo } from 'react';
import { Users } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { useTeamStore } from '@/services/stores/team-store';
import { TeamFilterTabs } from './TeamFilterTabs';
import { TeamMemberCard } from './TeamMemberCard';
import { matchesTeamFilter, TERMINAL_STATUSES, type TeamFilter, type TeamMemberExecution } from '@/types/team';
import { i18n } from '@/utils/i18n';

const POLL_INTERVAL_MS = 3000;

/** Sort executions: active (running/blocked) first, then by startedAt descending. */
function sortExecutions(executions: TeamMemberExecution[]): TeamMemberExecution[] {
  const statusOrder: Record<string, number> = { RUNNING: 0, ESCALATED: 1, SUSPENDED: 1, ERROR: 2, COMPLETED: 3, RESUMED: 4, REASSIGNED: 5 };
  return [...executions].sort((a, b) =>
    (statusOrder[a.status] ?? 9) - (statusOrder[b.status] ?? 9) ||
    ((b.startedAt ?? 0) - (a.startedAt ?? 0))
  );
}

/**
 * Team Member Panel — right-panel tab showing member execution cards
 * for the current TEAM agent session. Polls the executions API.
 * Each execution is shown as a separate card (same member can appear multiple times).
 */
export const TeamMemberPanel: React.FC = () => {
  const sessionId = useChatStore((s) => s.sessionId);
  const memberExecutions = useTeamStore((s) => s.memberExecutions);
  const teamFilter = useTeamStore((s) => s.teamFilter);
  const setTeamFilter = useTeamStore((s) => s.setTeamFilter);
  const refreshExecutions = useTeamStore((s) => s.refreshExecutions);
  const selectMember = useTeamStore((s) => s.selectMember);
  const selectedExecutionId = useTeamStore((s) => s.selectedExecutionId);
  const resetTeam = useTeamStore((s) => s.resetTeam);

  const sorted = useMemo(() => sortExecutions(memberExecutions), [memberExecutions]);

  // When every execution has reached a terminal status there is nothing new to poll;
  // a subsequent leader action (re-delegate / resume) arrives via SSE and re-triggers a refresh.
  const allTerminal = sorted.length > 0 &&
    sorted.every((exec) => TERMINAL_STATUSES.includes(exec.status));

  // Initial fetch + reset on session change
  useEffect(() => {
    if (!sessionId) {
      resetTeam();
      return;
    }
    refreshExecutions(sessionId);
  }, [sessionId, refreshExecutions, resetTeam]);

  // Poll while a session is active and not all executions are terminal
  useEffect(() => {
    if (!sessionId || allTerminal) return;
    const timer = setInterval(() => refreshExecutions(sessionId), POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [sessionId, allTerminal, refreshExecutions]);

  const counts = useMemo(() => {
    const c: Record<TeamFilter, number> = { ALL: sorted.length, RUNNING: 0, DONE: 0, BLOCKED: 0, ERROR: 0 };
    for (const exec of sorted) {
      if (matchesTeamFilter(exec.status, 'RUNNING')) c.RUNNING++;
      if (matchesTeamFilter(exec.status, 'DONE')) c.DONE++;
      if (matchesTeamFilter(exec.status, 'BLOCKED')) c.BLOCKED++;
      if (matchesTeamFilter(exec.status, 'ERROR')) c.ERROR++;
    }
    return c;
  }, [sorted]);

  const visible = sorted.filter((exec) => matchesTeamFilter(exec.status, teamFilter));

  return (
    <div className="h-full flex flex-col">
      <TeamFilterTabs active={teamFilter} counts={counts} onChange={setTeamFilter} />
      <div className="flex-1 overflow-y-auto p-2 space-y-2">
        {visible.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-2">
            <Users className="w-8 h-8 opacity-40" />
            <p className="text-xs">
              {sorted.length === 0
                ? i18n('No member executions yet. The team leader will delegate tasks to members during the conversation.')
                : i18n('No members match this filter.')}
            </p>
          </div>
        ) : (
          visible.map((exec) => (
            <TeamMemberCard
              key={exec.id}
              execution={exec}
              selected={selectedExecutionId === exec.id}
              onClick={() => selectMember(exec)}
            />
          ))
        )}
      </div>
    </div>
  );
};
