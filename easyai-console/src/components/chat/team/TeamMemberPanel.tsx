import { useEffect, useMemo } from 'react';
import { Users } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { useTeamStore } from '@/services/stores/team-store';
import { TeamFilterTabs } from './TeamFilterTabs';
import { TeamMemberCard } from './TeamMemberCard';
import { matchesTeamFilter, TERMINAL_STATUSES, type TeamFilter, type TeamMemberExecution } from '@/types/team';
import { i18n } from '@/utils/i18n';

const POLL_INTERVAL_MS = 3000;

/** Aggregated view of one member across all its executions. */
interface MemberAggregate {
  memberId: string;
  /** Latest execution (by round, then startedAt) */
  latest: TeamMemberExecution;
  rounds: number;
  totalInputTokens: number;
  totalOutputTokens: number;
}

function aggregateByMember(executions: TeamMemberExecution[]): MemberAggregate[] {
  const groups = new Map<string, TeamMemberExecution[]>();
  for (const exec of executions) {
    const list = groups.get(exec.memberId) ?? [];
    list.push(exec);
    groups.set(exec.memberId, list);
  }
  const result: MemberAggregate[] = [];
  for (const [memberId, list] of groups) {
    const sorted = [...list].sort((a, b) => (a.round - b.round) || ((a.startedAt ?? 0) - (b.startedAt ?? 0)));
    const latest = sorted[sorted.length - 1];
    result.push({
      memberId,
      latest,
      rounds: sorted.length,
      totalInputTokens: sorted.reduce((s, e) => s + (e.inputTokens || 0), 0),
      totalOutputTokens: sorted.reduce((s, e) => s + (e.outputTokens || 0), 0),
    });
  }
  // Running/blocked members first, then by startedAt
  const statusOrder: Record<string, number> = { RUNNING: 0, ESCALATED: 1, SUSPENDED: 1, ERROR: 2, COMPLETED: 3, RESUMED: 4, REASSIGNED: 5 };
  result.sort((a, b) =>
    (statusOrder[a.latest.status] ?? 9) - (statusOrder[b.latest.status] ?? 9) ||
    ((a.latest.startedAt ?? 0) - (b.latest.startedAt ?? 0))
  );
  return result;
}

/**
 * Team Member Panel — right-panel tab showing member execution cards
 * for the current TEAM agent session. Polls the executions API.
 */
export const TeamMemberPanel: React.FC = () => {
  const sessionId = useChatStore((s) => s.sessionId);
  const memberExecutions = useTeamStore((s) => s.memberExecutions);
  const teamFilter = useTeamStore((s) => s.teamFilter);
  const setTeamFilter = useTeamStore((s) => s.setTeamFilter);
  const refreshExecutions = useTeamStore((s) => s.refreshExecutions);
  const selectMember = useTeamStore((s) => s.selectMember);
  const selectedMemberId = useTeamStore((s) => s.selectedMemberId);
  const resetTeam = useTeamStore((s) => s.resetTeam);

  const aggregates = useMemo(() => aggregateByMember(memberExecutions), [memberExecutions]);

  // When every member has reached a terminal status there is nothing new to poll;
  // a subsequent leader action (re-delegate / resume) arrives via SSE and re-triggers a refresh.
  const allTerminal = aggregates.length > 0 &&
    aggregates.every((agg) => TERMINAL_STATUSES.includes(agg.latest.status));

  // Initial fetch + reset on session change
  useEffect(() => {
    if (!sessionId) {
      resetTeam();
      return;
    }
    refreshExecutions(sessionId);
  }, [sessionId, refreshExecutions, resetTeam]);

  // Poll while a session is active and not all members are terminal
  useEffect(() => {
    if (!sessionId || allTerminal) return;
    const timer = setInterval(() => refreshExecutions(sessionId), POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [sessionId, allTerminal, refreshExecutions]);

  const counts = useMemo(() => {
    const c: Record<TeamFilter, number> = { ALL: aggregates.length, RUNNING: 0, DONE: 0, BLOCKED: 0, ERROR: 0 };
    for (const agg of aggregates) {
      if (matchesTeamFilter(agg.latest.status, 'RUNNING')) c.RUNNING++;
      if (matchesTeamFilter(agg.latest.status, 'DONE')) c.DONE++;
      if (matchesTeamFilter(agg.latest.status, 'BLOCKED')) c.BLOCKED++;
      if (matchesTeamFilter(agg.latest.status, 'ERROR')) c.ERROR++;
    }
    return c;
  }, [aggregates]);

  const visible = aggregates.filter((agg) => matchesTeamFilter(agg.latest.status, teamFilter));

  return (
    <div className="h-full flex flex-col">
      <TeamFilterTabs active={teamFilter} counts={counts} onChange={setTeamFilter} />
      <div className="flex-1 overflow-y-auto p-2 space-y-2">
        {visible.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-2">
            <Users className="w-8 h-8 opacity-40" />
            <p className="text-xs">
              {aggregates.length === 0
                ? i18n('No member executions yet. The team leader will delegate tasks to members during the conversation.')
                : i18n('No members match this filter.')}
            </p>
          </div>
        ) : (
          visible.map((agg) => (
            <TeamMemberCard
              key={agg.memberId}
              execution={agg.latest}
              rounds={agg.rounds}
              totalInputTokens={agg.totalInputTokens}
              totalOutputTokens={agg.totalOutputTokens}
              selected={selectedMemberId === agg.memberId}
              onClick={() => selectMember(agg.latest)}
            />
          ))
        )}
      </div>
    </div>
  );
};
