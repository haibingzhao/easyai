import React, { useEffect, useRef, useState } from 'react';
import { Trash2, MoreHorizontal, Tags as TagsIcon, Check, X } from 'lucide-react';
import type { SessionListItem } from '@/services/session-service';
import { MultiSelectDropdown } from '@/components/chat/MultiSelectDropdown';
import { i18n } from '@/utils/i18n';

/** Must match the backend's normalizeTags truncation limit (R2dbcAsyncSessionStore.maxTagLength). */
const MAX_TAG_LENGTH = 32;

interface SessionItemProps {
  session: SessionListItem;
  isSelected: boolean;
  showDelete?: boolean;
  /** Distinct tags across sessions — used as autocomplete options in the tag editor. */
  availableTags?: string[];
  onSelect: (id: string) => void;
  onDelete?: (id: string) => void;
  /** Persist a new tag set for this session (rejects on failure so the editor stays open). */
  onUpdateTags?: (id: string, tags: string[]) => Promise<void>;
}

export const SessionItem: React.FC<SessionItemProps> = ({
  session,
  isSelected,
  showDelete = false,
  availableTags = [],
  onSelect,
  onDelete,
  onUpdateTags,
}) => {
  const [isHovered, setIsHovered] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [editingTags, setEditingTags] = useState(false);
  const [draftTags, setDraftTags] = useState<string[]>(session.tags ?? []);
  const [saving, setSaving] = useState(false);
  // The History list is an overflow-y-auto container, which clips absolutely-positioned
  // popovers. Flip the menu/editor upward when the row sits near the viewport bottom.
  const [dropUp, setDropUp] = useState(false);
  const actionRef = useRef<HTMLDivElement>(null);

  const tags = session.tags ?? [];
  const hasActions = !!onUpdateTags || (showDelete && !!onDelete);
  const actionVisible = hasActions && (isHovered || isSelected || menuOpen || editingTags);

  // Close menu / editor on outside click or Escape.
  useEffect(() => {
    if (!menuOpen && !editingTags) return;
    const onClickOutside = (e: MouseEvent) => {
      if (actionRef.current && !actionRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
        setEditingTags(false);
      }
    };
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMenuOpen(false);
        setEditingTags(false);
      }
    };
    document.addEventListener('mousedown', onClickOutside);
    document.addEventListener('keydown', onEscape);
    return () => {
      document.removeEventListener('mousedown', onClickOutside);
      document.removeEventListener('keydown', onEscape);
    };
  }, [menuOpen, editingTags]);

  const stop = (e: React.MouseEvent) => e.stopPropagation();

  const toggleMenu = (e: React.MouseEvent) => {
    e.stopPropagation();
    const rect = actionRef.current?.getBoundingClientRect();
    if (rect) setDropUp(window.innerHeight - rect.bottom < 240);
    setMenuOpen((o) => !o);
  };

  const openTagEditor = (e: React.MouseEvent) => {
    e.stopPropagation();
    setDraftTags(session.tags ?? []);
    setMenuOpen(false);
    setEditingTags(true);
  };

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    setMenuOpen(false);
    onDelete?.(session.id);
  };

  const handleSaveTags = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!onUpdateTags || saving) return;
    setSaving(true);
    try {
      await onUpdateTags(session.id, draftTags);
      setEditingTags(false);
    } catch {
      // Store already reverted + logged; keep the editor open for retry.
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className={`flex items-start justify-between gap-2 p-3 rounded-md border border-border cursor-pointer transition-colors ${
        isSelected ? 'bg-muted' : 'hover:bg-muted'
      }`}
      onClick={() => onSelect(session.id)}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          {session.streaming && (
            <span className="flex items-center gap-1 text-xs font-medium text-primary shrink-0">
              <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse" />
              {i18n('Running')}
            </span>
          )}
          <span className="font-medium truncate">{session.title || i18n('Untitled')}</span>
        </div>
        <div className="text-xs text-muted-foreground">
          {new Date(session.createdAt).toLocaleString()}
        </div>
        {tags.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-1.5">
            {tags.map((tag) => (
              <span
                key={tag}
                title={tag}
                className="inline-flex items-center px-1.5 py-0.5 rounded text-[11px] bg-muted border border-border text-muted-foreground max-w-[140px]"
              >
                <span className="truncate">{tag}</span>
              </span>
            ))}
          </div>
        )}
      </div>

      {/* Action area: ⋯ menu + inline tag editor */}
      <div ref={actionRef} className="relative shrink-0" onClick={stop}>
        {actionVisible && !editingTags && (
          <button
            type="button"
            onClick={toggleMenu}
            className="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-background transition-colors"
            title={i18n('More')}
          >
            <MoreHorizontal className="w-4 h-4" />
          </button>
        )}

        {/* Dropdown menu */}
        {menuOpen && !editingTags && (
          <div className={`absolute right-0 w-44 bg-popover border border-border rounded-lg shadow-lg z-50 py-1 ${dropUp ? 'bottom-full mb-1' : 'top-full mt-1'}`}>
            {onUpdateTags && (
              <button
                type="button"
                onClick={openTagEditor}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-muted rounded-md transition-colors"
              >
                <TagsIcon className="w-4 h-4 text-muted-foreground" />
                {i18n('Edit tags')}
              </button>
            )}
            {showDelete && onDelete && (
              <button
                type="button"
                onClick={handleDelete}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm text-destructive hover:bg-muted rounded-md transition-colors"
              >
                <Trash2 className="w-4 h-4" />
                {i18n('Delete Session')}
              </button>
            )}
          </div>
        )}

        {/* Inline tag editor popover */}
        {editingTags && (
          <div className={`absolute right-0 w-72 bg-popover border border-border rounded-lg shadow-lg z-50 p-3 ${dropUp ? 'bottom-full mb-1' : 'top-full mt-1'}`}>
            <div className="text-xs font-medium text-muted-foreground mb-2">{i18n('Edit tags')}</div>
            <MultiSelectDropdown
              values={draftTags}
              onChange={setDraftTags}
              options={availableTags}
              placeholder={i18n('Add or search tags...')}
              allowCustom
              maxCustomLength={MAX_TAG_LENGTH}
              variant="compact"
            />
            <div className="flex items-center justify-end gap-2 mt-3">
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  setEditingTags(false);
                }}
                className="inline-flex items-center gap-1 px-2.5 py-1 text-xs rounded-md border border-border text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
              >
                <X className="w-3.5 h-3.5" />
                {i18n('Cancel')}
              </button>
              <button
                type="button"
                onClick={handleSaveTags}
                disabled={saving}
                className="inline-flex items-center gap-1 px-2.5 py-1 text-xs rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              >
                <Check className="w-3.5 h-3.5" />
                {i18n('Save')}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
