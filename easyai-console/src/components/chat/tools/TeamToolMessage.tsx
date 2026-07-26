/**
 * TeamToolMessage — simplified inline rendering for team coordination tools.
 *
 * - delegate_to_member → single line "→ member: task" + status badge, click to expand full task
 * - resume_member      → single line "↩ member: resumed", click to expand full resolution
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

/** Expandable single-line row for delegate/resume tool calls. */
function ExpandableMemberRow({
  icon,
  iconColor,
  memberId,
  text,
  finished,
  isError,
  statusLabel,
  errorText,
}: {
  icon: string;
  iconColor: string;
  memberId: string;
  text: string;
  finished: boolean;
  isError: boolean;
  statusLabel: string;
  /** Error message from tool result — shown when the call failed. */
  errorText?: string;
}) {
  const [expanded, setExpanded] = useState(false);
  const hasText = text.length > 0;

  return (
    <div>
      <div
        className={`flex items-center gap-2 py-0.5 text-sm ${hasText ? 'cursor-pointer' : ''}`}
        onClick={hasText ? () => setExpanded(!expanded) : undefined}
      >
        {hasText && (
          <ChevronRight className={`w-3 h-3 shrink-0 text-muted-foreground transition-transform ${expanded ? 'rotate-90' : ''}`} />
        )}
        <span className={`${iconColor} shrink-0`}>{icon}</span>
        <span className="font-medium text-purple-400 shrink-0">{memberId}</span>
        {hasText && !expanded && <span className="text-muted-foreground truncate">: {text}</span>}
        <div className="flex-1" />
        <StatusDot finished={finished} isError={isError} />
        <span className="text-[11px] text-muted-foreground shrink-0">{statusLabel}</span>
      </div>
      {hasText && expanded && (
        <p className="mt-0.5 ml-5 text-xs text-muted-foreground whitespace-pre-wrap break-words">
          {text}
        </p>
      )}
      {isError && errorText && (
        <p className="mt-0.5 ml-5 text-xs text-destructive whitespace-pre-wrap break-words">
          {errorText}
        </p>
      )}
    </div>
  );
}

export function TeamToolMessage({ toolCall, result, status }: ToolMessageProps) {
  const args = parseArgs(toolCall.args);
  const memberId = String(args.memberId ?? args.member ?? 'member');
  const finished = status === 'COMPLETED' || status === 'FAILED';
  const isError = result?.isError ?? status === 'FAILED';

  // ── delegate_to_member ────────────────────────────────────────────
  if (toolCall.toolName === 'delegate_to_member') {
    const task = String(args.task ?? args.assignment ?? '');
    return (
      <ExpandableMemberRow
        icon="→"
        iconColor="text-primary"
        memberId={memberId}
        text={task}
        finished={finished}
        isError={isError}
        statusLabel={!finished ? i18n('Launching...') : isError ? i18n('Failed') : i18n('Launched')}
        errorText={isError ? result?.result : undefined}
      />
    );
  }

  // ── resume_member ─────────────────────────────────────────────────
  if (toolCall.toolName === 'resume_member') {
    const resolution = String(args.resolution ?? args.message ?? '');
    return (
      <ExpandableMemberRow
        icon="↩"
        iconColor="text-amber-400"
        memberId={memberId}
        text={resolution}
        finished={finished}
        isError={isError}
        statusLabel={!finished ? i18n('Resuming...') : isError ? i18n('Failed') : i18n('Resumed')}
        errorText={isError ? result?.result : undefined}
      />
    );
  }

  // ── wait_for_member_events ────────────────────────────────────────
  const resultText = result?.result ?? '';
  const hasEvents = resultText.trim().length > 0 && !resultText.includes('No active members');
  return (
    <CollapsibleSection
      title={
        <span className="flex items-center gap-2 w-full text-sm py-0.5">
          <span className="text-muted-foreground">
            {!finished
              ? i18n('Waiting for member events...')
              : hasEvents
                ? i18n('Team member events')
                : i18n('No active members')}
          </span>
          <div className="flex-1" />
          {!finished && <span className="w-2 h-2 shrink-0 rounded-full bg-blue-400 animate-pulse" />}
        </span>
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
