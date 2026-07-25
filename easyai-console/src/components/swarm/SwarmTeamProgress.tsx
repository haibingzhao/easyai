import React, { useEffect, useState, useCallback } from 'react';
import { Loader2, CheckCircle2, AlertTriangle, ArrowRightLeft, ChevronDown, ChevronRight, PauseCircle, XCircle, PlayCircle } from 'lucide-react';
import type { TeamHistoryResponse, TeamRoundRecordDto, TeamMemberExecutionDto, EscalationEntryDto, MemberStatusDto } from '@/services/swarm-service';
import { swarmService } from '@/services/swarm-service';
import { i18n } from '@/utils/i18n';
import { usePolling } from '@/hooks/usePolling';

interface SwarmTeamProgressProps {
  runId: string;
  taskId: string;
  taskStatus?: string;
  teamHistory?: TeamHistoryResponse;
}

const STATUS_CONFIG: Record<MemberStatusDto, { icon: React.ReactNode; color: string; label: string }> = {
  RUNNING: {
    icon: <Loader2 className="w-3 h-3 animate-spin" />,
    color: 'text-blue-500',
    label: 'Running',
  },
  COMPLETED: {
    icon: <CheckCircle2 className="w-3 h-3" />,
    color: 'text-green-500',
    label: 'Completed',
  },
  ESCALATED: {
    icon: <AlertTriangle className="w-3 h-3" />,
    color: 'text-orange-500',
    label: 'Escalated',
  },
  ERROR: {
    icon: <XCircle className="w-3 h-3" />,
    color: 'text-red-500',
    label: 'Error',
  },
  SUSPENDED: {
    icon: <PauseCircle className="w-3 h-3" />,
    color: 'text-amber-500',
    label: 'Suspended',
  },
  RESUMED: {
    icon: <PlayCircle className="w-3 h-3" />,
    color: 'text-teal-500',
    label: 'Resumed',
  },
  REASSIGNED: {
    icon: <ArrowRightLeft className="w-3 h-3" />,
    color: 'text-gray-500',
    label: 'Reassigned',
  },
};

