import { useState } from 'react';
import { ChevronDown, ChevronRight, Layers, Repeat } from 'lucide-react';
import type { UserMessage } from '../../types/message';
import { i18n } from '@/utils/i18n';

/**
 * One consolidation prompt as it appears in the hidden session of a background task.
 *
 * A run asks two different questions — group these entries, then write these merged bodies — through
 * the same session, and each of its prompts carries a memory dump tens of thousands of characters long.
 * Shown inline that way, the transcript of a task is unusable: the interesting half (what the model
 * answered) sits under a wall of material it was given. So the pass and the prompt number travel in the
 * message metadata the backend already persists, and this renders them as a labelled, collapsed turn.
 */

/** How much of a prompt stays visible before "expand". */
const PREVIEW_CHARS = 160;

type ConsolidationPhase = 'grouping' | 'merge';

export interface ConsolidationTurnInfo {
  phase: ConsolidationPhase;
  /** `position/total` as the backend stamped it; empty when an older row carries no batch. */
  batch: string;
  reask: boolean;
}

/** The turn marker this message carries, or null for an ordinary chat message. */
export function readConsolidationTurn(message: UserMessage): ConsolidationTurnInfo | null {
  const phase = message.metadata?.consolidationPhase;
  if (phase !== 'grouping' && phase !== 'merge') return null;
  return {
    phase,
    batch: message.metadata?.consolidationBatch ?? '',
    reask: message.metadata?.consolidationReask === 'true'
  };
}

function phaseLabel(turn: ConsolidationTurnInfo): string {
  const label = turn.phase === 'grouping' ? i18n('Grouping') : i18n('Merging');
  return turn.batch ? `${label} ${turn.batch}` : label;
}

export function ConsolidationTurn({ message }: { message: UserMessage }) {
  const turn = readConsolidationTurn(message);
  const [expanded, setExpanded] = useState(false);
  const content = message.content ?? '';
  if (!turn) return null;

  return (
    <div className="rounded-lg border border-border bg-muted/30 overflow-hidden">
      <div className="flex items-center gap-2 px-3 py-1.5 text-xs text-muted-foreground">
        <Layers className="w-3.5 h-3.5 shrink-0 text-primary" />
        <span className="font-medium text-foreground">{phaseLabel(turn)}</span>
        {turn.reask && (
          <span className="flex items-center gap-1 px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-600">
            <Repeat className="w-3 h-3" />
            {i18n('Re-ask')}
          </span>
        )}
        <span className="ml-auto shrink-0">{`${content.length} ${i18n('chars')}`}</span>
      </div>
      <button
        onClick={() => setExpanded((open) => !open)}
        className="w-full text-left px-3 pb-2 text-xs hover:bg-muted/50 transition-colors flex items-start gap-1.5"
      >
        {expanded ? (
          <ChevronDown className="w-3.5 h-3.5 shrink-0 mt-0.5 text-muted-foreground" />
        ) : (
          <ChevronRight className="w-3.5 h-3.5 shrink-0 mt-0.5 text-muted-foreground" />
        )}
        {expanded ? (
          <pre className="whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed max-h-[60vh] overflow-y-auto">
            {content}
          </pre>
        ) : (
          <span className="line-clamp-2 text-muted-foreground">
            {content.slice(0, PREVIEW_CHARS)}
            {content.length > PREVIEW_CHARS ? '…' : ''}
          </span>
        )}
      </button>
    </div>
  );
}
