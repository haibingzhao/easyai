/**
 * TeamToolMessage — simplified inline rendering for team coordination tools.
 *
 * - delegate_to_member → single line "→ member: task" + status badge
 * - resume_member      → single line "↩ member: resumed"
 * - wait_for_member_events → collapsed event summary
 */

import { ChevronRight } from 'lucide-react';
import { useState } from 'react';
import type { ToolMessageProps } from './types';
import { CollapsibleSection } from './CollapsibleSection';
import { i18n } from '@/utils/i18n';

function parseArgs(args: string): Record<string, unknown> {
  try {
    return JSON.parse(args) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function StatusDot({ finished, isError }: { finished: boolean; isError: boolean }) {
  if (!finished) return <span className="w-2 h-2 shrink-0 rounded-full bg-blue-400 animate-pulse" />;
  return <span className={`w-2 h-2 shrink-0 rounded-full ${isError ? 'bg-destructive' : 'bg-green-500'}`} />;
}

export function TeamToolMessage({ toolCall, result, status }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const args = parseArgs(toolCall.args);
  const memberId = String(args.memberId ?? args.member ?? 'member');
  const finished = status === 'COMPLETED' || status === 'FAILED';
  const isError = result?.isError ?? status === 'FAILED';

  // ── delegate_to_member ────────────────────────────────────────────
  if (toolCall.toolName === 'delegate_to_member') {
    const task = String(args.task ?? args.assignment ?? '');
    return (
      <div className="flex items-center gap-2 py-0.5 text-sm">
        <span className="text-primary shrink-0">→</span>
        <span className="font-medium text-purple-400 shrink-0">{memberId}</span>
        {task && <span className="text-muted-foreground truncate">: {task}</span>}
        <div className="flex-1" />
        <StatusDot finished={finished} isError={isError} />
        <span className="text-[11px] text-muted-foreground shrink-0">
          {!finished ? i18n('Launching...') : isError ? i18n('Failed') : i18n('Launched')}
        </span>
      </div>
    );
  }

  // ── resume_member ─────────────────────────────────────────────────
  if (toolCall.toolName === 'resume_member') {
    const resolution = String(args.resolution ?? args.message ?? '');
    return (
      <div className="flex items-center gap-2 py-0.5 text-sm">
        <span className="text-amber-400 shrink-0">↩</span>
        <span className="font-medium text-purple-400 shrink-0">{memberId}</span>
        {resolution && <span className="text-muted-foreground truncate">: {resolution}</span>}
        <div className="flex-1" />
        <StatusDot finished={finished} isError={isError} />
        <span className="text-[11px] text-muted-foreground shrink-0">
          {!finished ? i18n('Resuming...') : isError ? i18n('Failed') : i18n('Resumed')}
        </span>
      </div>
    );
  }

  // ── wait_for_member_events ────────────────────────────────────────
  const resultText = result?.result ?? '';
  const hasEvents = resultText.trim().length > 0 && !resultText.includes('No active members');
  return (
    <CollapsibleSection
      defaultCollapsed={!expanded}
      title={
        <button
          onClick={() => setExpanded(!expanded)}
          className="flex items-center gap-2 w-full text-sm py-0.5"
        >
          <ChevronRight className={`w-3.5 h-3.5 shrink-0 transition-transform ${expanded ? 'rotate-90' : ''}`} />
          <span className="text-muted-foreground">
            {!finished
              ? i18n('Waiting for member events...')
              : hasEvents
                ? i18n('Team member events')
                : i18n('No active members')}
          </span>
          <div className="flex-1" />
          {!finished && <span className="w-2 h-2 shrink-0 rounded-full bg-blue-400 animate-pulse" />}
        </button>
      }
    >
      {resultText && (
        <pre className="p-2 text-xs text-muted-foreground whitespace-pre-wrap break-words max-h-60 overflow-y-auto">
          {resultText}
        </pre>
      )}
    </CollapsibleSection>
  );
}
