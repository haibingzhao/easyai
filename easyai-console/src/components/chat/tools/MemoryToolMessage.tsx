/**
 * Memory tool message rendering component.
 *
 * Unified rendering for memory_search / memory_read / memory_write / memory_list tool calls.
 * - Title row: Brain icon + tool name + operation summary
 * - Expandable: shows write content / search results / list output / read content
 */

import { useState } from 'react';
import {
  Brain,
  Search,
  FileText,
  Plus,
  RefreshCw,
  Trash2,
  Layers,
  List,
  ChevronDown,
  AlertTriangle,
} from 'lucide-react';
import type { ToolMessageProps } from './types';
import { i18n } from '@/utils/i18n';

// ---------------------------------------------------------------------------
// Memory type display mapping (apiName -> display name)
// ---------------------------------------------------------------------------

const TYPE_DISPLAY: Record<string, string> = {
  user_preferences: 'User Preferences',
  project_information: 'Project Information',
  development_standards: 'Development Standards',
  task_summary: 'Task Summary',
  experience_lessons: 'Experience & Lessons',
  other: 'Other',
};

function typeDisplay(type?: string): string {
  if (!type) return '';
  return i18n(TYPE_DISPLAY[type] ?? type);
}

// ---------------------------------------------------------------------------
// Argument types
// ---------------------------------------------------------------------------

interface MemoryWriteOp {
  action: string;
  name: string;
  type?: string;
  description?: string;
  content?: string;
  oldText?: string;
}

interface MemoryWriteArgs {
  action?: string;
  name?: string;
  type?: string;
  description?: string;
  content?: string;
  oldText?: string;
  scope?: string;
  operations?: MemoryWriteOp[];
}

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
// Write action colors and icons
// ---------------------------------------------------------------------------

const WRITE_ACTION_META: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  add: {
    label: 'Add',
    color: 'text-green-600 dark:text-green-400 bg-green-50 dark:bg-green-950/40',
    icon: <Plus className="size-3 text-green-500" />,
  },
  update: {
    label: 'Update',
    color: 'text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-950/40',
    icon: <RefreshCw className="size-3 text-blue-500" />,
  },
  remove: {
    label: 'Remove',
    color: 'text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-950/40',
    icon: <Trash2 className="size-3 text-red-500" />,
  },
  batch: {
    label: 'Batch',
    color: 'text-violet-600 dark:text-violet-400 bg-violet-50 dark:bg-violet-950/40',
    icon: <Layers className="size-3 text-violet-500" />,
  },
};

// ---------------------------------------------------------------------------
// Tool-level icon mapping
// ---------------------------------------------------------------------------

const TOOL_ICON: Record<string, React.ReactNode> = {
  memory_search: <Search className="size-3.5 text-muted-foreground" />,
  memory_read:   <FileText className="size-3.5 text-muted-foreground" />,
  memory_write:  <Plus className="size-3.5 text-muted-foreground" />,
  memory_list:   <List className="size-3.5 text-muted-foreground" />,
};

