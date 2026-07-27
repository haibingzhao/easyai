import React, { useRef, useEffect } from 'react';
import { ThinkingBlock } from './ThinkingBlock';
import { CodeBlock } from './CodeBlock';
import type { SubAgentInnerBlock } from './tools/SubAgentPanel';
import { ReadLsGroupedMessage } from './tools/ReadLsGroupedMessage';
import { EditedGroupedMessage } from './tools/EditedGroupedMessage';
import { ToolMessage } from './ToolMessage';
import { CompactionIndicator } from './CompactionIndicator';
import { markdownCodeComponents } from './markdownCodeComponents';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { i18n } from '../../utils/i18n';
import { useProjectStore } from '@/services/stores/project-store';
import type {
  StreamingBlock,
  ToolBlockData,
} from '@/services/stores/chat/types';

interface StreamingMessageProps {
  blocks: StreamingBlock[];
  cancelReason?: string | null;
  streamingToolOutputs?: Record<string, string>;
}

/**
 * A parsed segment of text block content.
 * Complete code blocks (with opening and closing ```) are extracted as 'code' segments
 * with isComplete=true. Unclosed code fences (still being streamed) are also extracted
 * as 'code' segments with isComplete=false, so they bypass ReactMarkdown and avoid
 * component tree restructuring flicker.
 */
interface ContentSegment {
  type: 'code' | 'text';
  language?: string;
  content: string;
  /** Whether the code block has a closing fence (false = still streaming) */
  isComplete?: boolean;
}

/**
 * Parse text block content into segments.
 * Complete code blocks (with both opening and closing ```) are extracted as separate
 * 'code' segments with isComplete=true. Unclosed code fences at the tail are also
 * extracted as 'code' segments with isComplete=false (for streaming without flicker).
 * The remaining text is kept as 'text' segments for ReactMarkdown rendering.
 */
