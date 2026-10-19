import React, { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, Layers } from 'lucide-react';
import { TextMarkdown } from './segments/TextMarkdown';
import { i18n } from '@/utils/i18n';

/**
 * Structured renderer for a consolidation pass's model answer.
 *
 * The grouping and merge passes answer with strict-schema JSON (`groups`/`leftoverDocIds` and
 * `clusters`/`unmergedDocIds`). Run through the markdown renderer that JSON turns into a wall of
 * punctuation with accidental strikethroughs, so it is unreadable exactly where the reader most
 * wants to see what the model decided. This parses the answer and lays it out as titled rows with
 * their source ids tucked behind an expand; anything that merely looks like such an answer but
 * does not parse yet (mid-stream) falls back to a plain monospace block, never to markdown.
 */

interface GroupView {
  title: string;
  sourceDocIds: string[];
}

interface SectionRefView {
  anchor: string;
  sourceDocIds: string[];
}

interface ClusterView {
  mergedTitle: string;
  mergedDescription: string;
  mergedContent: string;
  sectionRefs: SectionRefView[];
  sourceDocIds: string[];
}

export type ConsolidationAnswerData =
  | { kind: 'grouping'; groups: GroupView[]; leftoverDocIds: string[] }
  | { kind: 'merge'; clusters: ClusterView[]; unmergedDocIds: string[] };

type ParseResult =
  | { kind: 'answer'; data: ConsolidationAnswerData }
  | { kind: 'partial' }
  | { kind: 'none' };

const stringArray = (value: unknown): string[] | null =>
  Array.isArray(value) && value.every((item) => typeof item === 'string') ? (value as string[]) : null;

function readGroups(value: unknown): GroupView[] | null {
  if (!Array.isArray(value)) return null;
  const groups: GroupView[] = [];
  for (const item of value) {
    if (typeof item !== 'object' || item === null) return null;
    const record = item as Record<string, unknown>;
    if (typeof record.title !== 'string') return null;
    const ids = stringArray(record.sourceDocIds);
    if (!ids) return null;
    groups.push({ title: record.title, sourceDocIds: ids });
  }
  return groups;
}

function readClusters(value: unknown): ClusterView[] | null {
  if (!Array.isArray(value)) return null;
  const clusters: ClusterView[] = [];
  for (const item of value) {
    if (typeof item !== 'object' || item === null) return null;
    const record = item as Record<string, unknown>;
    if (typeof record.mergedTitle !== 'string') return null;
    const ids = stringArray(record.sourceDocIds);
    if (!ids) return null;
    const sectionRefs: SectionRefView[] = [];
    if (Array.isArray(record.sectionRefs)) {
      for (const ref of record.sectionRefs) {
        if (typeof ref !== 'object' || ref === null) return null;
        const refRecord = ref as Record<string, unknown>;
        const refIds = stringArray(refRecord.sourceDocIds);
        if (typeof refRecord.anchor !== 'string' || !refIds) return null;
        sectionRefs.push({ anchor: refRecord.anchor, sourceDocIds: refIds });
      }
    }
    clusters.push({
      mergedTitle: record.mergedTitle,
      mergedDescription: typeof record.mergedDescription === 'string' ? record.mergedDescription : '',
      mergedContent: typeof record.mergedContent === 'string' ? record.mergedContent : '',
      sectionRefs,
      sourceDocIds: ids,
    });
  }
  return clusters;
}

/** Cheap gate so ordinary prose never pays for a JSON.parse. */
function looksLikeConsolidationOutput(content: string): boolean {
  const trimmed = content.trimStart();
  if (!trimmed.startsWith('{')) return false;
  return trimmed.includes('"sourceDocIds"');
}

function parseConsolidationText(content: string): ParseResult {
  if (!looksLikeConsolidationOutput(content)) return { kind: 'none' };
  let parsed: unknown;
  try {
    parsed = JSON.parse(content);
  } catch {
    return { kind: 'partial' };
  }
  if (typeof parsed !== 'object' || parsed === null) return { kind: 'partial' };
  const record = parsed as Record<string, unknown>;

  if ('groups' in record) {
    const groups = readGroups(record.groups);
    if (!groups) return { kind: 'partial' };
    return {
      kind: 'answer',
      data: { kind: 'grouping', groups, leftoverDocIds: stringArray(record.leftoverDocIds) ?? [] },
    };
  }
  if ('clusters' in record) {
    const clusters = readClusters(record.clusters);
    if (!clusters) return { kind: 'partial' };
    return {
      kind: 'answer',
      data: { kind: 'merge', clusters, unmergedDocIds: stringArray(record.unmergedDocIds) ?? [] },
    };
  }
  return { kind: 'partial' };
}

const DocIds: React.FC<{ ids: string[] }> = ({ ids }) => (
  <div className="flex flex-wrap gap-1 mt-1.5">
    {ids.map((id) => (
      <span
        key={id}
        title={id}
        className="px-1.5 py-0.5 rounded bg-muted border border-border text-[10px] font-mono text-muted-foreground max-w-[180px] truncate"
      >
        {id}
      </span>
    ))}
  </div>
);

