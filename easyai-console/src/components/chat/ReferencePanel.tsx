import React, { useState } from 'react';
import { ChevronDown, ChevronRight, Brain, FileText } from 'lucide-react';
import type { ContextReferences, MemoryRef, RuleRef } from '@/types/message';
import { i18n } from '@/utils/i18n';

interface ReferencePanelProps {
  references: ContextReferences;
}

export const ReferencePanel: React.FC<ReferencePanelProps> = ({ references }) => {
  const [expanded, setExpanded] = useState(true);

  const memoryCount = references.memories.length;
  const ruleCount = references.rules.length;
  const totalCount = memoryCount + ruleCount;

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
