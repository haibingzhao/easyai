import type { AssistantMessage, Message, ToolCall, ToolResult, UserMessage } from '@/types/message';
import type { QuickAskTurn } from '@/services/quick-ask-service';
import { isUserQuestion } from '@/utils/message-filters';

/** Total budget for the conversation-context block sent to the side panel. */
export const CONTEXT_MAX_CHARS = 8_000;
/** Per-cap on a single context entry (a whole turn side merges into one entry). */
export const CONTEXT_ENTRY_MAX_CHARS = 3_000;
/** Anchor turn plus this many preceding turns. */
export const CONTEXT_MAX_TURNS = 3;
/** Window size when the selection cannot be anchored to a message. */
export const FALLBACK_TURNS = 2;

const ANCHOR_HEAD_CHARS = 1_200;
const ANCHOR_TAIL_CHARS = 1_800;
const ACTIVITY_MAX_NAMES = 6;
const NEEDLE_PROBE_CHARS = 80;
const ELLIPSIS = '…';

export interface SideAskContext {
  context: QuickAskTurn[];
  /** Set when an entry was dropped to fit the budget. */
  truncated: boolean;
  /** Set when an entry was shortened but kept. */
  clipped: boolean;
}

interface Entry {
  role: 'user' | 'assistant';
  text: string;
}

/** Turns are message arrays: a real user message starts one, assistant rounds append to it. */
type Turn = Entry[];

function userEntryText(message: UserMessage): string {
  const markers = (message.attachments ?? []).map(
    (attachment) => `[attachment: ${attachment.name.replace(/\s+/g, ' ')}]`,
  );
  const content = message.content.trim();
  if (markers.length === 0) return content;
  return content ? `${content}\n${markers.join('\n')}` : markers.join('\n');
}

function activityLine(toolCalls: ToolCall[], toolResults: ToolResult[]): string {
  if (toolCalls.length === 0) return '';
  const names = [...new Set(toolCalls.map((call) => call.toolName))];
  const shown = names.slice(0, ACTIVITY_MAX_NAMES).join(', ');
  const extra = names.length > ACTIVITY_MAX_NAMES ? `, +${names.length - ACTIVITY_MAX_NAMES} more` : '';
  const calls = toolCalls.length === 1 ? '1 call' : `${toolCalls.length} calls`;
  const errors = toolResults.filter((result) => result.isError).length;
  const failed = errors === 1 ? '1 error' : `${errors} errors`;
  return `[tool activity: ${shown}${extra} (${calls}${errors > 0 ? `, ${failed}` : ''})]`;
}

/** Visible text plus a one-line tool summary. Args and results never enter the context. */
function assistantEntryText(message: AssistantMessage): string {
  const parts: string[] = [];
  const content = message.content.trim();
  if (content) parts.push(content);
  const activity = activityLine(message.toolCalls ?? [], message.toolResults ?? []);
  if (activity) parts.push(activity);
  return parts.join('\n');
}

function buildTurns(messages: Message[]): { turns: Turn[]; turnOfIndex: Map<number, number> } {
  const turns: Turn[] = [];
  const turnOfIndex = new Map<number, number>();
  let current: Turn | null = null;

  // Assistant rounds emitted before any real user message (background/system head) still need a
  // turn to live in; it then competes for the window like any other.
  const ensureTurn = (): Turn => {
    if (!current) {
      current = [];
      turns.push(current);
    }
    return current;
  };

  messages.forEach((message, index) => {
    if (isUserQuestion(message)) {
      current = [];
      turns.push(current);
      const text = userEntryText(message);
      if (text) current.push({ role: 'user', text });
      turnOfIndex.set(index, turns.length - 1);
      return;
    }
    if (message.role === 'assistant') {
      const turn = ensureTurn();
      turnOfIndex.set(index, turns.length - 1);
      const text = assistantEntryText(message);
      if (!text) return;
      const last = turn[turn.length - 1];
      if (last?.role === 'assistant') {
        last.text = `${last.text}\n\n${text}`;
      } else {
        turn.push({ role: 'assistant', text });
      }
    }
    // tool / error / custom / system-origin user messages neither enter a turn nor end one.
  });

  return { turns, turnOfIndex };
}

