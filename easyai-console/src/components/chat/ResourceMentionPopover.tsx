import React, { useEffect, useRef } from 'react';
import { File, Folder, ChevronRight, ChevronLeft } from 'lucide-react';
import type { MentionViewMode, MentionItem } from '@/hooks/useMention';
import { i18n } from '@/utils/i18n';

interface ResourceMentionPopoverProps {
  viewMode: MentionViewMode;
  items: MentionItem[];
  selectedIndex: number;
  hoveredItem: MentionItem | null;
  currentPath: string | null;
  query: string;
  onSelect: (item: MentionItem) => void;
  onEnterView: (mode: 'files' | 'folders') => void;
  onExitView: () => void;
  onHover: (item: MentionItem | null) => void;
  onClose: () => void;
}

/** Highlight matching portion of text in bold */
const HighlightedName: React.FC<{ name: string; query: string }> = ({ name, query }) => {
  if (!query) return <>{name}</>;
  const idx = name.toLowerCase().indexOf(query.toLowerCase());
  if (idx === -1) return <>{name}</>;
  return (
    <>
      {name.slice(0, idx)}
      <span className="font-bold">{name.slice(idx, idx + query.length)}</span>
      {name.slice(idx + query.length)}
    </>
  );
};

/** Icon for a mention item */
const ItemIcon: React.FC<{ type: 'file' | 'directory' }> = ({ type }) => {
  if (type === 'directory') {
    return <Folder className="w-4 h-4 text-amber-500 shrink-0" />;
  }
  return <File className="w-4 h-4 text-muted-foreground shrink-0" />;
};

export const ResourceMentionPopover: React.FC<ResourceMentionPopoverProps> = ({
  viewMode,
  items,
  selectedIndex,
  hoveredItem,
  query,
  onSelect,
  onEnterView,
  onExitView,
  onHover,
  onClose,
}) => {
  const listRef = useRef<HTMLDivElement>(null);
  const selectedRef = useRef<HTMLDivElement>(null);

  // Scroll selected item into view
  useEffect(() => {
    selectedRef.current?.scrollIntoView({ block: 'nearest' });
  }, [selectedIndex]);

  // Close on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (listRef.current && !listRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onClose]);

  const showSearchMode = viewMode === 'default' && query.length > 0;
  const showDefaultNoQuery = viewMode === 'default' && query.length === 0;
  const showFilesView = viewMode === 'files';
  const showFoldersView = viewMode === 'folders';

  return (
    <div
      ref={listRef}
      className="absolute left-0 right-0 z-50 bottom-full mb-1 mx-0"
    >
      <div className="bg-popover border border-border rounded-lg shadow-lg overflow-hidden max-h-72 overflow-y-auto">
        {/* Back header for files/folders view */}
        {(showFilesView || showFoldersView) && (
          <button
            className="flex items-center gap-1.5 w-full px-3 py-2 text-xs text-muted-foreground hover:bg-muted/50 transition-colors border-b border-border/50"
            onMouseDown={(e) => {
              e.preventDefault();
              e.stopPropagation();
              onExitView();
            }}
          >
            <ChevronLeft className="w-3.5 h-3.5" />
            <span>{showFilesView ? i18n('mention.files') : i18n('mention.folders')}</span>
          </button>
        )}

        {/* Items list */}
        {items.length === 0 ? (
          <div className="px-3 py-4 text-center text-xs text-muted-foreground">
            {query ? i18n('mention.noMatches') : i18n('mention.emptyDir')}
          </div>
        ) : (
          items.map((item, index) => (
            <div
              key={item.path}
              ref={index === selectedIndex ? selectedRef : undefined}
              className={`relative flex items-center gap-2 px-3 py-1.5 cursor-pointer transition-colors ${
                index === selectedIndex
                  ? 'bg-accent text-accent-foreground'
                  : 'hover:bg-muted/50'
              }`}
              onMouseDown={(e) => {
                e.preventDefault();
                e.stopPropagation();
                onSelect(item);
              }}
              onMouseEnter={() => onHover(item)}
              onMouseLeave={() => onHover(null)}
            >
              <ItemIcon type={item.type} />

              {/* File/folder name */}
              <span className="text-sm truncate shrink-0 max-w-[180px]">
                <HighlightedName name={item.name} query={showSearchMode ? query : ''} />
              </span>

              {/* Parent path (right-aligned, muted) */}
              {item.parentPath && (
                <span className="text-[11px] text-muted-foreground truncate ml-auto pl-2">
                  {item.parentPath}
                </span>
              )}

              {/* Hover breadcrumb tooltip */}
              {hoveredItem?.path === item.path && item.parentPath && (
                <span className="absolute left-14 bottom-full mb-0.5 text-[10px] text-muted-foreground bg-popover border border-border rounded px-1 py-0.5 whitespace-nowrap z-10 pointer-events-none shadow-sm">
                  {item.parentPath}
                </span>
              )}
            </div>
          ))
        )}

        {/* Separator + Files/Folders entries in default view with no query */}
        {showDefaultNoQuery && (
          <>
            <div className="border-t border-border/50 mx-2" />
            <div
              className="flex items-center gap-2 px-3 py-2 cursor-pointer transition-colors hover:bg-muted/50"
              onMouseDown={(e) => {
                e.preventDefault();
                e.stopPropagation();
                onEnterView('files');
              }}
            >
              <File className="w-4 h-4 text-muted-foreground shrink-0" />
              <span className="text-sm">{i18n('mention.files')}</span>
              <ChevronRight className="w-4 h-4 text-muted-foreground ml-auto" />
            </div>
            <div
              className="flex items-center gap-2 px-3 py-2 cursor-pointer transition-colors hover:bg-muted/50"
              onMouseDown={(e) => {
                e.preventDefault();
                e.stopPropagation();
                onEnterView('folders');
              }}
            >
              <Folder className="w-4 h-4 text-amber-500 shrink-0" />
              <span className="text-sm">{i18n('mention.folders')}</span>
              <ChevronRight className="w-4 h-4 text-muted-foreground ml-auto" />
            </div>
          </>
        )}

        {/* Keyboard hint bar */}
        <div className="flex items-center gap-3 px-3 py-1.5 border-t border-border/50 text-[10px] text-muted-foreground">
          <span>↑↓ {i18n('mention.navigate')}</span>
          <span>↵ {i18n('mention.select')}</span>
          <span>esc {i18n('mention.close')}</span>
        </div>
      </div>
    </div>
  );
};
