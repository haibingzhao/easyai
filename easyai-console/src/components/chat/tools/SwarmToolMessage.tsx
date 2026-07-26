/**
 * SwarmToolMessage — Dedicated renderer for the run_swarm tool.
 *
 * Three display modes:
 * - Streaming: progress text + best-effort task status list (via polling)
 * - Completed: structured task list with expandable drill-down
 * - Error: destructive error block
 *
 * Task drill-down reuses existing components:
 * - SINGLE → NodeMessageList (renders ask_question/todo_write/goal via ToolMessageRouter)
 * - TEAM → SwarmTeamProgress
 * - DELIBERATION → SwarmDeliberationProgress
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  Network,
  Loader2,
  CheckCircle2,
  XCircle,
  Clock,
  AlertTriangle,
  PauseCircle,
  ChevronRight,
  ChevronDown,
} from 'lucide-react';
import type { ToolMessageProps } from './types';
import { CollapsibleSection } from './CollapsibleSection';
import { parseSwarmArgs, extractRunId, extractRunIdFromStreaming } from './swarm-parsers';
import { swarmService } from '@/services/swarm-service';
import type { RunDetailResponse, TaskSummary } from '@/services/swarm-service';
import { usePolling } from '@/hooks/usePolling';
import { formatTokenCount } from '@/utils/format';
import { useChatStore } from '@/services/stores/chat-store';
import { NodeMessageList } from '@/components/workflow/NodeMessageList';
import { SwarmTeamProgress } from '@/components/swarm/SwarmTeamProgress';
import { SwarmDeliberationProgress } from '@/components/swarm/SwarmDeliberationProgress';
import { TeamConsultationPanel } from '@/components/swarm/TeamConsultationPanel';
import { useSwarmStore } from '@/services/stores/swarm-store';
import { markdownCodeComponents } from '@/components/chat/markdownCodeComponents';

// ---------------------------------------------------------------------------
// Task status configuration (mirrors WorkflowNode STATUS_CONFIG colors)
// ---------------------------------------------------------------------------

type TaskStatus = TaskSummary['status'];

const TASK_STATUS_CONFIG: Record<TaskStatus, { icon: typeof Clock; color: string }> = {
  PENDING: { icon: Clock, color: 'text-muted-foreground' },
  IN_PROGRESS: { icon: Loader2, color: 'text-blue-500' },
  COMPLETED: { icon: CheckCircle2, color: 'text-green-500' },
  FAILED: { icon: XCircle, color: 'text-red-500' },
  BLOCKED: { icon: AlertTriangle, color: 'text-orange-500' },
  PAUSED: { icon: PauseCircle, color: 'text-purple-500' },
  CANCELLED: { icon: XCircle, color: 'text-gray-500' },
};

// ---------------------------------------------------------------------------
// TaskStatusRow — single task row with expand/collapse drill-down
// ---------------------------------------------------------------------------

function TaskStatusRow({ task, runId }: { task: TaskSummary; runId: string }) {
  const [expanded, setExpanded] = useState(false);
  const config = TASK_STATUS_CONFIG[task.status] ?? TASK_STATUS_CONFIG.PENDING;
  const Icon = config.icon;
  const isSpinning = task.status === 'IN_PROGRESS';

  return (
    <div className="border-b border-border/50 last:border-b-0">
      <div
        className="flex items-center gap-2 py-1.5 px-2 cursor-pointer hover:bg-muted/40 transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded
          ? <ChevronDown className="w-3 h-3 shrink-0 text-muted-foreground" />
          : <ChevronRight className="w-3 h-3 shrink-0 text-muted-foreground" />
        }
        <Icon className={`w-3.5 h-3.5 shrink-0 ${config.color} ${isSpinning ? 'animate-spin' : ''}`} />
        <span className="text-xs font-medium text-foreground truncate">{task.id}</span>
        <span className="text-[10px] px-1 py-px rounded bg-muted text-muted-foreground shrink-0">
          {task.type}
        </span>
        <div className="flex-1" />
        {(task.inputTokens > 0 || task.outputTokens > 0) && (
          <span className="text-[10px] text-muted-foreground shrink-0">
            {task.inputTokens > 0 && `↑${formatTokenCount(task.inputTokens)}`}
            {task.inputTokens > 0 && task.outputTokens > 0 && ' '}
            {task.outputTokens > 0 && `↓${formatTokenCount(task.outputTokens)}`}
          </span>
        )}
      </div>

      {/* Drill-down content */}
      {expanded && (
        <div className="px-2 pb-2 max-h-80 overflow-y-auto border-t border-border/30">
          {task.type === 'TEAM' ? (
            <SwarmTeamProgress runId={runId} taskId={task.id} taskStatus={task.status} />
          ) : task.type === 'DELIBERATION' ? (
            <SwarmDeliberationProgress runId={runId} taskId={task.id} taskStatus={task.status} />
          ) : (
            <NodeMessageList runId={runId} taskId={task.id} taskStatus={task.status} />
          )}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// TaskStatusList — list of task rows
// ---------------------------------------------------------------------------

function TaskStatusList({ tasks, runId }: { tasks: TaskSummary[]; runId: string }) {
  if (tasks.length === 0) return null;
  return (
    <div className="rounded-md border border-border/60 overflow-hidden">
      {tasks.map((task) => (
        <TaskStatusRow key={task.id} task={task} runId={runId} />
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export function SwarmToolMessage({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const { presetName, variables } = parseSwarmArgs(toolCall.args);
  const isRunning = status === 'RUNNING' || status === 'PENDING';
  const isError = result?.isError ?? status === 'FAILED';
  const isFinished = status === 'COMPLETED' || status === 'FAILED';

  // --- Run discovery & polling state ---
  const [runId, setRunId] = useState<string | null>(null);
  const [runDetail, setRunDetail] = useState<RunDetailResponse | null>(null);
  const discoveryAttempted = useRef(false);
  const finalFetchDone = useRef(false);

  // --- Consultation from swarm-store (single source of truth) ---
  const pendingConsultation = useSwarmStore((s) => s.pendingConsultation);
  const clearPendingConsultation = useSwarmStore((s) => s.clearPendingConsultation);
  const consultation = pendingConsultation?.runId === runId ? pendingConsultation : null;

  const updateSwarmRun = useChatStore((s) => s.updateSwarmRun);

  // --- Discover runId from streaming progress text (Phase 4 backend) ---
  useEffect(() => {
    if (runId || !isRunning || !streamingOutput) return;
    const streamingRunId = extractRunIdFromStreaming(streamingOutput);
    if (streamingRunId) {
      setRunId(streamingRunId);
    }
  }, [streamingOutput, isRunning, runId]);

  // --- Best-effort runId discovery via listRuns (streaming, no backend enhancement) ---
  useEffect(() => {
    if (runId || !isRunning || discoveryAttempted.current) return;
    discoveryAttempted.current = true;

    swarmService.listRuns(10, 0).then((runs) => {
      const match = runs
        .filter((r) => r.presetName === presetName && (r.status === 'RUNNING' || r.status === 'PENDING'))
        .sort((a, b) => b.createdAt - a.createdAt)[0];
      if (match) {
        setRunId(match.id);
      }
    }).catch(() => { /* silent — degrade gracefully */ });
  }, [isRunning, presetName, runId]);

  // --- Extract runId from completed result ---
  useEffect(() => {
    if (runId || !result?.result) return;
    const parsed = extractRunId(result.result);
    if (parsed) setRunId(parsed);
  }, [result, runId]);

  // --- Poll run detail while running ---
  const fetchRunDetail = useCallback(() => {
    if (!runId) return;
    swarmService.getRun(runId).then((detail) => {
      if (detail) {
        setRunDetail(detail);
        // Sync to chat-store for Summary panel
        updateSwarmRun(toolCall.id, {
          runId: detail.id,
          presetName: detail.presetName,
          title: detail.title,
          status: detail.status,
          tasks: detail.tasks,
          totalInputTokens: detail.totalInputTokens,
          totalOutputTokens: detail.totalOutputTokens,
        });
      }
    }).catch(() => { /* silent */ });
  }, [runId, toolCall.id, updateSwarmRun]);

  // Poll while running; one-shot fetch on completion
  usePolling(fetchRunDetail, isRunning && !!runId, 3000);

  // --- SSE: listen for team consultation events while running (with reconnection) ---
  useEffect(() => {
    if (!runId || !isRunning) return;
    const disconnect = swarmService.subscribeToRunEventsWithRetry(runId, (event) => {
      if (event.type === 'swarm_team_user_consultation_needed' && event.taskId) {
        const d = event.data as { memberId?: string; question?: string; options?: string[]; allowOther?: boolean };
        if (d.memberId && d.question) {
          useSwarmStore.getState().setPendingConsultation({
            runId,
            taskId: event.taskId,
            consultation: { memberId: d.memberId, question: d.question, options: d.options, allowOther: d.allowOther },
          });
        }
      }
      // Terminal events — fetch final state then disconnect
      if (['swarm_run_completed', 'swarm_run_failed', 'swarm_run_cancelled'].includes(event.type)) {
        fetchRunDetail();
        disconnect();
      }
    });
    return disconnect;
  }, [runId, isRunning, fetchRunDetail]);

  // One-shot final fetch on completion (ref-guarded to avoid stale !runDetail skip)
  useEffect(() => {
    if (isFinished && runId && !finalFetchDone.current) {
      finalFetchDone.current = true;
      fetchRunDetail();
    }
  }, [isFinished, runId, fetchRunDetail]);

  // --- Status display ---
  const statusText = !isFinished ? 'Running...' : isError ? 'Failed' : 'Completed';
  const statusColor = !isFinished
    ? 'text-muted-foreground'
    : isError ? 'text-destructive' : 'text-foreground';
  const statusDotColor = !isFinished
    ? 'bg-muted-foreground animate-pulse'
    : isError ? 'bg-destructive' : 'bg-green-500';

  // --- Variables chips ---
  const variableEntries = Object.entries(variables);

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* Header */}
      <div className="p-3 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <Network className="w-4 h-4 shrink-0 text-cyan-500" />
          <span className="text-sm font-medium text-cyan-600 dark:text-cyan-400 truncate">
            Swarm: {presetName}
          </span>
          {variableEntries.length > 0 && (
            <span className="text-[10px] text-muted-foreground truncate">
              ({variableEntries.map(([k]) => k).join(', ')})
            </span>
          )}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {!isFinished && <Loader2 className="w-3.5 h-3.5 text-muted-foreground animate-spin" />}
          <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
          <span className={`text-xs ${statusColor}`}>{statusText}</span>
        </div>
      </div>

      {/* Streaming progress text */}
      {isRunning && streamingOutput && (
        <div className="px-3 pb-2">
          <p className="text-xs text-muted-foreground truncate">{streamingOutput}</p>
        </div>
      )}

      {/* Team consultation panel (SSE-driven, interactive, synced via swarm-store) */}
      {consultation && (
        <div className="px-3 pb-3">
          <TeamConsultationPanel
            runId={consultation.runId}
            taskId={consultation.taskId}
            consultation={consultation.consultation}
            onResolved={() => { clearPendingConsultation(); fetchRunDetail(); }}
            onClose={clearPendingConsultation}
          />
        </div>
      )}

      {/* Task status list (available when runId discovered) */}
      {runDetail && runDetail.tasks.length > 0 && (
        <div className="px-3 pb-3">
          <TaskStatusList tasks={runDetail.tasks} runId={runDetail.id} />
          {/* Token summary */}
          {(runDetail.totalInputTokens > 0 || runDetail.totalOutputTokens > 0) && (
            <div className="mt-2 text-[10px] text-muted-foreground">
              {runDetail.totalInputTokens > 0 && `↑ ${formatTokenCount(runDetail.totalInputTokens)}`}
              {runDetail.totalInputTokens > 0 && runDetail.totalOutputTokens > 0 && ' · '}
              {runDetail.totalOutputTokens > 0 && `↓ ${formatTokenCount(runDetail.totalOutputTokens)}`}
            </div>
          )}
        </div>
      )}

      {/* Error block */}
      {isError && result?.result && (
        <div className="px-3 pb-3">
          <div className="text-sm text-destructive bg-destructive/5 border border-destructive/20 rounded p-2">
            {result.result}
          </div>
        </div>
      )}

      {/* Completed: full markdown result (collapsed) */}
      {isFinished && !isError && result?.result && (
        <div className="px-3 pb-3">
          <CollapsibleSection
            defaultCollapsed={true}
            title={
              <span className="text-xs text-muted-foreground">Full Result</span>
            }
          >
            <div className="p-3 max-h-96 overflow-y-auto prose prose-sm dark:prose-invert max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                {result.result}
              </ReactMarkdown>
            </div>
          </CollapsibleSection>
        </div>
      )}
    </div>
  );
}