const ExpandableRow: React.FC<{
  header: React.ReactNode;
  children: React.ReactNode;
}> = ({ header, children }) => {
  const [open, setOpen] = useState(false);
  return (
    <div className="border-b border-border last:border-b-0">
      <button
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-start gap-1.5 px-3 py-1.5 text-left hover:bg-muted/50 transition-colors"
      >
        {open ? (
          <ChevronDown className="w-3.5 h-3.5 shrink-0 mt-0.5 text-muted-foreground" />
        ) : (
          <ChevronRight className="w-3.5 h-3.5 shrink-0 mt-0.5 text-muted-foreground" />
        )}
        <span className="flex-1 min-w-0">{header}</span>
      </button>
      {open && <div className="px-3 pb-2 pl-8">{children}</div>}
    </div>
  );
};

const GroupingView: React.FC<{ data: Extract<ConsolidationAnswerData, { kind: 'grouping' }> }> = ({ data }) => (
  <>
    {data.groups.map((group, index) => (
      <ExpandableRow
        key={`${group.title}-${index}`}
        header={
          <span className="flex items-baseline gap-2 text-xs">
            <span className="flex-1 min-w-0 truncate text-foreground">{group.title}</span>
            <span className="shrink-0 text-muted-foreground">
              {group.sourceDocIds.length} {i18n('sources')}
            </span>
          </span>
        }
      >
        <DocIds ids={group.sourceDocIds} />
      </ExpandableRow>
    ))}
    {data.leftoverDocIds.length > 0 && (
      <ExpandableRow
        header={
          <span className="text-xs text-muted-foreground">
            {i18n('Leftover entries')} · {data.leftoverDocIds.length}
          </span>
        }
      >
        <DocIds ids={data.leftoverDocIds} />
      </ExpandableRow>
    )}
  </>
);

const MergeView: React.FC<{ data: Extract<ConsolidationAnswerData, { kind: 'merge' }> }> = ({ data }) => (
  <>
    {data.clusters.map((cluster, index) => (
      <ExpandableRow
        key={`${cluster.mergedTitle}-${index}`}
        header={
          <span className="flex items-baseline gap-2 text-xs">
            <span className="flex-1 min-w-0 truncate text-foreground">{cluster.mergedTitle}</span>
            <span className="shrink-0 text-muted-foreground">
              {cluster.sourceDocIds.length} {i18n('sources')}
            </span>
          </span>
        }
      >
        {cluster.mergedDescription && (
          <p className="text-xs text-muted-foreground mb-1.5">{cluster.mergedDescription}</p>
        )}
        {cluster.mergedContent && (
          <div className="text-xs rounded-md border border-border bg-muted/30 p-2 max-h-[40vh] overflow-y-auto">
            <TextMarkdown content={cluster.mergedContent} />
          </div>
        )}
        {cluster.sectionRefs.length > 0 && (
          <div className="mt-1.5 space-y-0.5">
            {cluster.sectionRefs.map((ref, refIndex) => (
              <div key={`${ref.anchor}-${refIndex}`} className="text-[11px] text-muted-foreground">
                <span className="text-foreground">{ref.anchor}</span>
                {' · '}
                {ref.sourceDocIds.length} {i18n('sources')}
              </div>
            ))}
          </div>
        )}
        <DocIds ids={cluster.sourceDocIds} />
      </ExpandableRow>
    ))}
    {data.unmergedDocIds.length > 0 && (
      <ExpandableRow
        header={
          <span className="text-xs text-muted-foreground">
            {i18n('Unmerged entries')} · {data.unmergedDocIds.length}
          </span>
        }
      >
        <DocIds ids={data.unmergedDocIds} />
      </ExpandableRow>
    )}
  </>
);

const ConsolidationAnswer: React.FC<{ data: ConsolidationAnswerData }> = ({ data }) => {
  const isGrouping = data.kind === 'grouping';
  const count = isGrouping ? data.groups.length : data.clusters.length;
  return (
    <div className="rounded-lg border border-border bg-muted/30 overflow-hidden">
      <div className="flex items-center gap-2 px-3 py-1.5 text-xs text-muted-foreground border-b border-border">
        <Layers className="w-3.5 h-3.5 shrink-0 text-primary" />
        <span className="font-medium text-foreground">
          {isGrouping ? i18n('Grouping result') : i18n('Merge result')}
        </span>
        <span>
          · {count} {isGrouping ? i18n('groups') : i18n('clusters')}
        </span>
      </div>
      {isGrouping ? (
        <GroupingView data={data as Extract<ConsolidationAnswerData, { kind: 'grouping' }> } />
      ) : (
        <MergeView data={data as Extract<ConsolidationAnswerData, { kind: 'merge' }> } />
      )}
    </div>
  );
};

/**
 * Text-segment renderer that routes a consolidation pass's JSON answer to the structured view,
 * an unparseable (still streaming) answer to a plain monospace block, and everything else to
 * markdown as before.
 */
export const ConsolidationTextRow: React.FC<{ content: string }> = ({ content }) => {
  const parsed = useMemo(() => parseConsolidationText(content), [content]);
  if (parsed.kind === 'answer') return <ConsolidationAnswer data={parsed.data} />;
  if (parsed.kind === 'partial') {
    return (
      <pre className="whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed text-muted-foreground">
        {content}
      </pre>
    );
  }
  return <TextMarkdown content={content} />;
};