const TOOL_LABEL: Record<string, string> = {
  memory_search: 'Search',
  memory_read:   'Read',
  memory_write:  'Write',
  memory_list:   'List',
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

interface MemoryCardProps {
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

function MemoryCard({ toolName, summary, status, expandable, isExpanded, onToggle, result, streamingOutput, children }: MemoryCardProps) {
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
          <Brain className="size-4 text-teal-500 dark:text-teal-400 shrink-0" />
          <span className="text-sm font-medium text-foreground shrink-0">
            Memory {TOOL_LABEL[toolName] ?? toolName}
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

      {/* Expanded content */}
      {isExpanded && children && (
        <>
          <div className="border-t border-border" />
          {children}
        </>
      )}

      {/* Error output */}
      {isFailed && output && (
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
// memory_search
// ---------------------------------------------------------------------------

function MemorySearchView({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const parsed = parseArgs<{ query: string }>(toolCall.args);
  const output = extractResultOutput(result, streamingOutput);

  // Parse search result count from output text
  const matchCount = output.match(/Found (\d+) memories/)?.[1];

  return (
    <MemoryCard
      toolName={toolCall.toolName}
      summary={
        <span className="flex items-center gap-1">
          <span className="text-foreground/70">"{parsed?.query ?? '?'}"</span>
          {matchCount && !expanded && (
            <span className="text-teal-600 dark:text-teal-400 font-medium">· {matchCount} found</span>
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
    </MemoryCard>
  );
}

// ---------------------------------------------------------------------------
// memory_read
// ---------------------------------------------------------------------------

function MemoryReadView({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const parsed = parseArgs<{ path: string }>(toolCall.args);
  const output = extractResultOutput(result, streamingOutput);
  const isFailed = status === 'FAILED';

  return (
    <MemoryCard
      toolName={toolCall.toolName}
      summary={
        <span className="flex items-center gap-1">
          <code className="text-xs bg-muted px-1 py-0.5 rounded text-foreground/80">
            {parsed?.path ?? '?'}
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
    </MemoryCard>
  );
}

// ---------------------------------------------------------------------------
// memory_write
// ---------------------------------------------------------------------------

function ActionBadge({ action }: { action: string }) {
  const meta = WRITE_ACTION_META[action] ?? WRITE_ACTION_META.add;
  return (
    <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-xs font-medium ${meta.color}`}>
      {meta.icon}
      {meta.label}
    </span>
  );
}

function MemoryWriteView({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const parsed = parseArgs<MemoryWriteArgs>(toolCall.args);
  const isFailed = status === 'FAILED';

  // Determine if batch mode
  const isBatch = !!(parsed?.operations && parsed.operations.length > 0);
  const ops = parsed?.operations ?? [];

  // Single mode action
  const singleAction = parsed?.action ?? '';
  const singleName = parsed?.name ?? '';

  // Determine if expandable: when content / description / operations exist
  const detailContent = isBatch
    ? true
    : parsed?.content || parsed?.description;

  return (
    <MemoryCard
      toolName={toolCall.toolName}
      summary={
        <span className="flex items-center gap-1.5">
          {isBatch ? (
            <>
              <ActionBadge action="batch" />
              <span className="text-foreground/70">{ops.length} operations</span>
            </>
          ) : (
            <>
              <ActionBadge action={singleAction} />
              <span className="text-foreground/80 font-medium">{singleName}</span>
              {parsed?.scope && (
                <span className="text-muted-foreground/60">({parsed.scope})</span>
              )}
            </>
          )}
        </span>
      }
      status={status}
      expandable={!isFailed && !!detailContent}
      isExpanded={expanded}
      onToggle={() => setExpanded(!expanded)}
      result={result}
      streamingOutput={streamingOutput}
    >
      {expanded && (
        <div className="p-3 space-y-2">
          {isBatch ? (
            /* Batch operations list */
            <div className="space-y-1.5">
              {ops.map((op, i) => {
                const meta = WRITE_ACTION_META[op.action] ?? WRITE_ACTION_META.add;
                return (
                  <div key={i} className="flex items-start gap-2 text-sm bg-muted/40 rounded px-3 py-2">
                    <span className={`mt-0.5 ${meta.color.split(' ')[0]}`}>
                      {meta.icon}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-1.5">
                        <span className="font-medium text-foreground">{op.name}</span>
                        {op.type && (
                          <span className="text-xs text-muted-foreground bg-muted px-1 rounded">
                            {typeDisplay(op.type)}
                          </span>
                        )}
                      </div>
                      {op.description && (
                        <div className="text-xs text-muted-foreground mt-0.5">
                          {truncate(op.description, 120)}
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            /* Single operation detail */
            <>
              {/* Type + description */}
              {parsed?.type && (
                <div className="text-xs text-muted-foreground">
                  <span className="font-medium">Type: </span>
                  <span className="bg-muted px-1 rounded">{typeDisplay(parsed.type)}</span>
                  {parsed.scope && (
                    <>
                      <span className="mx-2">·</span>
                      <span className="font-medium">Scope: </span>
                      <span>{parsed.scope}</span>
                    </>
                  )}
                </div>
              )}
              {parsed?.description && (
                <div className="text-xs text-muted-foreground">
                  <span className="font-medium">Description: </span>
                  {parsed.description}
                </div>
              )}
              {/* Main content */}
              {parsed?.content && (
                <div className="text-sm whitespace-pre-wrap break-words text-foreground bg-muted/40 rounded px-3 py-2 max-h-[15em] overflow-y-auto">
                  {truncate(parsed.content, 1000)}
                </div>
              )}
            </>
          )}
        </div>
      )}
    </MemoryCard>
  );
}

// ---------------------------------------------------------------------------
// memory_list
// ---------------------------------------------------------------------------

function MemoryListView({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const parsed = parseArgs<{ type?: string }>(toolCall.args);
  const output = extractResultOutput(result, streamingOutput);

  // Parse total entry count from output
  const scopeMatches = output.match(/\((\d+) entries\)/g);
  const totalEntries = scopeMatches
    ? scopeMatches.reduce((sum, m) => sum + (parseInt(m.match(/\d+/)?.[0] ?? '0', 10)), 0)
    : 0;

  return (
    <MemoryCard
      toolName={toolCall.toolName}
      summary={
        <span className="flex items-center gap-1.5">
          {parsed?.type && (
            <span className="bg-muted px-1.5 py-0.5 rounded text-xs text-foreground/70">
              {parsed.type}
            </span>
          )}
          {totalEntries > 0 && !expanded && (
            <span className="text-teal-600 dark:text-teal-400 text-xs font-medium">
              {totalEntries} entries
            </span>
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
    </MemoryCard>
  );
}

// ---------------------------------------------------------------------------
// Main component: route by toolName
// ---------------------------------------------------------------------------

export function MemoryToolMessage(props: ToolMessageProps) {
  switch (props.toolCall.toolName) {
    case 'memory_search': return <MemorySearchView {...props} />;
    case 'memory_read':   return <MemoryReadView {...props} />;
    case 'memory_write':  return <MemoryWriteView {...props} />;
    case 'memory_list':   return <MemoryListView {...props} />;
    default:              return <MemorySearchView {...props} />;
  }
}
