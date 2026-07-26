import { useState, useEffect, useRef } from 'react';
import { ArrowLeft, Loader2, AlertTriangle, CheckCircle2, XCircle, RefreshCw, ChevronDown, ChevronRight } from 'lucide-react';
import { useTeamStore } from '@/services/stores/team-store';
import { MessageList } from '../MessageList';
import { formatTokenCount } from '@/utils/format';
import { i18n } from '@/utils/i18n';
import { TERMINAL_STATUSES, type TeamMemberStatus } from '@/types/team';

function statusBadge(status: TeamMemberStatus): { color: string; label: string } {
  switch (status) {
    case 'RUNNING':
      return { color: 'text-blue-400', label: 'Running' };
    case 'COMPLETED':
      return { color: 'text-green-500', label: 'Completed' };
    case 'ESCALATED':
    case 'SUSPENDED':
      return { color: 'text-amber-400', label: 'Blocked' };
    case 'ERROR':
      return { color: 'text-destructive', label: 'Error' };
    case 'RESUMED':
      return { color: 'text-muted-foreground', label: 'Resumed' };
    case 'REASSIGNED':
      return { color: 'text-muted-foreground', label: 'Reassigned' };
  }
}

function StatusIcon({ status }: { status: TeamMemberStatus }) {
  switch (status) {
    case 'RUNNING':
      return <Loader2 className="w-4 h-4 animate-spin text-blue-400" />;
    case 'COMPLETED':
      return <CheckCircle2 className="w-4 h-4 text-green-500" />;
    case 'ESCALATED':
    case 'SUSPENDED':
      return <AlertTriangle className="w-4 h-4 text-amber-400" />;
    case 'ERROR':
      return <XCircle className="w-4 h-4 text-destructive" />;
    default:
      return <RefreshCw className="w-4 h-4 text-muted-foreground" />;
  }
}

/**
 * Member detail view — replaces the main message list when a member card
 * is selected in the Team Member Panel. Shows the member's own session messages.
 */
export const TeamMemberDetail: React.FC = () => {
  const selectedExecutionId = useTeamStore((s) => s.selectedExecutionId);
  const memberMessages = useTeamStore((s) => s.memberMessages);
  const memberMessagesLoading = useTeamStore((s) => s.memberMessagesLoading);
  const memberExecutions = useTeamStore((s) => s.memberExecutions);
  const clearSelectedMember = useTeamStore((s) => s.clearSelectedMember);
  const refreshMemberMessages = useTeamStore((s) => s.refreshMemberMessages);

  const [assignmentExpanded, setAssignmentExpanded] = useState(false);
  const assignmentRef = useRef<HTMLParagraphElement>(null);
  const [isClamped, setIsClamped] = useState(false);

  // Find the selected execution (meta info)
  const execution = memberExecutions.find((e) => e.id === selectedExecutionId);

  const badge = execution ? statusBadge(execution.status) : null;

  // Detect whether the assignment text actually overflows 2 lines
  useEffect(() => {
    if (assignmentExpanded) {
      // Keep toggle visible while expanded (user can only expand when clamped)
      setIsClamped(true);
      return;
    }
    const el = assignmentRef.current;
    if (el) {
      setIsClamped(el.scrollHeight > el.clientHeight);
    }
  }, [execution?.assignment, assignmentExpanded]);

  // Poll member messages while the execution is non-terminal
  const isRunning = execution ? !TERMINAL_STATUSES.includes(execution.status) : false;
  useEffect(() => {
    if (!isRunning || !selectedExecutionId) return;
    const timer = setInterval(() => refreshMemberMessages(), 3000);
    return () => clearInterval(timer);
  }, [isRunning, selectedExecutionId, refreshMemberMessages]);

  return (
    <div className="h-full flex flex-col">
      {/* Meta header */}
      <div className="shrink-0 border-b border-border px-4 py-2 space-y-1">
        <button
          onClick={clearSelectedMember}
          className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          <ArrowLeft className="w-3.5 h-3.5" />
          {i18n('Back to Team')}
        </button>
        {execution && (
          <div className="flex items-center gap-2">
            <StatusIcon status={execution.status} />
            <span className="text-sm font-medium">{execution.memberName}</span>
            {badge && <span className={`text-[11px] ${badge.color}`}>{i18n(badge.label)}</span>}
            <span className="text-[11px] text-muted-foreground">{i18n('Round')} {execution.round}</span>
            {(execution.inputTokens > 0 || execution.outputTokens > 0) && (
              <span className="text-[11px] text-muted-foreground tabular-nums">
                ↑{formatTokenCount(execution.inputTokens)} ↓{formatTokenCount(execution.outputTokens)}
              </span>
            )}
          </div>
        )}
        {execution && (
          <div
            className={`select-none ${isClamped ? 'cursor-pointer' : ''}`}
            onClick={() => isClamped && setAssignmentExpanded((prev) => !prev)}
          >
            <p
              ref={assignmentRef}
              className={`text-xs text-muted-foreground whitespace-pre-wrap break-words ${
                assignmentExpanded ? '' : 'line-clamp-2'
              }`}
            >
              {execution.assignment}
            </p>
            {isClamped && (
              <span className="inline-flex items-center gap-0.5 text-[10px] text-primary/70 hover:text-primary transition-colors">
                {assignmentExpanded
                  ? <><ChevronDown className="w-3 h-3 rotate-180" />{i18n('Collapse')}</>
                  : <><ChevronRight className="w-3 h-3 rotate-90" />{i18n('Show more')}</>
                }
              </span>
            )}
          </div>
        )}
      </div>

      {/* Member messages */}
      <div className="flex-1 overflow-y-auto">
        {memberMessagesLoading ? (
          <div className="flex items-center justify-center h-32 text-muted-foreground gap-2">
            <Loader2 className="w-4 h-4 animate-spin" />
            <span className="text-sm">{i18n('Loading member messages...')}</span>
          </div>
        ) : memberMessages.length === 0 ? (
          <div className="flex items-center justify-center h-32 text-muted-foreground">
            <span className="text-xs">
              {execution?.memberSessionId
                ? i18n('No messages recorded for this member yet.')
                : i18n('Member session not available.')}
            </span>
          </div>
        ) : (
          <MessageList messages={memberMessages} isStreaming={false} disableEdit />
        )}
      </div>
    </div>
  );
};