const parseContentSegments = (content: string): ContentSegment[] => {
  const codeBlockRegex = /```(\w*)\n([\s\S]*?)```/g;
  const segments: ContentSegment[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = codeBlockRegex.exec(content)) !== null) {
    // Text before the code block
    if (match.index > lastIndex) {
      segments.push({ type: 'text', content: content.slice(lastIndex, match.index) });
    }
    // Complete code block
    segments.push({ type: 'code', language: match[1] || 'text', content: match[2], isComplete: true });
    lastIndex = match.index + match[0].length;
  }

  // Remaining text (may contain unclosed code fence)
  if (lastIndex < content.length) {
    const remaining = content.slice(lastIndex);
    // Check for unclosed code fence — extract as streaming code segment
    const unclosedMatch = remaining.match(/^```(\w*)\n([\s\S]*)$/);
    if (unclosedMatch) {
      // Text before the unclosed fence (if any)
      segments.push({
        type: 'code',
        language: unclosedMatch[1] || 'text',
        content: unclosedMatch[2],
        isComplete: false,
      });
    } else {
      segments.push({ type: 'text', content: remaining });
    }
  }

  return segments;
};

/**
 * Check if a block is a read or ls tool block
 */
function isReadOrLsTool(block: StreamingBlock): boolean {
  return block.type === 'tool' && (block.toolCall.toolName === 'read' || block.toolCall.toolName === 'ls');
}

/**
 * Check if a block is an edit tool block (write/create is NOT aggregated, rendered standalone)
 */
function isEditTool(block: StreamingBlock): boolean {
  return block.type === 'tool' && block.toolCall.toolName === 'edit';
}

/**
 * Group consecutive read/ls and edit tool blocks together.
 * Returns an array of either single non-grouped blocks or grouped blocks.
 * Group types: 'readLs' for consecutive read/ls, 'edited' for consecutive edit.
 * Note: write (create file) is NOT aggregated — rendered as standalone ToolMessage.
 */
type GroupedBlock =
  | { type: 'single'; block: StreamingBlock }
  | { type: 'group'; items: ToolBlockData[]; groupId: string; groupType: 'readLs' | 'edited' };

function groupToolBlocks(blocks: StreamingBlock[]): GroupedBlock[] {
  const result: GroupedBlock[] = [];
  let currentGroup: ToolBlockData[] = [];
  let currentGroupType: 'readLs' | 'edited' | null = null;
  let groupCounter = 0;

  const flushGroup = () => {
    if (currentGroup.length > 0 && currentGroupType) {
      const prefix = currentGroupType === 'readLs' ? 'read-ls' : 'edited';
      result.push({ type: 'group', items: [...currentGroup], groupId: `${prefix}-group-${groupCounter++}`, groupType: currentGroupType });
      currentGroup = [];
      currentGroupType = null;
    }
  };

  for (const block of blocks) {
    if (isReadOrLsTool(block)) {
      if (currentGroupType !== 'readLs') {
        flushGroup();
        currentGroupType = 'readLs';
      }
      currentGroup.push(block as ToolBlockData);
    } else if (isEditTool(block)) {
      if (currentGroupType !== 'edited') {
        flushGroup();
        currentGroupType = 'edited';
      }
      currentGroup.push(block as ToolBlockData);
    } else {
      flushGroup();
      result.push({ type: 'single', block });
    }
  }

  flushGroup();
  return result;
}

/**
 * Compute which group IDs should be collapsed.
 * A group is collapsed if there's a text or thinking block after it in the sequence.
 */
function computeSupersededGroups(grouped: GroupedBlock[]): Set<string> {
  const superseded = new Set<string>();
  let lastGroupId: string | null = null;

  for (const item of grouped) {
    if (item.type === 'group') {
      lastGroupId = item.groupId;
    } else if (item.type === 'single' && (item.block.type === 'text' || item.block.type === 'thinking')) {
      if (lastGroupId) {
        superseded.add(lastGroupId);
        lastGroupId = null;
      }
    }
  }

  return superseded;
}

export const StreamingMessage: React.FC<StreamingMessageProps> = ({ blocks, cancelReason, streamingToolOutputs }) => {
  const workDir = useProjectStore((s) => s.currentProject?.path || '');

  // Track which groups have been superseded by subsequent text/thinking blocks
  const supersededRef = useRef<Set<string>>(new Set());

  // Update superseded set whenever blocks change
  useEffect(() => {
    const grouped = groupToolBlocks(blocks);
    supersededRef.current = computeSupersededGroups(grouped);
  }, [blocks]);

  const groupedBlocks = groupToolBlocks(blocks);

  return (
    <div className="px-4 flex flex-col gap-3">
      {groupedBlocks.map((grouped) => {
        if (grouped.type === 'single') {
          const block = grouped.block;
          switch (block.type) {
            case 'thinking':
              return block.content.trim() ? (
                <ThinkingBlock key={block.id} content={block.content} isStreaming isFinished={block.isFinished} durationMs={block.durationMs} />
              ) : null;
            case 'text': {
              if (!block.content.trim()) return null;
              const segments = parseContentSegments(block.content);
              return (
                <div key={block.id} className="prose prose-sm dark:prose-invert max-w-none">
                  {segments.map((seg, segIndex) => {
                    if (seg.type === 'code') {
                      const className = seg.language ? `language-${seg.language}` : undefined;
                      return (
                        <CodeBlock key={`code-${segIndex}`} className={className} isStreaming={!seg.isComplete}>
                          {seg.content.replace(/\n$/, '')}
                        </CodeBlock>
                      );
                    }
                    if (!seg.content.trim()) return null;
                    return (
                      <ReactMarkdown
                        key={`text-${segIndex}`}
                        remarkPlugins={[remarkGfm]}
                        components={markdownCodeComponents}
                      >
                        {seg.content}
                      </ReactMarkdown>
                    );
                  })}
                </div>
              );
            }
            case 'tool': {
              const output = streamingToolOutputs?.[block.toolCall.id];
              // Extract prompt from tool args for SubAgentPanel
              let subAgentPrompt: string | undefined;
              if (block.subAgent && block.toolCall.args) {
                try {
                  const parsed = JSON.parse(block.toolCall.args);
                  subAgentPrompt = parsed.prompt;
                } catch { /* ignore */ }
              }
              return (
                <ToolMessage
                  key={block.toolCall.id}
                  toolCall={{
                    id: block.toolCall.id,
                    toolName: block.toolCall.toolName,
                    args: block.toolCall.args,
                  }}
                  result={block.toolResult}
                  status={block.toolCall.status}
                  streamingOutput={output}
                  workDir={workDir}
                  subAgent={block.subAgent ? {
                    agentName: block.subAgent.agentName,
                    toolCallId: block.toolCall.id,
                    prompt: subAgentPrompt,
                    blocks: block.subAgent.blocks as SubAgentInnerBlock[],
                    isFinished: block.subAgent.isFinished,
                    streamingToolOutputs: streamingToolOutputs,
                    errorMessage: (block.toolResult?.isError && block.toolResult.result) ? block.toolResult.result : undefined,
                    accumulatedUsage: block.subAgent.accumulatedUsage,
                  } : undefined}
                />
              );
            }
            case 'compaction':
              return (
                <CompactionIndicator
                  key={block.id}
                  isCompacting={!block.isFinished}
                  message={{
                    role: 'custom',
                    customType: 'compaction',
                    metadata: {
                      compactedCount: block.compactedCount,
                      tokensSaved: block.tokensSaved,
                      durationMs: block.durationMs,
                      currentTokens: block.currentTokens,
                      isCompactionIndicator: true,
                    },
                    timestamp: block.timestamp,
                  }}
                />
              );
            default:
              return null;
          }
        } else {
          // Grouped blocks (read/ls or edit)
          const isSuperseded = supersededRef.current.has(grouped.groupId);
          if (grouped.groupType === 'readLs') {
            return (
              <ReadLsGroupedMessage
                key={grouped.groupId}
                items={grouped.items.map(item => ({
                  toolCall: {
                    id: item.toolCall.id,
                    toolName: item.toolCall.toolName,
                    args: item.toolCall.args,
                  },
                  result: item.toolResult,
                  status: item.toolCall.status,
                  streamingOutput: streamingToolOutputs?.[item.toolCall.id],
                  workDir,
                }))}
                defaultExpanded={!isSuperseded}
              />
            );
          } else {
            return (
              <EditedGroupedMessage
                key={grouped.groupId}
                items={grouped.items.map(item => ({
                  toolCall: {
                    id: item.toolCall.id,
                    toolName: item.toolCall.toolName,
                    args: item.toolCall.args,
                  },
                  result: item.toolResult,
                  status: item.toolCall.status,
                  streamingOutput: streamingToolOutputs?.[item.toolCall.id],
                  workDir,
                }))}
                defaultExpanded={!isSuperseded}
              />
            );
          }
        }
      })}

      {cancelReason && (
        <div className="mt-2">
          <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted text-muted-foreground">
            {i18n(cancelReason)}
          </span>
        </div>
      )}
    </div>
  );
};