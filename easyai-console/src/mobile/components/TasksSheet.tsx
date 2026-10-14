import React from 'react';
import { useChatStore } from '@/services/stores/chat-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { useAggregatedReferences } from '@/hooks/useAggregatedReferences';
import { GoalCard } from '@/components/chat/GoalCard';
import { SwarmRunCard } from '@/components/chat/SwarmRunCard';
import { TodoPanel } from '@/components/chat/TodoPanel';
import { ReferencePanel } from '@/components/chat/ReferencePanel';
import { ForkBranchesSection } from '@/components/chat/ForkBranchesSection';
import { TeamMemberPanel } from '@/components/chat/team/TeamMemberPanel';
import { i18n } from '@/utils/i18n';
import { MobileSheet } from './MobileSheet';
import { useMobileUiStore } from '../stores/mobile-ui-store';

/**
 * The "☰" sheet: everything the desktop keeps in `RightPanel`'s Summary tab, plus the Team tab.
 *
 * The desktop panel is 400px wide and sits beside the transcript; a phone has no beside, so the
 * same read-only components stack in one scrolling sheet. Nothing here is re-implemented — the
 * components are the desktop ones, which keeps the two views from drifting.
 */
export const TasksSheet: React.FC = () => {
  const open = useMobileUiStore((s) => s.tasksSheetOpen);
  const close = useMobileUiStore((s) => s.closeTasksSheet);

  const todos = useChatStore((s) => s.todos);
  const subAgentTodos = useChatStore((s) => s.subAgentTodos);
  const swarmRuns = useChatStore((s) => s.swarmRuns);
  const currentGoal = useChatStore((s) => s.currentGoal);
  const sessionVariables = useChatStore((s) => s.sessionVariables);
  const references = useAggregatedReferences();

  const agents = useAgentStore((s) => s.agents);
  const selectedAgentId = useAgentStore((s) => s.selectedAgentId);
  const isTeamAgent = agents.find((a) => a.id === selectedAgentId)?.agentType === 'TEAM';

  const hasSwarmRuns = Object.keys(swarmRuns).length > 0;

  return (
    <MobileSheet open={open} onClose={close} title="Tasks" detent="half">
      {currentGoal && (
        <>
          <GoalCard goal={currentGoal} />
          <div className="border-t border-dashed border-border my-2" />
        </>
      )}
      {isTeamAgent && (
        <>
          <TeamMemberPanel />
          <div className="border-t border-dashed border-border my-2" />
        </>
      )}
      {hasSwarmRuns && (
        <>
          <SwarmRunCard runs={swarmRuns} />
          <div className="border-t border-dashed border-border my-2" />
        </>
      )}
      <TodoPanel mainTodos={todos} subAgentTodos={subAgentTodos} swarmRuns={swarmRuns} />
      <div className="border-t border-dashed border-border my-2" />
      <ReferencePanel references={references} sessionVariables={sessionVariables} />
      <ForkBranchesSection />
      <p className="mt-3 text-[11px] text-muted-foreground">
        {i18n('Review diffs, the file tree and workflow editing live on the desktop console.')}
      </p>
    </MobileSheet>
  );
};
