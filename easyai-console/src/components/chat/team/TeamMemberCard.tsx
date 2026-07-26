import { Bot, AlertTriangle, CheckCircle2, XCircle, RefreshCw, Loader2 } from 'lucide-react';
import type { TeamMemberExecution, TeamMemberStatus } from '@/types/team';
import { formatTokenCount } from '@/utils/format';
import { i18n } from '@/utils/i18n';

/** Status → icon + color mapping */
function statusVisual(status: TeamMemberStatus): { icon: React.ReactNode; color: string; label: string } {
  switch (status) {
    case 'RUNNING':
      return { icon: <Loader2 className="w-3.5 h-3.5 animate-spin" />, color: 'text-blue-400', label: 'Running' };
    case 'COMPLETED':
      return { icon: <CheckCircle2 className="w-3.5 h-3.5" />, color: 'text-green-500', label: 'Completed' };
    case 'ESCALATED':
    case 'SUSPENDED':
      return { icon: <AlertTriangle className="w-3.5 h-3.5" />, color: 'text-amber-400', label: 'Blocked' };
    case 'ERROR':
      return { icon: <XCircle className="w-3.5 h-3.5" />, color: 'text-destructive', label: 'Error' };
    case 'RESUMED':
      return { icon: <RefreshCw className="w-3.5 h-3.5" />, color: 'text-muted-foreground', label: 'Resumed' };
    case 'REASSIGNED':
      return { icon: <RefreshCw className="w-3.5 h-3.5" />, color: 'text-muted-foreground', label: 'Reassigned' };
  }
}

interface TeamMemberCardProps {
  execution: TeamMemberExecution;
  /** Total rounds executed by this member */
  rounds: number;
  /** Aggregated tokens across rounds */
  totalInputTokens: number;
  totalOutputTokens: number;
  selected: boolean;
  onClick: () => void;
}

/** A single member card in the Team Member Panel. */
export const TeamMemberCard: React.FC<TeamMemberCardProps> = ({
  execution,
  rounds,
  totalInputTokens,
  totalOutputTokens,
  selected,
  onClick,
}) => {
  const visual = statusVisual(execution.status);
  const hasTokens = totalInputTokens > 0 || totalOutputTokens > 0;

  return (
    <button
      onClick={onClick}
      className={`w-full text-left p-2.5 rounded-lg border transition-colors ${
        selected
          ? 'border-primary bg-primary/5'
          : 'border-border hover:border-muted-foreground/40 hover:bg-muted/50'
      }`}
    >
      {/* Header: icon + name + status */}
      <div className="flex items-center gap-2">
        <span className={visual.color}>{visual.icon}</span>
        <span className="text-sm font-medium truncate flex-1">{execution.memberName}</span>
        <span className={`text-[11px] shrink-0 ${visual.color}`}>{i18n(visual.label)}</span>
      </div>

      {/* Assignment */}
      <p className="mt-1 text-xs text-muted-foreground line-clamp-2">{execution.assignment}</p>

      {/* Blocked reason */}
      {execution.blockedQuestion && (execution.status === 'ESCALATED' || execution.status === 'SUSPENDED') && (
        <p className="mt-1 text-xs text-amber-400/90 line-clamp-2">
          {i18n('Needs')}: {execution.blockedQuestion}
        </p>
      )}

      {/* Footer: round + tokens */}
      <div className="mt-1.5 flex items-center gap-2 text-[11px] text-muted-foreground">
        <span className="inline-flex items-center gap-1">
          <Bot className="w-3 h-3" />
          {i18n('Round')} {execution.round}
          {rounds > 1 && `/${rounds}`}
        </span>
        {hasTokens && (
          <span className="tabular-nums">
            ↑{formatTokenCount(totalInputTokens)} ↓{formatTokenCount(totalOutputTokens)}
          </span>
        )}
      </div>
    </button>
  );
};
