import React, { useMemo, useState } from 'react';
import { Search, ChevronDown, ChevronRight, FileText, FolderOpen, Globe } from 'lucide-react';
import type { MemoryEntry } from '@/types/memory';
import { maturityLabelKey } from '@/types/memory';
import { useCategoryStore } from '@/services/stores/category-store';
import { i18n } from '@/utils/i18n';
import type { Project } from '@/services/project-service';
import { ToolTooltip } from '@/components/agent/ToolItem';

export interface SelectedMemory {
  scope: 'global' | 'project';
  projectPath: string | null;
  name: string;
}

interface MemorySidebarProps {
  entries: MemoryEntry[];
  projects: Project[];
  currentProject: Project | null;
  selected: SelectedMemory | null;
  autoGeneration: boolean;
  onToggleAutoGeneration: (enabled: boolean) => void;
  onSelect: (sel: SelectedMemory) => void;
}

const FILTER_ANY = 'all';

export const MemorySidebar: React.FC<MemorySidebarProps> = ({
  entries,
  projects,
  currentProject,
  selected,
  autoGeneration,
  onToggleAutoGeneration,
  onSelect,
}) => {
  const memoryCategories = useCategoryStore((s) => s.memoryCategories);
  const [query, setQuery] = useState('');
  const [categoryFilter, setCategoryFilter] = useState(FILTER_ANY);
  const [maturityFilter, setMaturityFilter] = useState(FILTER_ANY);
  const [projectFilter, setProjectFilter] = useState(FILTER_ANY);
  const [collapsedScopes, setCollapsedScopes] = useState<Record<string, boolean>>({});

  const filteredEntries = useMemo(() => {
    const q = query.trim().toLowerCase();
    return entries.filter((entry) => {
      if (categoryFilter !== FILTER_ANY && entry.type !== categoryFilter) return false;
      if (maturityFilter !== FILTER_ANY && (entry.maturity ?? null) !== maturityFilter) return false;
      if (projectFilter === 'global' && entry.projectPath != null) return false;
      if (projectFilter !== FILTER_ANY && projectFilter !== 'global' && entry.projectPath !== projectFilter) return false;
      if (!q) return true;
      const haystack = [
        entry.name,
        entry.description,
        entry.content,
        ...entry.keywords,
        ...entry.scenarios,
      ]
        .join('\n')
        .toLowerCase();
      return haystack.includes(q);
    });
  }, [entries, query, categoryFilter, maturityFilter, projectFilter]);

  // Scope groups: Global first, then each project that owns at least one visible entry
  const scopeGroups = useMemo(() => {
    const groups: { key: string; projectPath: string | null; label: string; icon: React.ReactNode }[] = [];
    const globalEntries = filteredEntries.filter((e) => e.projectPath == null);
    if (globalEntries.length > 0 || projectFilter === 'global') {
      groups.push({ key: 'global', projectPath: null, label: i18n('Global'), icon: <Globe className="size-3.5" /> });
    }
    for (const project of projects) {
      const count = filteredEntries.filter((e) => e.projectPath === project.path).length;
      if (count > 0) {
        groups.push({ key: project.path, projectPath: project.path, label: project.name, icon: <FolderOpen className="size-3.5" /> });
      }
    }
    return groups;
  }, [filteredEntries, projects, projectFilter]);

  const toggleScope = (key: string) => {
    setCollapsedScopes((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const isSelected = (entry: MemoryEntry, scopeKey: string) =>
    selected?.name === entry.name &&
    selected.scope === entry.scope &&
    selected.projectPath === (scopeKey === 'global' ? null : scopeKey);

  return (
    <div className="flex flex-col h-full min-h-0">
      {/* Automatic generation toggle */}
      {currentProject && (
        <div className="px-3 pt-3 pb-2 border-b border-border">
          <div className="flex items-center justify-between gap-2">
            <ToolTooltip name={i18n('Enable Automatic Generation')} description={i18n('Automatically build memories from conversations')} className="min-w-0 flex-1">
              <div className="min-w-0">
                <div className="text-xs font-medium text-foreground truncate">
                  {i18n('Enable Automatic Generation')}
                </div>
                <div className="text-[11px] text-muted-foreground truncate">
                  {i18n('Automatically build memories from conversations')}
                </div>
              </div>
            </ToolTooltip>
            <button
              onClick={() => onToggleAutoGeneration(!autoGeneration)}
              className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors ${
                autoGeneration ? 'bg-green-500' : 'bg-muted-foreground/30'
              }`}
              title={i18n('Enable Automatic Generation')}
            >
              <span
                className={`inline-block h-3.5 w-3.5 rounded-full bg-white transition-transform ${
                  autoGeneration ? 'translate-x-[18px]' : 'translate-x-0.5'
                }`}
              />
            </button>
          </div>
        </div>
      )}

      {/* Search box */}
      <div className="p-3 pb-1.5">
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-3.5 text-muted-foreground" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={i18n('Search memories...')}
            className="w-full pl-8 pr-3 py-1.5 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary"
          />
        </div>
      </div>

      {/* Filter row */}
      <div className="px-3 pb-2 flex items-center gap-1.5">
        <select
          value={categoryFilter}
          onChange={(e) => setCategoryFilter(e.target.value)}
          className="flex-1 min-w-0 px-2 py-1 rounded-md border border-border bg-background text-[11px] focus:outline-none focus:ring-2 focus:ring-primary"
          title={i18n('Category')}
        >
          <option value={FILTER_ANY}>{i18n('Category')}</option>
          {memoryCategories.map((c) => (
            <option key={c.code} value={c.code}>
              {i18n(c.labelKey)}
            </option>
          ))}
        </select>
        <select
          value={maturityFilter}
          onChange={(e) => setMaturityFilter(e.target.value)}
          className="flex-1 min-w-0 px-2 py-1 rounded-md border border-border bg-background text-[11px] focus:outline-none focus:ring-2 focus:ring-primary"
          title={i18n('Maturity')}
        >
          <option value={FILTER_ANY}>{i18n('Maturity')}</option>
          {['low', 'medium', 'high'].map((m) => (
            <option key={m} value={m}>
              {i18n(maturityLabelKey(m))}
            </option>
          ))}
        </select>
        <select
          value={projectFilter}
          onChange={(e) => setProjectFilter(e.target.value)}
          className="flex-1 min-w-0 px-2 py-1 rounded-md border border-border bg-background text-[11px] focus:outline-none focus:ring-2 focus:ring-primary"
          title={i18n('Project')}
        >
          <option value={FILTER_ANY}>{i18n('Project')}</option>
          <option value="global">{i18n('Global')}</option>
          {projects.map((p) => (
            <option key={p.path} value={p.path}>
              {p.name}
            </option>
          ))}
        </select>
      </div>

      {/* Navigation tree */}
      <div className="flex-1 min-h-0 overflow-y-auto px-2 pb-3">
        {scopeGroups.length === 0 && (
          <div className="text-center text-xs text-muted-foreground py-8 px-4">
            {i18n('No memories yet')}
          </div>
        )}
        {scopeGroups.map((scope) => {
          const scopeEntries = filteredEntries.filter((e) =>
            scope.projectPath == null ? e.projectPath == null : e.projectPath === scope.projectPath
          );
          const collapsed = collapsedScopes[scope.key] ?? false;
          return (
            <div key={scope.key} className="mb-1">
              {/* Scope header */}
              <button
                onClick={() => toggleScope(scope.key)}
                className="w-full flex items-center gap-1.5 px-1.5 py-1.5 rounded-md hover:bg-muted/60 transition-colors text-left"
              >
                {collapsed ? (
                  <ChevronRight className="size-3.5 text-muted-foreground shrink-0" />
                ) : (
                  <ChevronDown className="size-3.5 text-muted-foreground shrink-0" />
                )}
                <span className="text-muted-foreground shrink-0">{scope.icon}</span>
                <span className="text-xs font-semibold text-foreground truncate flex-1">
                  {scope.label}
                </span>
                <span className="text-[10px] text-muted-foreground bg-muted rounded-full px-1.5 py-0.5 shrink-0">
                  {scopeEntries.length}
                </span>
              </button>

              {/* Category groups */}
              {!collapsed && (
                <div className="ml-2.5 border-l border-border/60 pl-2">
                  {memoryCategories.map((cat) => {
                    const catEntries = scopeEntries.filter((e) => e.type === cat.code);
                    if (catEntries.length === 0) return null;
                    return (
                      <div key={cat.code} className="mb-0.5">
                        <div className="flex items-center gap-1 px-1.5 py-1">
                          <span className="text-[11px] text-muted-foreground truncate flex-1">
                            {i18n(cat.labelKey)}
                          </span>
                          <span className="text-[10px] text-muted-foreground bg-muted rounded-full px-1.5 py-0.5">
                            {catEntries.length}
                          </span>
                        </div>
                        <div className="space-y-px">
                          {catEntries.map((entry) => (
                            <button
                              key={entry.name}
                              onClick={() =>
                                onSelect({
                                  scope: entry.scope as 'global' | 'project',
                                  projectPath: scope.projectPath,
                                  name: entry.name,
                                })
                              }
                              className={`w-full flex items-center gap-1.5 px-1.5 py-1 rounded-md text-left transition-colors ${
                                isSelected(entry, scope.key)
                                  ? 'bg-primary/10 text-primary'
                                  : 'hover:bg-muted/60 text-foreground'
                              }`}
                            >
                              <FileText className="size-3 text-muted-foreground shrink-0" />
                              <span className="text-xs truncate flex-1">{entry.name}</span>
                              {entry.maturity && (
                                <span
                                  className={`shrink-0 size-1.5 rounded-full ${
                                    entry.maturity === 'high'
                                      ? 'bg-green-500'
                                      : entry.maturity === 'medium'
                                        ? 'bg-amber-500'
                                        : 'bg-slate-400'
                                  }`}
                                  title={i18n(maturityLabelKey(entry.maturity))}
                                />
                              )}
                            </button>
                          ))}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};
