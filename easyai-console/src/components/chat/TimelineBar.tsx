import React, { useRef, useEffect, useMemo } from 'react';
import type { Message, AssistantMessage, ToolResultMessage, CustomMessage } from '../../types/message';
import { formatDurationMs } from '../../utils/format';

interface TimelineBarProps {
  messages: Message[];
  /** Optional streaming blocks for real-time bar display during SSE streaming */
  streamingBlocks?: Array<{
    type: 'thinking' | 'text' | 'tool' | 'subagent' | 'compaction';
    content?: string;
    isFinished?: boolean;
    durationMs?: number;
    /** Token count from message_end (set after LLM call completes) */
    tokenCount?: number;
    toolCall?: { id: string; toolName: string; args: string; status: string };
    toolResult?: { toolName: string; durationMs?: number | null };
    /** Accumulated token usage for sub-agent blocks */
    accumulatedUsage?: { inputTokens: number; outputTokens: number; cacheReadTokens: number };
    /** Agent name for sub-agent blocks */
    agentName?: string;
    /** Tokens saved by context compaction (compaction blocks) */
    tokensSaved?: number;
    /** Sub-agent data nested inside tool blocks (from chat store ToolBlockData) */
    subAgent?: {
      agentName: string;
      isFinished: boolean;
      accumulatedUsage: { inputTokens: number; outputTokens: number; cacheReadTokens: number };
    };
  }>;
}

const MIN_WIDTH = 4;
const MAX_WIDTH = 24;
const MAX_HEIGHT = 24;
const MIN_HEIGHT = 6;

type BarType = 'reasoning' | 'text' | 'tool' | 'user' | 'error' | 'compact';

interface Bar {
  type: BarType;
  color: string;
  height: number;
  width: number;
  tip: string;
}

const WRITE_TOOLS = new Set(['write', 'edit', 'create_file', 'search_replace', 'apply_patch']);
const READ_TOOLS = new Set(['read', 'read_file', 'grep', 'search', 'list_dir', 'glob']);

function isWriteTool(name: string): boolean {
  return WRITE_TOOLS.has(name.toLowerCase());
}

function isReadTool(name: string): boolean {
  return READ_TOOLS.has(name.toLowerCase());
}

function getToolColor(toolName: string): string {
  if (toolName.startsWith('subagent:')) return 'bg-teal-500';
  if (isWriteTool(toolName)) return 'bg-indigo-500';
  if (isReadTool(toolName)) return 'bg-cyan-500';
  return 'bg-green-600';
}