export const SwarmTeamProgress: React.FC<SwarmTeamProgressProps> = ({
  runId,
  taskId,
  taskStatus,
  teamHistory: externalHistory,
}) => {
  const [history, setHistory] = useState<TeamHistoryResponse | null>(externalHistory ?? null);
  const [loading, setLoading] = useState(!externalHistory);
  const [expandedRound, setExpandedRound] = useState<number | null>(null);
  const [collapsedEntries, setCollapsedEntries] = useState<Set<string>>(new Set());

  const isRunning = taskStatus === 'PENDING' || taskStatus === 'IN_PROGRESS';

  const toggleEntry = (key: string) => {
    setCollapsedEntries((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const fetchData = useCallback((showLoader: boolean) => {
    if (showLoader) setLoading(true);
    swarmService.fetchTeamHistory(runId, taskId).then((data) => {
      setHistory(data);
      if (showLoader) setLoading(false);
    }).catch(() => {
      if (showLoader) setLoading(false);
    });
  }, [runId, taskId]);

  // Initial load
  useEffect(() => {
    if (externalHistory) {
      setHistory(externalHistory);
      setLoading(false);
      return;
    }
    fetchData(true);
  }, [fetchData, externalHistory]);

  // Poll while task is running
  const pollFetch = useCallback(() => fetchData(false), [fetchData]);
  usePolling(pollFetch, isRunning);

  if (loading) {
    return (
      <div className="flex items-center justify-center p-6">
        <Loader2 className="w-4 h-4 animate-spin text-muted-foreground" />
        <span className="ml-2 text-xs text-muted-foreground">{i18n('Loading team history...')}</span>
      </div>
    );
  }

  if (!history) {
    return (
      <div className="p-4 text-xs text-muted-foreground text-center">
        {i18n('No team execution data available.')}
      </div>
    );
  }

  const { roundRecords, memberExecutions, escalationHistory } = history;

  // Auto-select the latest round when running
  const activeRound = expandedRound ?? (isRunning && roundRecords.length > 0
    ? roundRecords[roundRecords.length - 1].round
    : null);

  return (
    <div className="flex flex-col min-h-0 flex-1">
      {/* Content area */}
      <div className="flex-1 min-h-0 overflow-y-auto p-3 space-y-3">
        {/* Leader Decisions */}
        <div>
          <h4 className="text-xs font-medium text-teal-400 mb-2">{i18n('Leader Decisions')}</h4>
          {roundRecords.length === 0 ? (
            <p className="text-xs text-muted-foreground">{i18n('No rounds recorded yet.')}</p>
          ) : (
            <div className="space-y-1.5">
              {roundRecords.map((record: TeamRoundRecordDto) => {
                const isExpanded = activeRound === record.round;
                const promptKey = `leader-prompt-${record.round}`;
                const isPromptCollapsed = collapsedEntries.has(promptKey);
                const promptLabel = record.round === 1 ? 'Planning Prompt' : 'Coordination Prompt';
                return (
                  <div key={record.round} className="rounded-md border border-border overflow-hidden">
                    {/* Round header */}
                    <button
                      type="button"
                      onClick={() => setExpandedRound(isExpanded ? null : record.round)}
                      className="w-full flex items-center gap-1.5 px-2 py-1.5 text-xs bg-muted/30 hover:bg-muted/60 transition-colors"
                    >
                      {isExpanded
                        ? <ChevronDown className="w-3 h-3 shrink-0" />
                        : <ChevronRight className="w-3 h-3 shrink-0" />
                      }
                      <span className="font-medium">{i18n('Round')} {record.round}</span>
                      <span className="text-[10px] text-muted-foreground ml-auto">
                        {record.delegatedMembers.length} {i18n('delegated')}
                      </span>
                    </button>
                    {isExpanded && (
                      <div className="bg-card">
                        {/* Leader Prompt (amber card) */}
                        {record.leaderPrompt && (
                          <div className="border-b border-border">
                            <div
                              className="flex items-center gap-1.5 px-2 py-1.5 cursor-pointer hover:bg-amber-50/50 dark:hover:bg-amber-950/30 transition-colors"
                              onClick={() => toggleEntry(promptKey)}
                            >
                              {isPromptCollapsed
                                ? <ChevronRight className="w-3 h-3 text-amber-600 dark:text-amber-400 shrink-0" />
                                : <ChevronDown className="w-3 h-3 text-amber-600 dark:text-amber-400 shrink-0" />
                              }
                              <span className="text-[10px] font-medium text-amber-700 dark:text-amber-300">
                                {i18n(`Leader ${promptLabel}`)}
                              </span>
                            </div>
                            {!isPromptCollapsed && (
                              <div className="px-2 pb-1.5 text-[11px] text-foreground/70 whitespace-pre-wrap">
                                {record.leaderPrompt}
                              </div>
                            )}
                          </div>
                        )}
                        {/* Analysis + delegation info */}
                        <div className="p-2 space-y-2 text-xs">
                          <div>
                            <span className="text-muted-foreground">{i18n('Analysis')}:</span>
                            <p className="mt-0.5 text-foreground/80 whitespace-pre-wrap">{record.leaderAnalysis || '—'}</p>
                          </div>
                          <div className="flex gap-4">
                            <div>
                              <span className="text-muted-foreground">{i18n('Delegated')}:</span>
                              <span className="ml-1">{record.delegatedMembers.join(', ') || '—'}</span>
                            </div>
                            <div>
                              <span className="text-muted-foreground">{i18n('Completed')}:</span>
                              <span className="ml-1">{record.completedMembers.join(', ') || '—'}</span>
                            </div>
                          </div>
                          {record.escalations.length > 0 && (
                            <div>
                              <span className="text-muted-foreground">{i18n('Escalations')}:</span>
                              <span className="ml-1 text-orange-500">{record.escalations.join(', ')}</span>
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Members */}
        <div>
          <h4 className="text-xs font-medium text-teal-400 mb-2">{i18n('Members')} ({memberExecutions.length})</h4>
          {memberExecutions.length === 0 ? (
            <p className="text-xs text-muted-foreground">{i18n('No member executions yet.')}</p>
          ) : (
            <div className="space-y-1.5">
              {memberExecutions.map((exec: TeamMemberExecutionDto, idx: number) => {
                const cfg = STATUS_CONFIG[exec.status] ?? {
                  icon: <AlertTriangle className="w-3 h-3" />,
                  color: 'text-gray-500',
                  label: exec.status,
                };
                const assignKey = `assign-${exec.memberId}-${exec.round}-${idx}`;
                const isAssignCollapsed = collapsedEntries.has(assignKey);
                return (
                  <div key={`${exec.memberId}-${exec.round}-${idx}`} className="rounded-md border border-border p-2">
                    <div className="flex items-center gap-1.5">
                      <span className={cfg.color}>{cfg.icon}</span>
                      <span className="text-xs font-medium">{exec.memberId}</span>
                      <span className="text-[10px] text-muted-foreground">{i18n('Round')} {exec.round}</span>
                      <span className={`ml-auto text-[10px] font-medium ${cfg.color}`}>{cfg.label}</span>
                    </div>
                    {/* Expandable assignment */}
                    <div
                      className="mt-1 cursor-pointer"
                      onClick={() => toggleEntry(assignKey)}
                    >
                      <div className="flex items-center gap-1">
                        {isAssignCollapsed
                          ? <ChevronRight className="w-2.5 h-2.5 text-muted-foreground shrink-0" />
                          : <ChevronDown className="w-2.5 h-2.5 text-muted-foreground shrink-0" />
                        }
                        <span className="text-[10px] text-muted-foreground">{i18n('Assignment')}</span>
                      </div>
                      {isAssignCollapsed ? (
                        <div className="mt-0.5 text-xs text-foreground/70 line-clamp-2">{exec.assignment}</div>
                      ) : (
                        <div className="mt-0.5 text-xs text-foreground/70 whitespace-pre-wrap">{exec.assignment}</div>
                      )}
                    </div>
                    {exec.summary && (
                      <div className="mt-1 text-xs text-muted-foreground line-clamp-3">{exec.summary}</div>
                    )}
                    {exec.escalationReason && (
                      <div className="mt-1 text-xs text-orange-500">{exec.escalationReason}</div>
                    )}
                    {(exec.inputTokens > 0 || exec.outputTokens > 0) && (
                      <div className="mt-1 text-[10px] text-muted-foreground">
                        {exec.inputTokens + exec.outputTokens} tokens
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Escalations */}
        <div>
          <h4 className="text-xs font-medium text-orange-400 mb-2">{i18n('Escalations')} ({escalationHistory.length})</h4>
          {escalationHistory.length === 0 ? (
            <p className="text-xs text-muted-foreground">{i18n('No escalations.')}</p>
          ) : (
            <div className="space-y-1.5">
              {escalationHistory.map((esc: EscalationEntryDto, idx: number) => (
                <div key={`${esc.memberId}-${esc.round}-${idx}`} className="rounded-md border border-orange-500/20 p-2 bg-orange-500/5">
                  <div className="flex items-center gap-1.5 text-xs">
                    <AlertTriangle className="w-3 h-3 text-orange-500 shrink-0" />
                    <span className="font-medium">{esc.memberId}</span>
                    <span className="text-[10px] text-muted-foreground">{i18n('Round')} {esc.round}</span>
                    {esc.resolution && (
                      <span className="ml-auto text-[10px] text-green-500">{i18n('Resolved')}</span>
                    )}
                  </div>
                  <div className="mt-1 text-xs text-foreground/70">{esc.reason}</div>
                  {esc.resolution && (
                    <div className="mt-1 text-xs text-green-500/80">{esc.resolution}</div>
                  )}
                  {esc.reassignedTo && (
                    <div className="mt-1 text-xs text-muted-foreground">
                      {i18n('Reassigned to')}: {esc.reassignedTo}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
