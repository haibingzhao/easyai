import React, { useState, useRef, useEffect, useCallback } from 'react';
import { X, ChevronUp, ChevronDown, Folder, File, ChevronRight } from 'lucide-react';
import { browseDirectory } from '@/services/permission-service';
import type { FileNodeDto } from '@/types/permission';

interface FileBrowserDropdownProps {
  /** Currently selected absolute paths */
  values: string[];
  /** Called when the selection list changes */
  onChange: (values: string[]) => void;
  /** Initial path to start browsing from (typically project path; component uses its parent) */
  initialPath: string;
  /** Placeholder text when no values are selected */
  placeholder?: string;
}

/**
 * A file browser dropdown that simulates a filesystem navigation experience.
 *
 * - Shows selected paths as removable chips.
 * - Click to toggle selection, double-click directories to navigate into them.
 * - Breadcrumb navigation for jumping to parent directories.
 * - No custom input allowed (only real filesystem paths).
 */
export const FileBrowserDropdown: React.FC<FileBrowserDropdownProps> = ({
  values,
  onChange,
  initialPath,
  placeholder = '浏览并选择路径...',
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [currentPath, setCurrentPath] = useState<string>(() => {
    // Start from parent of initial path
    if (!initialPath || initialPath === '/') return '/';
    const parts = initialPath.replace(/\/$/, '').split('/');
    parts.pop();
    return parts.join('/') || '/';
  });
  const [entries, setEntries] = useState<FileNodeDto[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [dropUp, setDropUp] = useState(false);
  const clickTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const containerRef = useRef<HTMLDivElement>(null);

  // ---- Load directory contents ----
  const loadDirectory = useCallback(async (path: string) => {
    setIsLoading(true);
    try {
      const nodes = await browseDirectory(path);
      setEntries(nodes);
      setCurrentPath(path);
    } catch {
      setEntries([]);
    } finally {
      setIsLoading(false);
    }
  }, []);

  // Load initial directory on mount
  useEffect(() => {
    loadDirectory(currentPath);
  }, []);

  // ---- Cleanup debounce timers on unmount ----
  useEffect(() => {
    return () => {
      if (clickTimerRef.current) clearTimeout(clickTimerRef.current);
    };
  }, []);

  // ---- Close on outside click / escape ----
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, []);

  // ---- Decide drop-up direction when opening ----
  useEffect(() => {
    if (isOpen && containerRef.current) {
      const rect = containerRef.current.getBoundingClientRect();
      const spaceBelow = window.innerHeight - rect.bottom;
      setDropUp(spaceBelow < 280);
    }
  }, [isOpen]);

  // ---- Selection helpers ----
  const toggleSelection = useCallback(
    (path: string) => {
      if (values.includes(path)) {
        onChange(values.filter((v) => v !== path));
      } else {
        onChange([...values, path]);
      }
    },
    [values, onChange],
  );

  const removeValue = useCallback(
    (path: string) => {
      onChange(values.filter((v) => v !== path));
    },
    [values, onChange],
  );

  // ---- Navigation helpers ----
  const navigateTo = useCallback(
    (path: string) => {
      if (path !== currentPath) {
        loadDirectory(path);
      }
    },
    [currentPath, loadDirectory],
  );

  const navigateUp = useCallback(() => {
    if (currentPath === '/') return;
    const parts = currentPath.replace(/\/$/, '').split('/');
    parts.pop();
    const parent = parts.join('/') || '/';
    navigateTo(parent);
  }, [currentPath, navigateTo]);

  // ---- Click / double-click disambiguation ----
  /**
   * Single-click on an entry: toggle selection.
   * Uses a short delay so that a double-click (navigate) cancels the selection toggle.
   */
  const handleClick = useCallback(
    (path: string) => {
      if (clickTimerRef.current) clearTimeout(clickTimerRef.current);
      clickTimerRef.current = setTimeout(() => {
        toggleSelection(path);
        clickTimerRef.current = null;
      }, 200);
    },
    [toggleSelection],
  );

  /**
   * Double-click on a directory: navigate into it (cancels the pending selection toggle).
   */
  const handleDoubleClick = useCallback(
    (path: string) => {
      if (clickTimerRef.current) {
        clearTimeout(clickTimerRef.current);
        clickTimerRef.current = null;
      }
      navigateTo(path);
    },
    [navigateTo],
  );

  // ---- Breadcrumb segments ----
  const breadcrumbs = currentPath === '/' ? ['/'] : ['/', ...currentPath.split('/').filter(Boolean)];

  return (
    <div ref={containerRef} className="relative">
      {/* ---- Trigger area ---- */}
      <div
        className="flex flex-wrap items-center gap-1 px-2 py-1 border border-border rounded-md bg-background cursor-pointer min-h-[30px]"
        onClick={() => {
          setIsOpen(true);
        }}
      >
        {/* Selected-value chips */}
        {values.map((v) => {
          // Show just the filename/last segment in the chip
          const label = v.split('/').pop() || v;
          return (
            <span
              key={v}
              className="inline-flex items-center gap-1 px-1.5 py-0.5 bg-primary/15 text-primary text-xs rounded-md max-w-[280px]"
              title={v}
            >
              <span className="truncate">{label}</span>
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
          );
        })}

        {/* Placeholder */}
        {values.length === 0 && (
          <span className="text-xs text-muted-foreground">{placeholder}</span>
        )}

        {/* Chevron */}
        <button
          onClick={(e) => {
            e.stopPropagation();
            setIsOpen((o) => !o);
          }}
          className="flex-shrink-0 p-0.5 text-muted-foreground ml-auto"
        >
          {isOpen ? (
            <ChevronUp className="w-3.5 h-3.5" />
          ) : (
            <ChevronDown className="w-3.5 h-3.5" />
          )}
        </button>
      </div>

      {/* ---- Dropdown panel ---- */}
      {isOpen && (
        <div
          className={`absolute z-50 left-0 right-0 bg-background border border-border rounded-md shadow-lg ${
            dropUp ? 'bottom-full mb-1' : 'top-full mt-1'
          }`}
        >
          {/* Breadcrumb navigation */}
          <div className="flex items-center gap-0.5 px-3 py-1.5 border-b border-border text-xs overflow-x-auto">
            {breadcrumbs.map((segment, idx) => {
              const isLast = idx === breadcrumbs.length - 1;
              // Build path up to this segment
              const pathToHere =
                segment === '/'
                  ? '/'
                  : '/' + breadcrumbs.slice(1, idx + 1).join('/');
              return (
                <React.Fragment key={pathToHere}>
                  {idx > 0 && <ChevronRight className="w-3 h-3 text-muted-foreground flex-shrink-0" />}
                  <button
                    onClick={() => navigateTo(pathToHere)}
                    className={`truncate max-w-[120px] ${
                      isLast
                        ? 'text-foreground font-medium'
                        : 'text-muted-foreground hover:text-foreground'
                    }`}
                  >
                    {segment}
                  </button>
                </React.Fragment>
              );
            })}
          </div>

          {/* Directory contents */}
          <div className="max-h-52 overflow-y-auto">
            {/* Parent directory row */}
            {currentPath !== '/' && (
              <div
                onClick={navigateUp}
                onDoubleClick={navigateUp}
                className="flex items-center gap-2 px-3 py-1.5 text-xs cursor-pointer hover:bg-muted"
              >
                <Folder className="w-3.5 h-3.5 text-muted-foreground" />
                <span className="text-muted-foreground">..</span>
              </div>
            )}

            {/* Loading state */}
            {isLoading && (
              <div className="px-3 py-2 text-xs text-muted-foreground text-center">
                加载中...
              </div>
            )}

            {/* Entries */}
            {!isLoading &&
              entries.map((entry) => {
                const isSelected = values.includes(entry.path);
                const isDir = entry.type === 'directory';
                return (
                  <div
                    key={entry.path}
                    onClick={() => handleClick(entry.path)}
                    onDoubleClick={() => {
                      if (isDir) handleDoubleClick(entry.path);
                    }}
                    className={`flex items-center gap-2 px-3 py-1.5 text-xs cursor-pointer ${
                      isSelected ? 'bg-primary/10' : 'hover:bg-muted'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={isSelected}
                      readOnly
                      className="rounded flex-shrink-0"
                    />
                    {isDir ? (
                      <Folder className="w-3.5 h-3.5 text-blue-500 flex-shrink-0" />
                    ) : (
                      <File className="w-3.5 h-3.5 text-muted-foreground flex-shrink-0" />
                    )}
                    <span className={`truncate ${isSelected ? 'text-primary font-medium' : ''}`}>
                      {entry.name}
                    </span>
                  </div>
                );
              })}

            {/* Empty state */}
            {!isLoading && entries.length === 0 && (
              <div className="px-3 py-2 text-xs text-muted-foreground text-center">
                {currentPath === '/' ? '根目录' : '空目录'}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default FileBrowserDropdown;
