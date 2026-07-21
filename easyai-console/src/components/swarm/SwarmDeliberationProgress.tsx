import React, { useEffect, useState, useRef, useCallback } from 'react';
import { Loader2, ChevronDown, ChevronRight, Scale } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { DeliberationEntryDto, DeliberationHistoryResponseDto } from '@/services/swarm-service';
import { swarmService } from '@/services/swarm-service';
import { formatTokenCount } from '@/utils/format';
import { i18n } from '@/utils/i18n';
import { markdownCodeComponents } from '@/components/chat/markdownCodeComponents';

/** Format milliseconds into human-readable duration like "1h 2m 3s". */
function formatDuration(ms: number): string | null {
  if (ms <= 0) return null;
  const totalSec = Math.round(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const parts: string[] = [];
  if (h > 0) parts.push(`${h}h`);
  if (m > 0) parts.push(`${m}m`);
  if (s > 0 || parts.length === 0) parts.push(`${s}s`);
  return parts.join(' ');
}

const POLL_INTERVAL_MS = 3000;

interface SwarmDeliberationProgressProps {
  runId: string;
  taskId: string;
  taskStatus?: string;
}

export const SwarmDeliberationProgress: React.FC<SwarmDeliberationProgressProps> = ({
  runId,
  taskId,
  taskStatus,
}) => {
  const [data, setData] = useState<DeliberationHistoryResponseDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedRound, setSelectedRound] = useState<number | null>(null);
  const [collapsedEntries, setCollapsedEntries] = useState<Set<string>>(new Set());
  const [showOpeningPrompt, setShowOpeningPrompt] = useState(false);
  const [showVerdictPrompt, setShowVerdictPrompt] = useState(false);
  const [showVerdictResponse, setShowVerdictResponse] = useState(true);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

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
    swarmService.fetchDeliberationHistory(runId, taskId).then((result) => {
      setData(result);
      if (showLoader) setLoading(false);
    }).catch(() => {
      if (showLoader) setLoading(false);
    });
  }, [runId, taskId]);

  // Initial load
  useEffect(() => {
    fetchData(true);
  }, [fetchData]);

  // Poll while task is running
  useEffect(() => {
    if (!isRunning) {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
      return;
    }
    pollRef.current = setInterval(() => fetchData(false), POLL_INTERVAL_MS);
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [isRunning, fetchData]);

  if (loading) {
    return (
      <div className="flex items-center justify-center p-6">
        <Loader2 className="w-4 h-4 animate-spin text-muted-foreground" />
        <span className="ml-2 text-xs text-muted-foreground">{i18n('Loading deliberation history...')}</span>
      </div>
    );
  }

  const history = data?.entries ?? [];
  if (history.length === 0) {
    return (
      <div className="p-4 text-xs text-muted-foreground text-center">
        {i18n('No deliberation data available.')}
      </div>
    );
  }

  // Group entries by round
  const rounds: Record<number, DeliberationEntryDto[]> = {};
  for (const entry of history) {
    if (!rounds[entry.round]) rounds[entry.round] = [];
    rounds[entry.round].push(entry);
  }
  const roundNumbers = Object.keys(rounds).map(Number).sort((a, b) => a - b);

  // Auto-select the first round, or the latest round when running
  const activeRound = selectedRound ?? (isRunning ? roundNumbers[roundNumbers.length - 1] : roundNumbers[0]);
  const activeEntries = rounds[activeRound] ?? [];

  // Extract opening prompt from first entry of round 1
  const openingPrompt = rounds[1]?.[0]?.openingPrompt;

  // Extract round prompts from first entry of current round (round > 1)
  const roundPrompts = activeRound > 1 ? activeEntries[0]?.roundPrompts : undefined;

  // Verdict data
  const verdictPrompt = data?.verdictPrompt;
  const verdictResponse = data?.verdictResponse;
  const isCompleted = taskStatus === 'COMPLETED' || taskStatus === 'FAILED';

  return (
    <div className="flex flex-col min-h-0 flex-1">
      {/* Round selector bar */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border shrink-0">
        <h4 className="text-xs font-medium text-purple-400 shrink-0">
          {i18n('Deliberation Rounds')}
        </h4>
        <div className="relative ml-auto">
          <select
            value={activeRound ?? ''}
            onChange={(e) => setSelectedRound(Number(e.target.value))}
            className="appearance-none text-xs bg-muted/50 border border-border rounded-md pl-2 pr-6 py-1 font-medium text-foreground cursor-pointer hover:bg-muted/80 transition-colors focus:outline-none focus:ring-1 focus:ring-purple-400"
          >
            {roundNumbers.map((rn) => (
              <option key={rn} value={rn}>
                {i18n('Round')} {rn} ({rounds[rn].length})
              </option>
            ))}
          </select>
          <ChevronDown className="absolute right-1.5 top-1/2 -translate-y-1/2 w-3 h-3 text-muted-foreground pointer-events-none" />
        </div>
      </div>

      {/* Content area */}
      <div className="flex-1 min-h-0 overflow-y-auto p-3 space-y-2">
        {/* Opening Prompt (Round 1 only) */}
        {activeRound === 1 && openingPrompt && (
          <div className="rounded-md border border-amber-300 dark:border-amber-700 bg-amber-50/50 dark:bg-amber-950/30">
            <div
              className="flex items-center gap-1.5 p-2 cursor-pointer hover:bg-amber-100/50 dark:hover:bg-amber-900/30 transition-colors rounded-t-md"
              onClick={() => setShowOpeningPrompt(!showOpeningPrompt)}
            >
              {showOpeningPrompt
                ? <ChevronDown className="w-3 h-3 text-amber-600 dark:text-amber-400 shrink-0" />
                : <ChevronRight className="w-3 h-3 text-amber-600 dark:text-amber-400 shrink-0" />
              }
              <span className="text-xs font-medium text-amber-700 dark:text-amber-300">
                {i18n('Judge Opening Prompt')}
              </span>
            </div>
            {showOpeningPrompt && (
              <div className="px-2 pb-2 text-xs text-foreground/80 prose prose-xs dark:prose-invert max-w-none">
                <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                  {openingPrompt}
                </ReactMarkdown>
              </div>
            )}
          </div>
        )}

        {/* Selected round entries */}
        {activeEntries.map((entry, idx) => {
          const totalTokens = entry.inputTokens + entry.outputTokens;
          const duration = formatDuration(entry.durationMs);
          const entryKey = `${entry.agentId}-${idx}`;
          const isCollapsed = collapsedEntries.has(entryKey);
          // Per-participant round prompt (Round 2+)
          const participantPrompt = roundPrompts?.[entry.agentId];
          const promptKey = `prompt-${entryKey}`;
          const isPromptCollapsed = collapsedEntries.has(promptKey);
          return (
            <div key={entryKey} className="rounded-md border border-border">
              {/* Per-participant prompt (Round 2+) */}
              {participantPrompt && (
                <div className="border-b border-border">
                  <div
                    className="flex items-center gap-1.5 px-2 py-1.5 cursor-pointer hover:bg-blue-50/50 dark:hover:bg-blue-950/30 transition-colors"
                    onClick={() => toggleEntry(promptKey)}
                  >
                    {isPromptCollapsed
                      ? <ChevronRight className="w-3 h-3 text-blue-500 shrink-0" />
                      : <ChevronDown className="w-3 h-3 text-blue-500 shrink-0" />
                    }
                    <span className="text-[10px] font-medium text-blue-600 dark:text-blue-400">
                      {i18n('Round Prompt')}
                    </span>
                  </div>
                  {!isPromptCollapsed && (
                    <div className="px-2 pb-1.5 text-[11px] text-foreground/70 prose prose-xs dark:prose-invert max-w-none">
                      <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                        {participantPrompt}
                      </ReactMarkdown>
                    </div>
                  )}
                </div>
              )}
              {/* Participant response header */}
              <div
                className="flex items-center gap-1.5 p-2 cursor-pointer hover:bg-muted/50 transition-colors rounded-t-md"
                onClick={() => toggleEntry(entryKey)}
              >
                {isCollapsed
                  ? <ChevronRight className="w-3 h-3 text-muted-foreground shrink-0" />
                  : <ChevronDown className="w-3 h-3 text-muted-foreground shrink-0" />
                }
                <span className="text-xs font-medium text-purple-600 dark:text-purple-400">
                  {entry.agentId}
                </span>
                {totalTokens > 0 && (
                  <span className="text-[10px] text-muted-foreground">
                    {formatTokenCount(totalTokens)}
                  </span>
                )}
                {duration && (
                  <span className="text-[10px] text-muted-foreground ml-auto">
                    {duration}
                  </span>
                )}
              </div>
              {!isCollapsed && (
                <div className="px-2 pb-2 text-xs text-foreground/80 prose prose-xs dark:prose-invert max-w-none">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                    {entry.response}
                  </ReactMarkdown>
                </div>
              )}
            </div>
          );
        })}

        {/* Verdict section (shown when task completed) */}
        {isCompleted && (verdictPrompt || verdictResponse) && (
          <div className="rounded-md border border-green-300 dark:border-green-700 bg-green-50/50 dark:bg-green-950/30 mt-3">
            <div className="flex items-center gap-1.5 p-2 border-b border-green-200 dark:border-green-800">
              <Scale className="w-3 h-3 text-green-600 dark:text-green-400 shrink-0" />
              <span className="text-xs font-medium text-green-700 dark:text-green-300">
                {i18n('Judge Verdict')}
              </span>
            </div>
            {/* Verdict Prompt */}
            {verdictPrompt && (
              <div className="border-b border-green-200/50 dark:border-green-800/50">
                <div
                  className="flex items-center gap-1.5 px-2 py-1.5 cursor-pointer hover:bg-green-100/50 dark:hover:bg-green-900/30 transition-colors"
                  onClick={() => setShowVerdictPrompt(!showVerdictPrompt)}
                >
                  {showVerdictPrompt
                    ? <ChevronDown className="w-3 h-3 text-green-600 dark:text-green-400 shrink-0" />
                    : <ChevronRight className="w-3 h-3 text-green-600 dark:text-green-400 shrink-0" />
                  }
                  <span className="text-[10px] font-medium text-green-600 dark:text-green-400">
                    {i18n('Verdict Prompt')}
                  </span>
                </div>
                {showVerdictPrompt && (
                  <div className="px-2 pb-1.5 text-[11px] text-foreground/70 prose prose-xs dark:prose-invert max-w-none">
                    <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                      {verdictPrompt}
                    </ReactMarkdown>
                  </div>
                )}
              </div>
            )}
            {/* Verdict Response */}
            {verdictResponse && (
              <div>
                <div
                  className="flex items-center gap-1.5 px-2 py-1.5 cursor-pointer hover:bg-green-100/50 dark:hover:bg-green-900/30 transition-colors"
                  onClick={() => setShowVerdictResponse(!showVerdictResponse)}
                >
                  {showVerdictResponse
                    ? <ChevronDown className="w-3 h-3 text-green-600 dark:text-green-400 shrink-0" />
                    : <ChevronRight className="w-3 h-3 text-green-600 dark:text-green-400 shrink-0" />
                  }
                  <span className="text-[10px] font-medium text-green-600 dark:text-green-400">
                    {i18n('Verdict Response')}
                  </span>
                </div>
                {showVerdictResponse && (
                  <div className="px-2 pb-2 text-xs text-foreground/80 prose prose-xs dark:prose-invert max-w-none">
                    <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                      {verdictResponse}
                    </ReactMarkdown>
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
