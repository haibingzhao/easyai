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
  Clock,
  History,
  CheckCircle2,
} from 'lucide-react';
import type { ToolMessageProps } from './types';
import { getToolRowSummary, parseMemoryDocument } from './parsers';
import { ToolRowHeader } from './ToolRowHeader';
import { useStreamingRowExpand } from './useStreamingRowExpand';
import { TextMarkdown } from '../segments/TextMarkdown';
import { useCategoryStore } from '@/services/stores/category-store';
import type { CategorySpec } from '@/types/category';
import { i18n } from '@/utils/i18n';

// ---------------------------------------------------------------------------
// Memory type display (apiName -> the active domain's label)
// ---------------------------------------------------------------------------

function typeDisplay(type: string | undefined, memoryCategories: CategorySpec[]): string {
  if (!type) return '';
  // Unknown code = an entry written under another application's taxonomy; show the code itself.
  return i18n(memoryCategories.find((c) => c.code === type)?.labelKey ?? type);
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

interface MemoryReadArgs {
  path?: string;
  docIds?: string[];
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
  /** Single-line summary for the compact row header */
  rowSummary?: string;
  status: ToolMessageProps['status'];
  expandable: boolean;
  isExpanded: boolean;
  onToggle: () => void;
  result?: ToolMessageProps['result'];
  streamingOutput?: string;
  compact?: boolean;
  children?: React.ReactNode;
}

function MemoryCard({ toolName, summary, rowSummary, status, expandable, isExpanded, onToggle, result, streamingOutput, compact, children }: MemoryCardProps) {
  const isFailed = status === 'FAILED';
  const output = extractResultOutput(result, streamingOutput);

  return (
    <div className={compact ? 'overflow-hidden' : 'border border-border rounded-lg bg-card overflow-hidden'}>
      {/* Title row */}
      {compact ? (
        <ToolRowHeader
          toolName={toolName}
          status={status ?? 'PENDING'}
          summary={rowSummary}
          expanded={isExpanded}
          expandable={expandable}
          onToggle={onToggle}
        />
      ) : (
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
      )}

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
// Structured tool output (ToolResult.details)
// ---------------------------------------------------------------------------

/**
 * Memory tools answer the model in prose and the UI in `details`. The prose carries no contract — it
 * can be reworded or localized at any time — so nothing here parses it; a result without details just
 * renders as text.
 */
type UnknownRecord = Record<string, unknown>;

function asRecord(value: unknown): UnknownRecord | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as UnknownRecord)
    : null;
}

function recordsOf(value: unknown): UnknownRecord[] {
  if (!Array.isArray(value)) return [];
  return value.map(asRecord).filter((item): item is UnknownRecord => item !== null);
}

function readString(rec: UnknownRecord, key: string): string {
  const value = rec[key];
  return typeof value === 'string' ? value : '';
}

function readStringOrNull(rec: UnknownRecord, key: string): string | null {
  const value = rec[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function readNumberOrNull(rec: UnknownRecord, key: string): number | null {
  const value = rec[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** `{n} days`, or a literal "unknown" — the backend sends a count, never a precomposed tail. */
function formatDays(count: number | null): string {
  return count == null ? i18n('unknown') : `${count} ${i18n('days')}`;
}

/** Freshness tail shared by the search hit and the list row: `2026-01-15 (3 days ago)`. */
function formatFreshness(updated: string | null, daysSince: number | null): string {
  if (!updated) return i18n('unknown');
  if (daysSince == null) return updated;
  return `${updated} (${daysSince} ${i18n('days ago')})`;
}

interface MemorySearchHit {
  scope: string;
  path: string;
  name: string;
  description: string;
  updated: string | null;
  daysSinceUpdate: number | null;
  maturity: string | null;
  level: number | null;
  content: string;
}

function toSearchHit(rec: UnknownRecord): MemorySearchHit {
  return {
    scope: readString(rec, 'scope').toLowerCase(),
    path: readString(rec, 'path'),
    name: readString(rec, 'name'),
    description: readString(rec, 'description'),
    updated: readStringOrNull(rec, 'updated'),
    daysSinceUpdate: readNumberOrNull(rec, 'daysSinceUpdate'),
    maturity: readStringOrNull(rec, 'maturity'),
    level: readNumberOrNull(rec, 'level'),
    content: readString(rec, 'contentPreview'),
  };
}

/** Structured hits, or null when this result predates the detail channel / the store returned none. */
function readSearchHits(result?: ToolMessageProps['result']): MemorySearchHit[] | null {
  const hits = recordsOf(result?.details?.hits);
  return hits.length > 0 ? hits.map(toSearchHit) : null;
}

const SCOPE_BADGE: Record<string, string> = {
  project: 'bg-teal-50 text-teal-700 dark:bg-teal-950/40 dark:text-teal-300',
  global: 'bg-violet-50 text-violet-700 dark:bg-violet-950/40 dark:text-violet-300',
};

const MATURITY_DOT: Record<string, string> = {
  low: 'bg-orange-400',
  medium: 'bg-amber-400',
  high: 'bg-green-500',
};

function scopeLabel(scope: string): string {
  if (scope === 'project') return i18n('Project');
  if (scope === 'global') return i18n('Global');
  return scope;
}

function maturityLabel(maturity: string | null): string {
  if (!maturity) return i18n('Unset');
  return i18n(maturity.charAt(0).toUpperCase() + maturity.slice(1));
}

function MemoryHitCard({ hit }: { hit: MemorySearchHit }) {
  return (
    <div className="rounded-md border border-border bg-muted/30 overflow-hidden">
      <div className="px-3 pt-2 pb-1.5 space-y-1">
        <div className="flex items-baseline gap-1.5 flex-wrap min-w-0">
          <span
            className={`self-center shrink-0 px-1.5 py-px rounded text-[11px] font-medium ${SCOPE_BADGE[hit.scope] ?? 'bg-muted text-muted-foreground'}`}
          >
            {scopeLabel(hit.scope)}
          </span>
          <span className="text-sm font-medium text-foreground break-words">{hit.name}</span>
          {(hit.level ?? 0) > 0 && (
            <span className="shrink-0 text-[10px] text-violet-600 dark:text-violet-400">
              {i18n('Level')} {hit.level}
            </span>
          )}
        </div>
        <code className="block text-[11px] text-muted-foreground truncate" title={hit.path}>
          {hit.path}
        </code>
        {hit.description && (
          <div className="text-xs text-muted-foreground break-words">{hit.description}</div>
        )}
        <div className="flex items-center gap-3 flex-wrap text-[11px] text-muted-foreground pt-0.5">
          <span className="inline-flex items-center gap-1">
            <Clock className="size-3" />
            {i18n('Updated')}: {formatFreshness(hit.updated, hit.daysSinceUpdate)}
          </span>
          <span className="inline-flex items-center gap-1">
            <span className={`size-1.5 rounded-full ${MATURITY_DOT[hit.maturity ?? ''] ?? 'bg-muted-foreground/50'}`} />
            {i18n('Maturity')}: {maturityLabel(hit.maturity)}
          </span>
        </div>
      </div>
      {hit.content && (
        <div className="px-3 py-2 border-t border-border/60 text-foreground/90 max-h-[16em] overflow-y-auto">
          <TextMarkdown content={hit.content} />
        </div>
      )}
    </div>
  );
}

function MemorySearchView({ toolCall, result, status, streamingOutput, compact }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const markTouched = useStreamingRowExpand(status, setExpanded);
  const parsed = parseArgs<{ query: string }>(toolCall.args);
  const output = extractResultOutput(result, streamingOutput);
  const hits = readSearchHits(result);

  return (
    <MemoryCard
      toolName={toolCall.toolName}
      rowSummary={getToolRowSummary(toolCall.toolName, toolCall.args)}
      summary={
        <span className="flex items-center gap-1">
          <span className="text-foreground/70">"{parsed?.query ?? '?'}"</span>
          {hits && !expanded && (
            <span className="text-teal-600 dark:text-teal-400 font-medium">· {hits.length} {i18n('found')}</span>
          )}
        </span>
      }
      status={status}
      expandable={!!output}
      isExpanded={expanded}
      onToggle={() => { markTouched(); setExpanded(prev => !prev); }}
      result={result}
      streamingOutput={streamingOutput}
      compact={compact}
    >
      {output && (
        hits ? (
          <div className="p-3 space-y-2 max-h-[24em] overflow-y-auto">
            {hits.map((hit, i) => (
              <MemoryHitCard key={`${hit.scope}:${hit.path}:${i}`} hit={hit} />
            ))}
          </div>
        ) : (
          <div className="p-3 text-sm whitespace-pre-wrap break-words text-muted-foreground">
            {output}
          </div>
        )
      )}
    </MemoryCard>
  );
}

// ---------------------------------------------------------------------------
// memory_read
// ---------------------------------------------------------------------------

/**
 * Both read modes label themselves in the row: a drill-down call carries docIds and has no path, and a
 * row that falls back to "?" reads like a malformed call.
 */
function readArgsLabel(args: MemoryReadArgs | null): string {
  if (args?.path) return args.path;
  const ids = args?.docIds?.filter((id) => id.trim().length > 0) ?? [];
  if (ids.length > 0) return `${ids.length} ${i18n('archived sources')}`;
  return '?';
}

function MemoryReadView({ toolCall, result, status, streamingOutput, compact }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const markTouched = useStreamingRowExpand(status, setExpanded);
  const parsed = parseArgs<MemoryReadArgs>(toolCall.args);
  const output = extractResultOutput(result, streamingOutput);
  const { fields, tags, body } = parseMemoryDocument(output);
  const hasMeta = Object.keys(fields).length > 0;

  return (
    <MemoryCard
      toolName={toolCall.toolName}
      rowSummary={getToolRowSummary(toolCall.toolName, toolCall.args)}
      summary={
        <span className="flex items-center gap-1">
          <code className="text-xs bg-muted px-1 py-0.5 rounded text-foreground/80">
            {readArgsLabel(parsed)}
          </code>
        </span>
      }
      status={status}
      expandable={!!output}
      isExpanded={expanded}
      onToggle={() => { markTouched(); setExpanded(prev => !prev); }}
      result={result}
      streamingOutput={streamingOutput}
      compact={compact}
    >
      {output && (
        <div className="p-3 max-h-[24em] overflow-y-auto space-y-2">
          {hasMeta && (
            <div className="rounded-md border border-border bg-muted/30 px-3 py-2 space-y-1">
              {fields.name && (
                <div className="text-sm font-medium text-foreground break-words">{fields.name}</div>
              )}
              {fields.description && (
                <div className="text-xs text-muted-foreground break-words">{fields.description}</div>
              )}
              <div className="flex items-center gap-3 flex-wrap text-[11px] text-muted-foreground pt-0.5">
                {fields.type && (
                  <span className="px-1.5 py-px rounded bg-muted text-muted-foreground">{fields.type}</span>
                )}
                {fields.maturity && (
                  <span className="inline-flex items-center gap-1">
                    <span className={`size-1.5 rounded-full ${MATURITY_DOT[fields.maturity] ?? 'bg-muted-foreground/50'}`} />
                    {i18n('Maturity')}: {maturityLabel(fields.maturity)}
                  </span>
                )}
                {fields.updated && (
                  <span className="inline-flex items-center gap-1">
                    <Clock className="size-3" />
                    {i18n('Updated')}: {fields.updated}
                  </span>
                )}
                {fields.last_accessed && (
                  <span className="inline-flex items-center gap-1">
                    <History className="size-3" />
                    {i18n('Last accessed')}: {fields.last_accessed}
                  </span>
                )}
              </div>
              {tags.length > 0 && (
                <div className="flex items-center gap-1 flex-wrap pt-0.5">
                  {tags.map((tag, i) => (
                    <span key={i} className="px-1.5 py-px rounded text-[11px] bg-muted text-muted-foreground">
                      {tag}
                    </span>
                  ))}
                </div>
              )}
            </div>
          )}
          {body.trim() && <TextMarkdown content={body} />}
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

function MemoryWriteView({ toolCall, result, status, streamingOutput, compact }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const markTouched = useStreamingRowExpand(status, setExpanded);
  const memoryCategories = useCategoryStore((s) => s.memoryCategories);
  const parsed = parseArgs<MemoryWriteArgs>(toolCall.args);
  const output = extractResultOutput(result, streamingOutput);
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
      rowSummary={getToolRowSummary(toolCall.toolName, toolCall.args)}
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
      expandable={!!output || !!detailContent}
      isExpanded={expanded}
      onToggle={() => { markTouched(); setExpanded(prev => !prev); }}
      result={result}
      streamingOutput={streamingOutput}
      compact={compact}
    >
      <div className="p-3 space-y-2">
        {output && !isFailed && (
          <div className="flex items-start gap-1.5 text-xs text-green-700 dark:text-green-400 bg-green-50 dark:bg-green-950/30 rounded px-2.5 py-1.5 whitespace-pre-wrap break-words">
            <CheckCircle2 className="size-3.5 mt-px shrink-0" />
            <span>{output}</span>
          </div>
        )}
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
                            {typeDisplay(op.type, memoryCategories)}
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
                  <span className="bg-muted px-1 rounded">{typeDisplay(parsed.type, memoryCategories)}</span>
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
    </MemoryCard>
  );
}

// ---------------------------------------------------------------------------
// memory_list
// ---------------------------------------------------------------------------

interface MemoryListEntry {
  name: string;
  type: string;
  description: string;
  path: string;
  updated: string | null;
  maturity: string | null;
  level: number | null;
  unusedDays: number | null;
}

interface MemoryListReviewItem {
  name: string;
  type: string;
  unusedDays: number | null;
}

interface MemoryListScope {
  scope: string;
  count: number;
  entries: MemoryListEntry[];
  reviewItems: MemoryListReviewItem[];
  staleAfterDays: number | null;
}

function toListEntry(rec: UnknownRecord): MemoryListEntry {
  return {
    name: readString(rec, 'name'),
    type: readString(rec, 'type'),
    description: readString(rec, 'description'),
    path: readString(rec, 'path'),
    updated: readStringOrNull(rec, 'updated'),
    maturity: readStringOrNull(rec, 'maturity'),
    level: readNumberOrNull(rec, 'level'),
    unusedDays: readNumberOrNull(rec, 'unusedDays'),
  };
}

function toListScope(rec: UnknownRecord): MemoryListScope {
  return {
    scope: readString(rec, 'scope').toLowerCase(),
    count: readNumberOrNull(rec, 'count') ?? 0,
    entries: recordsOf(rec['entries']).map(toListEntry),
    reviewItems: recordsOf(rec['reviewCandidates']).map((item) => ({
      name: readString(item, 'name'),
      type: readString(item, 'type'),
      unusedDays: readNumberOrNull(item, 'unusedDays'),
    })),
    staleAfterDays: readNumberOrNull(rec, 'staleAfterDays'),
  };
}

/** Structured scopes, or null when this result predates the detail channel / listed nothing. */
function readListScopes(result?: ToolMessageProps['result']): MemoryListScope[] | null {
  const scopes = recordsOf(result?.details?.scopes).map(toListScope);
  return scopes.length > 0 ? scopes : null;
}

function MemoryListRow({ entry }: { entry: MemoryListEntry }) {
  return (
    <div className="rounded-md border border-border bg-muted/30 px-3 py-2 space-y-0.5">
      <div className="flex items-baseline gap-1.5 flex-wrap min-w-0">
        <span className="text-sm font-medium text-foreground break-words">{entry.name}</span>
        <span className="shrink-0 px-1.5 py-px rounded text-[11px] bg-muted text-muted-foreground">{entry.type}</span>
        {(entry.level ?? 0) > 0 && (
          <span className="shrink-0 text-[10px] text-violet-600 dark:text-violet-400">
            {i18n('Level')} {entry.level}
          </span>
        )}
      </div>
      {entry.description && (
        <div className="text-xs text-muted-foreground break-words">{entry.description}</div>
      )}
      <div className="flex items-center gap-3 flex-wrap text-[11px] text-muted-foreground pt-0.5">
        {entry.path && (
          <code className="truncate max-w-[45%]" title={entry.path}>{entry.path}</code>
        )}
        {entry.updated && (
          <span className="inline-flex items-center gap-1">
            <Clock className="size-3" />
            {i18n('Updated')}: {entry.updated}
          </span>
        )}
        {entry.maturity && (
          <span className="inline-flex items-center gap-1">
            <span className={`size-1.5 rounded-full ${MATURITY_DOT[entry.maturity] ?? 'bg-muted-foreground/50'}`} />
            {i18n('Maturity')}: {maturityLabel(entry.maturity)}
          </span>
        )}
        <span className="inline-flex items-center gap-1">
          <History className="size-3" />
          {i18n('Unused for')}: {formatDays(entry.unusedDays)}
        </span>
      </div>
    </div>
  );
}

function MemoryListView({ toolCall, result, status, streamingOutput, compact }: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);
  const markTouched = useStreamingRowExpand(status, setExpanded);
  const parsed = parseArgs<{ type?: string }>(toolCall.args);
  const output = extractResultOutput(result, streamingOutput);
  const scopes = readListScopes(result);
  const totalEntries = scopes?.reduce((sum, scope) => sum + scope.count, 0) ?? 0;

  return (
    <MemoryCard
      toolName={toolCall.toolName}
      rowSummary={getToolRowSummary(toolCall.toolName, toolCall.args)}
      summary={
        <span className="flex items-center gap-1.5">
          {parsed?.type && (
            <span className="bg-muted px-1.5 py-0.5 rounded text-xs text-foreground/70">
              {parsed.type}
            </span>
          )}
          {totalEntries > 0 && !expanded && (
            <span className="text-teal-600 dark:text-teal-400 text-xs font-medium">
              {totalEntries} {i18n('entries')}
            </span>
          )}
        </span>
      }
      status={status}
      expandable={!!output}
      isExpanded={expanded}
      onToggle={() => { markTouched(); setExpanded(prev => !prev); }}
      result={result}
      streamingOutput={streamingOutput}
      compact={compact}
    >
      {output && (
        scopes ? (
          <div className="p-3 space-y-3 max-h-[24em] overflow-y-auto">
            {scopes.map((scope, i) => (
              <div key={i} className="space-y-1.5">
                <div className="flex items-center gap-1.5">
                  <span className={`shrink-0 px-1.5 py-px rounded text-[11px] font-medium ${SCOPE_BADGE[scope.scope] ?? 'bg-muted text-muted-foreground'}`}>
                    {scopeLabel(scope.scope)}
                  </span>
                  <span className="text-xs text-muted-foreground">{scope.count} {i18n('entries')}</span>
                </div>
                {scope.entries.map((entry, j) => (
                  <MemoryListRow key={`${entry.path}:${j}`} entry={entry} />
                ))}
                {scope.reviewItems.length > 0 && (
                  <div className="rounded-md border border-amber-500/30 bg-amber-50 dark:bg-amber-950/20 px-3 py-2 space-y-1">
                    <div className="flex items-center gap-1.5 text-xs font-medium text-amber-600 dark:text-amber-400">
                      <AlertTriangle className="size-3.5 shrink-0" />
                      <span>
                        {i18n('Review candidates')}
                        {scope.staleAfterDays != null && (
                          <span className="ml-1 font-normal">≥{scope.staleAfterDays} {i18n('days')}</span>
                        )}
                      </span>
                    </div>
                    <ul className="space-y-0.5 text-xs text-foreground/80">
                      {scope.reviewItems.map((item, k) => (
                        <li key={`${item.name}:${k}`} className="break-words">
                          <span className="font-medium">{item.name}</span>
                          {item.type && <span className="text-muted-foreground"> [{item.type}]</span>}
                          <span className="text-muted-foreground"> · {i18n('Unused for')}: {formatDays(item.unusedDays)}</span>
                        </li>
                      ))}
                    </ul>
                    <div className="text-[11px] text-amber-700/80 dark:text-amber-400/80 break-words">
                      {i18n('Low-maturity entries nobody has retrieved for a long time. Verify each one before updating or removing it.')}
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        ) : (
          <div className="p-3 text-sm whitespace-pre-wrap break-words text-muted-foreground">
            {output}
          </div>
        )
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
