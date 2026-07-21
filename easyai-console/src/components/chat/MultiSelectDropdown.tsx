import React, { useState, useRef, useEffect, useCallback } from 'react';
import { X, ChevronDown, ChevronUp, Plus } from 'lucide-react';

interface MultiSelectDropdownProps {
  /** Currently selected values */
  values: string[];
  /** Called when the selection list changes (add / remove) */
  onChange: (values: string[]) => void;
  /** Predefined options */
  options: string[];
  /** Placeholder for the search / input field */
  placeholder?: string;
  /** Whether the user can type custom values that are not in the option list */
  allowCustom?: boolean;
  /** Visual variant – 'compact' is shorter */
  variant?: 'default' | 'compact';
}

/**
 * A multi-select dropdown with optional custom-text input.
 *
 * - Shows selected items as removable chips above the input.
 * - Filters predefined options as the user types.
 * - When `allowCustom` is true the user can press Enter (or click +) to add
 *   the typed text as a new value even if it does not match any option.
 * - Opens downward by default; flips upward when there is not enough space.
 */
export const MultiSelectDropdown: React.FC<MultiSelectDropdownProps> = ({
  values,
  onChange,
  options,
  placeholder = '搜索或输入...',
  allowCustom = false,
  variant = 'default',
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState('');
  const containerRef = useRef<HTMLDivElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const [dropUp, setDropUp] = useState(false);

  // ---- close on outside click / escape ----
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
        setSearch('');
      }
    };
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsOpen(false);
        setSearch('');
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, []);

  // ---- decide drop-up direction when opening ----
  useEffect(() => {
    if (isOpen && containerRef.current) {
      const rect = containerRef.current.getBoundingClientRect();
      const spaceBelow = window.innerHeight - rect.bottom;
      setDropUp(spaceBelow < 220);
    }
  }, [isOpen]);

  // ---- derived option list ----
  const filteredOptions = useCallback(() => {
    const q = search.toLowerCase().trim();
    // Always show selected items first, then unselected that match the query.
    const selected = values.filter((v) => {
      const inOptions = options.includes(v);
      return inOptions && (q === '' || v.toLowerCase().includes(q));
    });
    const unselected = options.filter(
      (o) => !values.includes(o) && (q === '' || o.toLowerCase().includes(q)),
    );
    return { selected, unselected };
  }, [options, values, search]);

  const toggleValue = useCallback(
    (value: string) => {
      if (values.includes(value)) {
        onChange(values.filter((v) => v !== value));
      } else {
        onChange([...values, value]);
      }
    },
    [values, onChange],
  );

  const removeValue = useCallback(
    (value: string) => {
      onChange(values.filter((v) => v !== value));
    },
    [values, onChange],
  );

  const addCustom = useCallback(() => {
    const trimmed = search.trim();
    if (trimmed && !values.includes(trimmed)) {
      onChange([...values, trimmed]);
      setSearch('');
    }
  }, [search, values, onChange]);

  const handleInputKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (allowCustom) {
        addCustom();
      } else {
        // Toggle the first filtered (unselected) option if there is exactly one.
        const { unselected } = filteredOptions();
        if (unselected.length === 1) {
          toggleValue(unselected[0]);
          setSearch('');
        }
      }
    }
  };

  const { selected: selectedOpts, unselected: unselectedOpts } = filteredOptions();
  const heightClass = variant === 'compact' ? 'min-h-[30px]' : 'min-h-[34px]';

  return (
    <div ref={containerRef} className="relative">
      {/* ---- Trigger / input area ---- */}
      <div
        className={`flex flex-wrap items-center gap-1 px-2 py-1 border border-border rounded-md bg-background cursor-text ${heightClass}`}
        onClick={() => {
          setIsOpen(true);
          // Focus the hidden input when clicking the container.
          const input = containerRef.current?.querySelector('input');
          input?.focus();
        }}
      >
        {/* Selected-value chips */}
        {values.map((v) => (
          <span
            key={v}
            className="inline-flex items-center gap-1 px-1.5 py-0.5 bg-primary/15 text-primary text-xs rounded-md max-w-[280px]"
            title={v}
          >
            <span className="truncate">{v}</span>
            <button
              onClick={(e) => {
                e.stopPropagation();
                removeValue(v);
              }}
              className="flex-shrink-0 hover:text-primary/70"
            >
              <X className="w-3 h-3" />
            </button>
          </span>
        ))}

        <input
          type="text"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setIsOpen(true);
          }}
          onFocus={() => setIsOpen(true)}
          onKeyDown={handleInputKeyDown}
          placeholder={values.length === 0 ? placeholder : ''}
          className="flex-1 min-w-[80px] bg-transparent text-xs outline-none placeholder:text-muted-foreground"
        />

        {/* Custom-add button (only visible when there is typed text) */}
        {allowCustom && search.trim() && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              addCustom();
            }}
            className="flex-shrink-0 p-0.5 text-muted-foreground hover:text-foreground"
            title="添加"
          >
            <Plus className="w-3.5 h-3.5" />
          </button>
        )}

        {/* Chevron indicator */}
        <button
          onClick={(e) => {
            e.stopPropagation();
            setIsOpen((o) => !o);
          }}
          className="flex-shrink-0 p-0.5 text-muted-foreground"
        >
          {isOpen ? (
            <ChevronUp className="w-3.5 h-3.5" />
          ) : (
            <ChevronDown className="w-3.5 h-3.5" />
          )}
        </button>
      </div>

      {/* ---- Dropdown list ---- */}
      {isOpen && (
        <div
          ref={dropdownRef}
          className={`absolute z-50 left-0 right-0 max-h-52 overflow-y-auto bg-background border border-border rounded-md shadow-lg ${
            dropUp ? 'bottom-full mb-1' : 'top-full mt-1'
          }`}
        >
          {/* Predefined options – selected section */}
          {selectedOpts.length > 0 &&
            selectedOpts.map((opt) => (
              <div
                key={opt}
                onClick={() => {
                  toggleValue(opt);
                }}
                className="flex items-center gap-2 px-3 py-1.5 text-xs cursor-pointer hover:bg-muted"
              >
                <input type="checkbox" checked readOnly className="rounded" />
                <span className="truncate text-primary">{opt}</span>
              </div>
            ))}

          {/* Predefined options – unselected section */}
          {unselectedOpts.map((opt) => (
            <div
              key={opt}
              onClick={() => {
                toggleValue(opt);
              }}
              className="flex items-center gap-2 px-3 py-1.5 text-xs cursor-pointer hover:bg-muted"
            >
              <input type="checkbox" checked={false} readOnly className="rounded" />
              <span className="truncate">{opt}</span>
            </div>
          ))}

          {/* "Add custom" hint */}
          {allowCustom && search.trim() && !options.includes(search.trim()) && (
            <div
              onClick={addCustom}
              className="flex items-center gap-2 px-3 py-1.5 text-xs cursor-pointer hover:bg-muted text-primary"
            >
              <Plus className="w-3 h-3" />
              <span>添加 &quot;{search.trim()}&quot;</span>
            </div>
          )}

          {/* Empty state */}
          {selectedOpts.length === 0 && unselectedOpts.length === 0 && (
            <div className="px-3 py-2 text-xs text-muted-foreground text-center">
              {allowCustom ? '按 Enter 添加自定义项' : '无匹配项'}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default MultiSelectDropdown;