/** Format token count with unit, e.g. "1.2k tokens" */
function formatTokens(count: number, unit?: string): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M ${unit || "tokens"}`;
  if (count >= 1_000) return `${(count / 1_000).toFixed(1)}k ${unit || "tokens"}`;
  return `${count} ${unit || "tokens"}`;
}

/** Raw bar before size normalization. */
interface RawBar {
  type: BarType;
  /** Content size for height calculation (token count or char length). */
  size: number;
  /** Duration in ms for width calculation (0 means use MIN_WIDTH). */
  duration: number;
  /** Tooltip text. */
  tip: string;
}

/**
 * Expand streaming blocks into raw bars for real-time display.
 */
function expandStreamingBlocks(blocks: NonNullable<TimelineBarProps['streamingBlocks']>): RawBar[] {
  const result: RawBar[] = [];
  for (const block of blocks) {
    if (block.type === 'thinking') {
      const charLen = block.content?.length ?? 0;
      const tokens = block.tokenCount;
      // Use max of chars and tokens for height so bars with different token counts have different heights
      const size = tokens ? Math.max(charLen, tokens) : charLen;
      const tipParts = [`${formatTokens(charLen, "chars")}`];
      if (tokens) tipParts.push(`${formatTokens(tokens)}`);
      result.push({
        type: 'reasoning',
        size,
        duration: block.durationMs ?? 0,
        tip: `reasoning (streaming) | ${tipParts.join(', ')}`,
      });
    } else if (block.type === 'text') {
      const charLen = block.content?.length ?? 0;
      const tokens = block.tokenCount;
      // Prefer token count for size when available (from message_end)
      const size = tokens ?? charLen;
      result.push({
        type: 'text',
        size,
        duration: block.durationMs ?? 0,
        tip: `text (streaming) | ${tokens ? formatTokens(tokens) : `${formatTokens(charLen, "chars")}`}`,
      });
    } else if (block.type === 'tool') {
      if (block.subAgent) {
        // Sub-agent tool block: use accumulatedUsage for size and tooltip
        const usage = block.subAgent.accumulatedUsage;
        const tokens = (usage?.inputTokens ?? 0) + (usage?.outputTokens ?? 0);
        const blockDuration = block.toolResult?.durationMs ?? 0;
        result.push({
          type: 'tool',
          size: tokens || 1,
          duration: blockDuration,
          tip: `subagent: ${block.subAgent.agentName ?? 'unknown'} | ${formatTokens(tokens)}${blockDuration ? `, ${formatDurationMs(blockDuration)}` : ''}`,
        });
      } else {
        result.push({
          type: 'tool',
          size: 0,
          duration: block.toolResult?.durationMs ?? 0,
          tip: block.toolCall?.toolName ?? 'tool (streaming)',
        });
      }
    } else if (block.type === 'subagent') {
      const tokens = (block.accumulatedUsage?.inputTokens ?? 0) + (block.accumulatedUsage?.outputTokens ?? 0);
      result.push({
        type: 'tool',
        size: tokens || 1,
        duration: 0,
        tip: `subagent: ${block.agentName ?? 'unknown'} | ${formatTokens(tokens)}`,
      });
    } else if (block.type === 'compaction') {
      // Skip in-progress compaction blocks (tokensSaved is still 0); the segment appears
      // once compaction_end finalizes the block.
      if (block.isFinished !== false) {
        const tokensSaved = block.tokensSaved ?? 0;
        result.push({
          type: 'compact',
          size: tokensSaved,
          duration: block.durationMs ?? 0,
          tip: `compact | -${formatTokens(tokensSaved)}, ${formatDurationMs(block.durationMs ?? 0)}`,
        });
      }
    }
  }
  return result;
}

/**
 * Expand messages into raw bars.
 * - Assistant: thinking -> reasoning bar, content -> text bar
 * - Tool: each toolResult -> independent bar
 * - User / Error: single bar
 */
function expandMessages(messages: Message[]): RawBar[] {
  const result: RawBar[] = [];

  for (const msg of messages) {
    if (msg.role === 'custom') {
      const c = msg as CustomMessage;
      if (c.customType === 'compaction') {
        const tokensSaved = (c.metadata.tokensSaved as number | undefined) ?? 0;
        result.push({
          type: 'compact',
          size: tokensSaved,
          duration: (c.metadata.durationMs as number | undefined) ?? 0,
          tip: `compact | -${formatTokens(tokensSaved)}, ${formatDurationMs((c.metadata.durationMs as number | undefined) ?? 0)}`,
        });
      }
      continue;
    }

    switch (msg.role) {
      case 'assistant': {
        const a = msg as AssistantMessage;
        const tokens = a.usage?.totalTokens ?? 0;
        const duration = a.usage?.durationMs ?? 0;
        const thinkingDuration = a.thinkingDurationMs ?? duration;
        const textDuration = a.textDurationMs ?? duration;
        const compactedPrefix = a.compactedAt ? '\u2935 [compacted] ' : '';

        if (a.thinking && a.content) {
          // Both: reasoning uses thinking char length, text uses remaining tokens
          const thinkingSize = a.thinking.length;
          const textSize = Math.max(tokens - thinkingSize, 1);
          result.push({
            type: 'reasoning',
            size: thinkingSize,
            duration: thinkingDuration,
            tip: `${compactedPrefix}reasoning | ${formatTokens(thinkingSize, "chars")}, ${formatDurationMs(thinkingDuration)}`,
          });
          result.push({
            type: 'text',
            size: textSize,
            duration: textDuration,
            tip: `${compactedPrefix}text | ${formatTokens(textSize)}, ${formatDurationMs(textDuration)}`,
          });
        } else if (a.thinking) {
          const displayTokens = tokens
          result.push({
            type: 'reasoning',
            size: displayTokens,
            duration: thinkingDuration,
            tip: `${compactedPrefix}reasoning | ${formatTokens(displayTokens)}, ${formatDurationMs(thinkingDuration)}`,
          });
        } else if (a.content) {
          const displayTokens = tokens
          result.push({
            type: 'text',
            size: displayTokens,
            duration: textDuration,
            tip: `${compactedPrefix}text | ${formatTokens(displayTokens)}, ${formatDurationMs(textDuration)}`,
          });
        }
        // Tool results merged into assistant message (from loadSessionMessages)
        if (a.toolResults) {
          for (const tr of a.toolResults) {
            // Compute totalTokens with fallback for old data (backend Usage may not have totalTokens)
            const trTotalTokens = tr.usage?.totalTokens ?? ((tr.usage?.inputTokens ?? 0) + (tr.usage?.outputTokens ?? 0));

            if (trTotalTokens > 0) {
              // New data: usage stored in ToolResultEntry.usage
              const subAgentGroup = a.subAgentMessages?.find(g => g.toolCallId === tr.id);
              const agentName = subAgentGroup?.agentName ?? tr.toolName;
              const trDuration = tr.durationMs ?? 0;
              result.push({
                type: 'tool',
                size: trTotalTokens,
                duration: trDuration,
                tip: `subagent: ${agentName} | ${formatTokens(trTotalTokens)}${trDuration ? `, ${formatDurationMs(trDuration)}` : ''}`,
              });
            } else {
              // Fallback for old data: compute from subAgentMessages
              const subAgentGroup = a.subAgentMessages?.find(g => g.toolCallId === tr.id);
              if (subAgentGroup) {
                const subTokens = subAgentGroup.messages
                  .filter((m): m is AssistantMessage => m.role === 'assistant')
                  .reduce((sum, m) => sum + (m.usage?.totalTokens ?? 0), 0);
                const trDuration = tr.durationMs ?? 0;
                result.push({
                  type: 'tool',
                  size: subTokens || 1,
                  duration: trDuration,
                  tip: `subagent: ${subAgentGroup.agentName} | ${formatTokens(subTokens)}${trDuration ? `, ${formatDurationMs(trDuration)}` : ''}`,
                });
              } else {
                result.push({
                  type: 'tool',
                  size: 0,
                  duration: tr.durationMs ?? 0,
                  tip: tr.toolName,
                });
              }
            }
          }
        }
        // Fallback: neither thinking nor content nor toolResults
        if (!a.thinking && !a.content && !a.toolResults) {
          result.push({
            type: 'text',
            size: tokens || 1,
            duration: textDuration,
            tip: `${compactedPrefix}nothing | ${formatTokens(tokens)}, ${formatDurationMs(textDuration)}`,
          });
        }
        break;
      }

      case 'tool': {
        const t = msg as ToolResultMessage;
        for (const tr of t.toolResults) {
          const trTotalTokens = tr.usage?.totalTokens ?? ((tr.usage?.inputTokens ?? 0) + (tr.usage?.outputTokens ?? 0));
          result.push({
            type: 'tool',
            size: trTotalTokens || 0,
            duration: tr.durationMs ?? 0,
            tip: trTotalTokens > 0
              ? `subagent: ${tr.toolName} | ${formatTokens(trTotalTokens)}`
              : tr.toolName,
          });
        }
        break;
      }

      case 'user':
      case 'user-with-attachments': {
        const u = msg as Extract<Message, { role: 'user' | 'user-with-attachments' }>;
        // Skip system-injected user messages (completion_check, follow_up, steering)
        // These are added by UserMessageAddedEvent during agent loop and should not appear in TimelineBar
        const source = u.metadata?.source;
        if (source === 'completion_check' || source === 'follow_up' || source === 'steering') {
          break;
        }
        result.push({
          type: 'user',
          size: u.content?.length ?? 1,
          duration: 0,
          tip: 'user',
        });
        break;
      }

      case 'error': {
        result.push({
          type: 'error',
          size: 1,
          duration: 0,
          tip: 'error',
        });
        break;
      }
    }
  }

  return result;
}

/**
 * Compute bar color from type and optional tool name.
 */
function barColor(type: BarType, toolName?: string): string {
  switch (type) {
    case 'reasoning':
      return 'bg-purple-500';
    case 'text':
      return 'bg-gray-400 dark:bg-gray-500';
    case 'tool':
      return toolName ? getToolColor(toolName) : 'bg-green-500';
    case 'user':
      return 'bg-blue-500';
    case 'error':
      return 'bg-red-500';
    case 'compact':
      return 'bg-amber-500';
  }
}

/** Normalize raw bars into sized bars. */
function normalizeBars(
  raw: RawBar[],
  messages: Message[],
  streamingBlocks?: NonNullable<TimelineBarProps['streamingBlocks']>
): Bar[] {
  if (raw.length === 0) return [];

  const sizes = raw.map(r => Math.max(r.size, 1));
  const maxSize = Math.max(...sizes, 1);
  const durations = raw.map(r => r.duration);
  const maxDuration = Math.max(...durations, 1);

  // Build a lookup for tool names from tool messages and merged tool results in assistant messages
  const toolNames: string[] = [];
  for (const msg of messages) {
    if (msg.role === 'tool') {
      const t = msg as ToolResultMessage;
      for (const tr of t.toolResults) {
        toolNames.push(tr.toolName);
      }
    } else if (msg.role === 'assistant') {
      const a = msg as AssistantMessage;
      if (a.toolResults) {
        for (const tr of a.toolResults) {
          // Check if this tool result corresponds to a sub-agent call
          const subAgentGroup = a.subAgentMessages?.find(g => g.toolCallId === tr.id);
          toolNames.push(subAgentGroup ? `subagent:${subAgentGroup.agentName}` : tr.toolName);
        }
      }
    }
  }
  // Also include tool names from streaming blocks
  if (streamingBlocks) {
    for (const block of streamingBlocks) {
      if (block.type === 'tool' && block.toolCall) {
        if (block.subAgent) {
          toolNames.push(`subagent:${block.subAgent.agentName ?? 'unknown'}`);
        } else {
          toolNames.push(block.toolCall.toolName);
        }
      } else if (block.type === 'subagent') {
        toolNames.push(`subagent:${block.agentName ?? 'unknown'}`);
      }
    }
  }

  let toolIdx = 0;
  return raw.map((r, idx) => {
    const sizeRatio = sizes[idx] / maxSize;

    // Resolve color first so we can detect subagent for height calculation
    let color: string;
    let isSubagent = false;
    if (r.type === 'tool') {
      const name = toolNames[toolIdx] ?? '';
      color = barColor('tool', name);
      isSubagent = name.startsWith('subagent:');
      toolIdx++;
    } else {
      color = barColor(r.type);
    }

    // Subagent bars (LLM-intensive operations) use variable height like reasoning/text,
    // while regular tools (quick API calls) stay at MIN_HEIGHT
    const height = (r.type === 'tool' && !isSubagent) || r.type === 'user' || r.type === 'error' || r.type === 'compact'
      ? MIN_HEIGHT
      : Math.round(MIN_HEIGHT + sizeRatio * (MAX_HEIGHT - MIN_HEIGHT));

    const width = r.duration > 0
      ? Math.round(MIN_WIDTH + (r.duration / maxDuration) * (MAX_WIDTH - MIN_WIDTH))
      : MIN_WIDTH;

    return { type: r.type, color, height, width, tip: r.tip };
  });
}

export const TimelineBar: React.FC<TimelineBarProps> = ({ messages, streamingBlocks }) => {
  const scrollRef = useRef<HTMLDivElement>(null);

  const bars = useMemo(() => {
    const raw = expandMessages(messages);
    // Append streaming blocks for real-time display
    if (streamingBlocks && streamingBlocks.length > 0) {
      raw.push(...expandStreamingBlocks(streamingBlocks));
    }
    return normalizeBars(raw, messages, streamingBlocks);
  }, [messages, streamingBlocks]);

  // Auto-scroll to latest bar
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollLeft = scrollRef.current.scrollWidth;
    }
  }, [bars.length]);

  if (bars.length === 0) return null;

  return (
    <div
      ref={scrollRef}
      className="flex items-end gap-[2px] overflow-x-auto py-1 scrollbar-none"
      style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
    >
      {bars.map((bar, i) => (
        <div
          key={i}
          className="shrink-0 rounded-sm hover:opacity-80 transition-opacity cursor-default"
          style={{
            width: `${bar.width}px`,
            height: `${MAX_HEIGHT}px`,
          }}
          title={bar.tip}
        >
          <div
            className={`w-full rounded-sm ${bar.color}`}
            style={{
              height: `${bar.height}px`,
              marginTop: `${MAX_HEIGHT - bar.height}px`,
            }}
          />
        </div>
      ))}
    </div>
  );
};
