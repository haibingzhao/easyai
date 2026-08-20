import React, { useMemo, useState } from 'react';
import { Search, FileText, Upload } from 'lucide-react';
import type { KnowledgeEntryDto } from '@/services/knowledge-service';
import { useCategoryStore } from '@/services/stores/category-store';
import { i18n } from '@/utils/i18n';

interface KnowledgeSidebarProps {
  entries: KnowledgeEntryDto[];
  sources: string[];
  selectedKey: string | null;
  onSelect: (key: string) => void;
  onUploadClick: () => void;
}

const FILTER_ANY = 'all';

export const KnowledgeSidebar: React.FC<KnowledgeSidebarProps> = ({
  entries,
  sources,
  selectedKey,
  onSelect,
  onUploadClick,
}) => {
  const knowledgeCategories = useCategoryStore((s) => s.knowledgeCategories);
  const [query, setQuery] = useState('');
  const [categoryFilter, setCategoryFilter] = useState(FILTER_ANY);
  const [sourceFilter, setSourceFilter] = useState(FILTER_ANY);

  const filteredEntries = useMemo(() => {
    const q = query.trim().toLowerCase();
    return entries.filter((entry) => {
      if (categoryFilter !== FILTER_ANY && entry.category !== categoryFilter) return false;
      if (sourceFilter !== FILTER_ANY && entry.source !== sourceFilter) return false;
      if (!q) return true;
      const haystack = [entry.title, entry.description, entry.relativePath, entry.contentPreview]
        .join('\n')
        .toLowerCase();
      return haystack.includes(q);
    });
  }, [entries, query, categoryFilter, sourceFilter]);

  return (
    <div className="flex flex-col h-full min-h-0">
      {/* Upload button */}
      <div className="px-3 pt-3 pb-2 border-b border-border">
        <button
          onClick={onUploadClick}
          className="w-full flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
        >
          <Upload className="size-3.5" />
          {i18n('Upload Knowledge')}
        </button>
      </div>

      {/* Search box */}
      <div className="p-3 pb-1.5">
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-3.5 text-muted-foreground" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={i18n('Search knowledge...')}
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
          <option value={FILTER_ANY}>{i18n('All Categories')}</option>
          {knowledgeCategories.map((c) => (
            <option key={c.code} value={c.code}>
              {i18n(c.labelKey)}
            </option>
          ))}
        </select>
        <select
          value={sourceFilter}
          onChange={(e) => setSourceFilter(e.target.value)}
          className="flex-1 min-w-0 px-2 py-1 rounded-md border border-border bg-background text-[11px] focus:outline-none focus:ring-2 focus:ring-primary"
          title={i18n('Source')}
        >
          <option value={FILTER_ANY}>{i18n('All Sources')}</option>
          {sources.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>

      {/* Entry list */}
      <div className="flex-1 min-h-0 overflow-y-auto px-2 pb-3">
        {filteredEntries.length === 0 && (
          <div className="text-center text-xs text-muted-foreground py-8 px-4">
            {i18n('No knowledge entries yet')}
          </div>
        )}
        {filteredEntries.map((entry) => (
          <button
            key={entry.key}
            onClick={() => onSelect(entry.key)}
            className={`w-full flex items-start gap-2 px-2 py-1.5 rounded-md text-left transition-colors mb-0.5 ${
              selectedKey === entry.key
                ? 'bg-primary/10 text-primary'
                : 'hover:bg-muted/60 text-foreground'
            }`}
          >
            <FileText className="size-3.5 text-muted-foreground shrink-0 mt-0.5" />
            <div className="flex-1 min-w-0">
              <div className="text-xs font-medium truncate">{entry.title || entry.relativePath}</div>
              {entry.description && (
                <div className="text-[11px] text-muted-foreground truncate">{entry.description}</div>
              )}
              <div className="flex items-center gap-1.5 mt-0.5">
                {entry.category && (
                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-green-500/10 text-green-600 dark:text-green-400">
                    {i18n(knowledgeCategories.find((c) => c.code === entry.category)?.labelKey ?? entry.category)}
                  </span>
                )}
                <span className="text-[10px] text-muted-foreground">{entry.source}</span>
              </div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
};
