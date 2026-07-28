import React, { useState } from 'react';
import { ChevronDown, ChevronRight, Brain, FileText, Variable, Copy, Check } from 'lucide-react';
import type { ContextReferences, MemoryRef, RuleRef } from '@/types/message';
import { i18n } from '@/utils/i18n';

interface ReferencePanelProps {
  references: ContextReferences;
  /** Session-scoped variables (key -> value) tracked from update_variable tool / restored from backend */
  sessionVariables?: Record<string, string>;
}

export const ReferencePanel: React.FC<ReferencePanelProps> = ({ references, sessionVariables }) => {
  const [expanded, setExpanded] = useState(true);

  const memoryCount = references.memories.length;
  const ruleCount = references.rules.length;
  const variableCount = sessionVariables ? Object.keys(sessionVariables).length : 0;
  const totalCount = memoryCount + ruleCount + variableCount;

  // Group memories by scope
  const globalMemories = references.memories.filter(m => m.scope === 'global');
  const projectMemories = references.memories.filter(m => m.scope !== 'global');

  return (
    <div className="text-sm">
      {/* Header */}
      <div
        className="flex items-center justify-between py-2 cursor-pointer hover:bg-muted/50 transition-colors px-2 rounded"
        onClick={() => setExpanded(!expanded)}
      >
        <span className="text-muted-foreground font-medium">
          {i18n('References')}
        </span>
        {expanded ? (
          <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />
        ) : (
          <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0" />
        )}
      </div>

      {/* Content */}
      {expanded && (
        <div className="pb-2 space-y-2">
          {totalCount === 0 ? (
            <div className="text-muted-foreground text-xs px-2">{i18n('No references yet')}</div>
          ) : (
            <>
              {/* Session Variables */}
              {variableCount > 0 && sessionVariables && (
                <SessionVariablesSection variables={sessionVariables} />
              )}

              {/* Memories */}
              {memoryCount > 0 && (
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs text-muted-foreground font-medium uppercase tracking-wider">
                    <Brain className="w-3 h-3" />
                    {i18n('Memories')}
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {globalMemories.map((mem, i) => (
                      <MemoryBadge key={`g-${i}`} memory={mem} />
                    ))}
                    {projectMemories.map((mem, i) => (
                      <MemoryBadge key={`p-${i}`} memory={mem} />
                    ))}
                  </div>
                </div>
              )}

              {/* Rules */}
              {ruleCount > 0 && (
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs text-muted-foreground font-medium uppercase tracking-wider">
                    <FileText className="w-3 h-3" />
                    {i18n('Rules')}
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {references.rules.map((rule, i) => (
                      <RuleBadge key={i} rule={rule} />
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};

const MemoryBadge: React.FC<{ memory: MemoryRef }> = ({ memory }) => {
  const isGlobal = memory.scope === 'global';
  return (
    <span
      className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs border border-border bg-background"
      title={memory.description || memory.name}
    >
      <span className="truncate max-w-[200px]">{memory.name}</span>
      <span
        className={`shrink-0 px-1 py-px rounded text-[10px] leading-none font-medium ${
          isGlobal
            ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
            : 'bg-blue-500/10 text-blue-600 dark:text-blue-400'
        }`}
      >
        {isGlobal ? i18n('Global') : i18n('Project')}
      </span>
    </span>
  );
};

const RuleBadge: React.FC<{ rule: RuleRef }> = ({ rule }) => (
  <span
    className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs border border-border bg-background"
    title={rule.source}
  >
    <span className="truncate max-w-[200px]">{rule.name}</span>
    <span className="shrink-0 px-1 py-px rounded text-[10px] leading-none font-medium bg-amber-500/10 text-amber-600 dark:text-amber-400">
      {rule.source}
    </span>
  </span>
);

// ---------------------------------------------------------------------------
// Session Variables
// ---------------------------------------------------------------------------

const FILE_REF_PREFIX = '[file: ';

/** Parse a "[file: path]" reference, returning the path or null when not a file ref. */
function parseFileRef(value: string): string | null {
  if (value.startsWith(FILE_REF_PREFIX) && value.endsWith(']')) {
    return value.slice(FILE_REF_PREFIX.length, -1);
  }
  return null;
}

const SessionVariablesSection: React.FC<{ variables: Record<string, string> }> = ({ variables }) => {
  const [collapsed, setCollapsed] = useState(false);
  const entries = Object.entries(variables);
  if (entries.length === 0) return null;
  return (
    <div className="space-y-1">
      <div
        className="flex items-center gap-1.5 text-xs text-muted-foreground font-medium uppercase tracking-wider cursor-pointer hover:text-foreground transition-colors select-none"
        onClick={() => setCollapsed((c) => !c)}
        title={collapsed ? i18n('Expand') : i18n('Collapse')}
      >
        {collapsed ? <ChevronRight className="w-3 h-3 shrink-0" /> : <ChevronDown className="w-3 h-3 shrink-0" />}
        <Variable className="w-3 h-3" />
        {i18n('Session Variables')}
        <span className="ml-auto shrink-0 px-1.5 py-px rounded-full text-[10px] leading-none font-medium bg-violet-500/10 text-violet-600 dark:text-violet-400">
          {entries.length}
        </span>
      </div>
      {!collapsed && (
        <div className="space-y-1">
          {entries.map(([key, value]) => (
            <VariableRow key={key} varKey={key} value={value} />
          ))}
        </div>
      )}
    </div>
  );
};

const VariableRow: React.FC<{ varKey: string; value: string }> = ({ varKey, value }) => {
  const [expandedValue, setExpandedValue] = useState(false);
  const [copied, setCopied] = useState(false);

  const filePath = parseFileRef(value);
  const isFileRef = filePath !== null;
  // Inline values are expandable when long or multi-line
  const expandable = !isFileRef && (value.length > 60 || value.includes('\n'));

  const copyText = isFileRef ? (filePath ?? '') : value;

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(copyText);
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      /* clipboard unavailable — ignore */
    }
  };

  // File ref rows copy the path on click; inline rows toggle expansion
  const handleRowClick = isFileRef ? handleCopy : expandable ? () => setExpandedValue((v) => !v) : undefined;

  return (
    <div
      className={`group rounded-md border border-border bg-background ${handleRowClick ? 'cursor-pointer' : ''}`}
      onClick={handleRowClick}
      title={isFileRef ? i18n('Copy') : undefined}
    >
      <div className="flex items-center gap-2 px-2 py-1.5 min-w-0">
        {/* Key */}
        <span
          className="shrink-0 font-mono text-xs font-medium text-violet-600 dark:text-violet-400 truncate max-w-[40%]"
          title={varKey}
        >
          {varKey}
        </span>
        {/* Value / file reference */}
        {isFileRef ? (
          <span className="flex items-center gap-1 min-w-0 text-xs text-muted-foreground">
            <FileText className="w-3 h-3 shrink-0 text-violet-500/70" />
            <span className="truncate font-mono">{filePath}</span>
            <span className="shrink-0 px-1 py-px rounded text-[10px] leading-none font-medium bg-violet-500/10 text-violet-600 dark:text-violet-400">
              file
            </span>
          </span>
        ) : (
          <span
            className={`min-w-0 text-xs text-foreground/80 font-mono ${
              expandedValue ? 'whitespace-pre-wrap break-all' : 'truncate'
            }`}
          >
            {value}
          </span>
        )}
        {/* Copy button (visible on hover) */}
        <button
          onClick={handleCopy}
          className={`ml-auto shrink-0 p-0.5 rounded transition-opacity text-muted-foreground hover:text-foreground ${
            copied ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
          }`}
          title={i18n('Copy')}
        >
          {copied ? <Check className="w-3 h-3 text-emerald-500" /> : <Copy className="w-3 h-3" />}
        </button>
      </div>
      {/* Expanded full value */}
      {expandedValue && !isFileRef && (
        <div className="border-t border-border px-2 py-1.5">
          <pre className="m-0 text-xs font-mono whitespace-pre-wrap break-all text-foreground/80 max-h-40 overflow-y-auto">
            {value}
          </pre>
        </div>
      )}
    </div>
  );
};
