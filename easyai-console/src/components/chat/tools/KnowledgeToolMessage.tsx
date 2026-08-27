/**
 * Knowledge tool message rendering component.
 *
 * Unified rendering for knowledge_search / knowledge_read tool calls.
 * - Title row: BookOpen icon + tool name + operation summary
 * - Expandable: shows search results / read content
 */

import { useState } from 'react';
import {
  BookOpen,
  Search,
  FileText,
  ChevronDown,
  AlertTriangle,
} from 'lucide-react';
import type { ToolMessageProps } from './types';

// ---------------------------------------------------------------------------
// Argument parsing
// ---------------------------------------------------------------------------

function parseArgs<T>(args: string): T | null {
  try {
    return JSON.parse(args);
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Tool-level icon mapping
// ---------------------------------------------------------------------------

const TOOL_ICON: Record<string, React.ReactNode> = {
  knowledge_search: <Search className="size-3.5 text-muted-foreground" />,
  knowledge_read:   <FileText className="size-3.5 text-muted-foreground" />,
};

const TOOL_LABEL: Record<string, string> = {
  knowledge_search: 'Search',
  knowledge_read:   'Read',
};

// ---------------------------------------------------------------------------
// Shared sub-components
// ---------------------------------------------------------------------------

function ExecStatusDot({ status }: { status: ToolMessageProps['status'] }) {
  if (status === 'RUNNING' || status === 'PENDING') {
    return <span className="w-2 h-2 rounded-full bg-muted-foreground animate-pulse" />;
  }
  if (status === 'FAILED') {
    return <span className="w-2 h-2 rounded-full bg-destructive" />;
  }
  return <span className="w-2 h-2 rounded-full bg-green-500" />;
}

function truncate(text: string, maxLen: number): string {
  return text.length > maxLen ? text.slice(0, maxLen) + '…' : text;
}

/** Extract plain text output from result or streamingOutput */
function extractResultOutput(result?: ToolMessageProps['result'], streamingOutput?: string): string {
  if (streamingOutput !== undefined) return streamingOutput;
  if (!result) return '';
  if (result.contentBlocks && result.contentBlocks.length > 0) {
    return result.contentBlocks
      .map((block) => {
        if (block.type === 'toolResult') return block.output;
        if (block.type === 'text') return block.text;
        return '';
      })
      .join('');
  }
  return result.result;
}

// ---------------------------------------------------------------------------
// Card container (unified border, header layout)
// ---------------------------------------------------------------------------

interface KnowledgeCardProps {
  toolName: string;
  summary: React.ReactNode;
  status: ToolMessageProps['status'];
  expandable: boolean;
  isExpanded: boolean;
  onToggle: () => void;
  result?: ToolMessageProps['result'];
  streamingOutput?: string;
  children?: React.ReactNode;
}

function KnowledgeCard({ toolName, summary, status, expandable, isExpanded, onToggle, result, streamingOutput, children }: KnowledgeCardProps) {
  const isFailed = status === 'FAILED';
  const output = extractResultOutput(result, streamingOutput);

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* Title row */}
      <div
        className={`p-3 flex items-center justify-between gap-2 transition-colors ${expandable ? 'cursor-pointer hover:bg-muted/50' : ''}`}
        onClick={expandable ? onToggle : undefined}
      >
        <div className="flex items-center gap-2 min-w-0">
          <BookOpen className="size-4 text-amber-500 dark:text-amber-400 shrink-0" />
          <span className="text-sm font-medium text-foreground shrink-0">
            Knowledge {TOOL_LABEL[toolName] ?? toolName}
          </span>
          {TOOL_ICON[toolName]}
          <span className="text-xs text-muted-foreground truncate min-w-0">{summary}</span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <ExecStatusDot status={status} />
          {expandable && (
            <ChevronDown
              className={`size-4 text-muted-foreground transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
            />
          )}
        </div>
      </div>

      {/* Expanded content (hidden on failure — error section below handles it) */}
      {isExpanded && children && !isFailed && (
        <>
          <div className="border-t border-border" />
          {children}
        </>
      )}

      {/* Error output (also respects expand/collapse state) */}
      {isExpanded && isFailed && output && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <div className="flex items-center gap-2 mb-1.5">
              <AlertTriangle className="size-4 text-amber-500" />
              <span className="text-sm font-medium text-amber-600 dark:text-amber-400">Error</span>
            </div>
            <div className="text-sm font-mono whitespace-pre-wrap break-all text-destructive bg-destructive/10 rounded p-2 max-h-[10em] overflow-y-auto">
              {output}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// knowledge_search
// ---------------------------------------------------------------------------

function KnowledgeSearchView({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const parsed = parseArgs<{ query: string; source?: string; kcategory?: string }>(toolCall.args);
  const output = extractResultOutput(result, streamingOutput);

  // Parse search result count from output text
  const matchCount = output.match(/Found (\d+) knowledge entries/)?.[1];

  // Build filter badges
  const filters: string[] = [];
  if (parsed?.source) filters.push(parsed.source);
  if (parsed?.kcategory) filters.push(parsed.kcategory);

  return (
    <KnowledgeCard
      toolName={toolCall.toolName}
      summary={
        <span className="flex items-center gap-1">
          <span className="text-foreground/70">"{parsed?.query ?? '?'}"</span>
          {filters.map((f) => (
            <span key={f} className="bg-amber-500/10 text-amber-600 dark:text-amber-400 px-1 py-0.5 rounded text-xs">
              {f}
            </span>
          ))}
          {matchCount && !expanded && (
            <span className="text-amber-600 dark:text-amber-400 font-medium">· {matchCount} found</span>
          )}
        </span>
      }
      status={status}
      expandable={!!output}
      isExpanded={expanded}
      onToggle={() => setExpanded(!expanded)}
      result={result}
      streamingOutput={streamingOutput}
    >
      {output && (
        <div className="p-3">
          <div className="text-sm font-mono whitespace-pre-wrap break-all text-foreground max-h-[20em] overflow-y-auto">
            {output}
          </div>
        </div>
      )}
    </KnowledgeCard>
  );
}

// ---------------------------------------------------------------------------
// knowledge_read
// ---------------------------------------------------------------------------

function KnowledgeReadView({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const parsed = parseArgs<{ key: string }>(toolCall.args);
  const output = extractResultOutput(result, streamingOutput);
  const isFailed = status === 'FAILED';

  return (
    <KnowledgeCard
      toolName={toolCall.toolName}
      summary={
        <span className="flex items-center gap-1">
          <code className="text-xs bg-muted px-1 py-0.5 rounded text-foreground/80">
            {parsed?.key ?? '?'}
          </code>
        </span>
      }
      status={status}
      expandable={!isFailed && !!output}
      isExpanded={expanded}
      onToggle={() => setExpanded(!expanded)}
      result={result}
      streamingOutput={streamingOutput}
    >
      {output && (
        <div className="p-3">
          <div className="text-sm whitespace-pre-wrap break-words text-foreground bg-muted/40 rounded px-3 py-2 max-h-[20em] overflow-y-auto">
            {truncate(output, 2000)}
          </div>
        </div>
      )}
    </KnowledgeCard>
  );
}

// ---------------------------------------------------------------------------
// Main component: route by toolName
// ---------------------------------------------------------------------------

export function KnowledgeToolMessage(props: ToolMessageProps) {
  switch (props.toolCall.toolName) {
    case 'knowledge_search': return <KnowledgeSearchView {...props} />;
    case 'knowledge_read':   return <KnowledgeReadView {...props} />;
    default:                 return <KnowledgeSearchView {...props} />;
  }
}