function findNeedle(text: string, needle: string): number {
  const trimmed = needle.trim();
  // Rendered text can differ from the stored markdown in a few places, so fall back to a shorter
  // probe before giving up and clipping from the head.
  for (const length of [NEEDLE_PROBE_CHARS, 40]) {
    const probe = trimmed.slice(0, length);
    if (!probe) continue;
    const at = text.indexOf(probe);
    if (at >= 0) return at;
  }
  return -1;
}

/** Clip around the selection for the anchor entry so the passage's surroundings survive. */
function clipAround(text: string, at: number): string {
  const start = Math.max(0, at - ANCHOR_HEAD_CHARS);
  const end = Math.min(text.length, at + ANCHOR_TAIL_CHARS);
  const head = start > 0 ? ELLIPSIS : '';
  const tail = end < text.length ? ELLIPSIS : '';
  const budget = CONTEXT_ENTRY_MAX_CHARS - head.length - tail.length;
  const window = text.slice(start, end);
  return `${head}${window.length > budget ? window.slice(0, budget) : window}${tail}`;
}

function clipEntry(text: string, needle: string | null): { text: string; clipped: boolean } {
  if (text.length <= CONTEXT_ENTRY_MAX_CHARS) return { text, clipped: false };
  const at = needle ? findNeedle(text, needle) : -1;
  return { text: at >= 0 ? clipAround(text, at) : text.slice(0, CONTEXT_ENTRY_MAX_CHARS), clipped: true };
}

/**
 * Snapshot of the main conversation around `selectedText`, frozen when the side panel opens.
 * Oldest first; at most [CONTEXT_MAX_TURNS] turns, at most [CONTEXT_MAX_CHARS] characters.
 */
export function buildSideAskContext(
  messages: Message[],
  anchorIndex: number | null,
  selectedText: string,
): SideAskContext {
  if (messages.length === 0) return { context: [], truncated: false, clipped: false };

  const { turns, turnOfIndex } = buildTurns(messages);
  if (turns.length === 0) return { context: [], truncated: false, clipped: false };

  let anchorOrdinal: number | undefined;
  if (anchorIndex !== null && Number.isFinite(anchorIndex)) {
    const clamped = Math.min(Math.max(Math.trunc(anchorIndex), 0), messages.length - 1);
    anchorOrdinal = turnOfIndex.get(clamped);
  }

  const windowStart = anchorOrdinal === undefined
    ? Math.max(0, turns.length - FALLBACK_TURNS)
    : Math.max(0, anchorOrdinal - (CONTEXT_MAX_TURNS - 1));
  const windowEnd = anchorOrdinal === undefined ? turns.length : anchorOrdinal + 1;
  const selected = turns.slice(windowStart, windowEnd);

  // Without an anchor the newest turn is the one worth protecting.
  const protectedOffset = anchorOrdinal === undefined ? selected.length - 1 : anchorOrdinal - windowStart;

  let truncated = false;
  let clippedAny = false;
  const flattened = selected.flatMap((turn, offset) =>
    turn.map((entry) => {
      // Every entry of the anchor turn gets the selection as its clip target: the passage may live
      // in the user's own (long) message just as well as in the assistant's answer.
      const { text, clipped } = clipEntry(entry.text, offset === protectedOffset ? selectedText : null);
      if (clipped) clippedAny = true;
      return { role: entry.role, text, protected: offset === protectedOffset };
    }),
  );

  let total = flattened.reduce((sum, entry) => sum + entry.text.length, 0);
  for (let i = 0; i < flattened.length && total > CONTEXT_MAX_CHARS; ) {
    if (flattened[i].protected) {
      i++;
      continue;
    }
    total -= flattened[i].text.length;
    flattened.splice(i, 1);
    truncated = true;
  }

  return {
    context: flattened.map((entry) => ({ role: entry.role, content: entry.text })),
    truncated,
    clipped: clippedAny,
  };
}