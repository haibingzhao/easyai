import { useState, useCallback, useRef, useEffect } from 'react';
import { useProjectStore } from '@/services/stores/project-store';
import { browseDirectory, searchFiles } from '@/services/permission-service';
import type { FileNodeDto } from '@/types/permission';

export type MentionViewMode = 'default' | 'files' | 'folders';

export interface MentionItem {
  name: string;
  path: string;
  type: 'file' | 'directory';
  parentPath?: string;
}

interface UseMentionReturn {
  isOpen: boolean;
  viewMode: MentionViewMode;
  query: string;
  items: MentionItem[];
  selectedIndex: number;
  hoveredItem: MentionItem | null;
  currentPath: string | null;
  onInput: (text: string, cursorPosition: number) => void;
  onKeyDown: (e: React.KeyboardEvent) => boolean;
  enterView: (mode: 'files' | 'folders') => void;
  exitView: () => void;
  setHoveredItem: (item: MentionItem | null) => void;
  close: () => void;
  /** The @ trigger range start position in text (for chip insertion) */
  atTriggerIndex: number;
}

const MAX_ITEMS = 50;
const DEBOUNCE_MS = 150;

/** Characters that may appear immediately before a valid @ trigger */
const VALID_PRECEDING_RE = /^[\s\p{P}]$/u;

/** Convert a FileNodeDto to a MentionItem */
function toMentionItem(node: FileNodeDto, parentPath?: string): MentionItem {
  return {
    name: node.name,
    path: node.path,
    type: node.type === 'directory' ? 'directory' : 'file',
    parentPath,
  };
}

/** Extract parent directory path from a full file path */
function getParentPath(fullPath: string): string {
  const idx = fullPath.lastIndexOf('/');
  return idx > 0 ? fullPath.slice(0, idx) : '';
}

/** Filter items by a case-insensitive query on name */
function filterByQuery(items: MentionItem[], q: string): MentionItem[] {
  if (!q) return items;
  const lower = q.toLowerCase();
  return items.filter((item) => item.name.toLowerCase().includes(lower));
}

/**
 * Hook for @ mention autocomplete in the message editor.
 *
 * Detects `@` triggers anywhere in text, browses project files/folders,
 * and provides keyboard navigation for selection.
 */
export function useMention(): UseMentionReturn {
  const [isOpen, setIsOpen] = useState(false);
  const [viewMode, setViewMode] = useState<MentionViewMode>('default');
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<MentionItem[]>([]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [hoveredItem, setHoveredItem] = useState<MentionItem | null>(null);
  const [currentPath, setCurrentPath] = useState<string | null>(null);
  const [atTriggerIndex, setAtTriggerIndex] = useState(-1);

  const projectPath = useProjectStore((s) => s.currentProject?.path ?? null);
  const projectId = useProjectStore((s) => s.currentProject?.id ?? null);

  // Cache for directory listings: path -> FileNodeDto[]
  const cacheRef = useRef<Map<string, FileNodeDto[]>>(new Map());
  // Debounce timer ref
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Track latest request to avoid stale updates
  const requestIdRef = useRef(0);
  // Refs for reading current state inside async callbacks / setTimeout
  const viewModeRef = useRef<MentionViewMode>('default');
  const currentPathRef = useRef<string | null>(null);

  // Clear cache when project changes
  useEffect(() => {
    cacheRef.current.clear();
  }, [projectPath]);

  // Cleanup debounce timer on unmount
  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  const close = useCallback(() => {
    setIsOpen(false);
    setQuery('');
    setItems([]);
    setSelectedIndex(0);
    setViewMode('default');
    viewModeRef.current = 'default';
    setCurrentPath(null);
    currentPathRef.current = null;
    setAtTriggerIndex(-1);
    setHoveredItem(null);
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
      debounceRef.current = null;
    }
  }, []);

  /** Load directory contents with caching */
  const loadDirectory = useCallback(async (dirPath: string): Promise<FileNodeDto[]> => {
    const cached = cacheRef.current.get(dirPath);
    if (cached) return cached;
    try {
      const nodes = await browseDirectory(dirPath, projectId ?? undefined);
      cacheRef.current.set(dirPath, nodes);
      return nodes;
    } catch {
      return [];
    }
  }, [projectId]);

  /** Load items for default view (no query): root project dir files */
  const loadDefaultItems = useCallback(async () => {
    if (!projectPath) return;
    const reqId = ++requestIdRef.current;
    const nodes = await loadDirectory(projectPath);
    if (reqId !== requestIdRef.current) return;
    const mentionItems = nodes.map((n) => toMentionItem(n, projectPath));
    setItems(mentionItems.slice(0, MAX_ITEMS));
    setSelectedIndex(0);
  }, [projectPath, loadDirectory]);

  /** Load items for default view with query: search recursively via backend API */
  const loadSearchItems = useCallback((q: string) => {
    if (!projectPath || !projectId) return;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      const reqId = ++requestIdRef.current;
      try {
        const nodes = await searchFiles(projectId, q);
        if (reqId !== requestIdRef.current) return;
        const mentionItems = nodes.map((n) => toMentionItem(n, getParentPath(n.path)));
        setItems(mentionItems.slice(0, MAX_ITEMS));
        setSelectedIndex(0);
      } catch {
        if (reqId !== requestIdRef.current) return;
        setItems([]);
        setSelectedIndex(0);
      }
    }, DEBOUNCE_MS);
  }, [projectPath, projectId]);

  /** Load items for files/folders view */
  const loadViewItems = useCallback(async (dirPath: string, mode: 'files' | 'folders', q: string) => {
    const reqId = ++requestIdRef.current;
    const nodes = await loadDirectory(dirPath);
    if (reqId !== requestIdRef.current) return;
    let filtered = nodes
      .filter((n) => {
        if (mode === 'files') return n.type === 'file';
        if (mode === 'folders') return n.type === 'directory';
        return true;
      })
      .map((n) => toMentionItem(n, dirPath));
    if (q) {
      filtered = filterByQuery(filtered, q);
    }
    setItems(filtered.slice(0, MAX_ITEMS));
    setSelectedIndex(0);
  }, [loadDirectory]);

  const onInput = useCallback((text: string, cursorPosition: number) => {
    if (!projectPath) {
      close();
      return;
    }

    // Find last @ before cursor
    const atIdx = text.lastIndexOf('@', cursorPosition - 1);
    if (atIdx === -1) {
      close();
      return;
    }

    // Validate character before @
    if (atIdx > 0) {
      const charBefore = text[atIdx - 1];
      if (!VALID_PRECEDING_RE.test(charBefore)) {
        close();
        return;
      }
    }

    // Extract query
    const q = text.slice(atIdx + 1, cursorPosition);

    // Query must be a single token (no spaces)
    if (q.includes(' ')) {
      close();
      return;
    }

    setAtTriggerIndex(atIdx);
    setQuery(q);
    setIsOpen(true);

    // Load items based on current view mode
    // Use a timeout to let state updates batch before async loads
    setTimeout(() => {
      const vm = viewModeRef.current;
      const cp = currentPathRef.current;
      if (vm === 'default') {
        if (!q) {
          loadDefaultItems();
        } else {
          loadSearchItems(q);
        }
      } else if (vm === 'files' || vm === 'folders') {
        const dirToLoad = cp ?? projectPath;
        if (q) {
          if (debounceRef.current) clearTimeout(debounceRef.current);
          debounceRef.current = setTimeout(() => {
            loadViewItems(dirToLoad, vm, q);
          }, DEBOUNCE_MS);
        } else {
          loadViewItems(dirToLoad, vm, '');
        }
      }
    }, 0);
  }, [projectPath, close, loadDefaultItems, loadSearchItems, loadViewItems]);

  const enterView = useCallback((mode: 'files' | 'folders') => {
    if (!projectPath) return;
    setViewMode(mode);
    viewModeRef.current = mode;
    setCurrentPath(projectPath);
    currentPathRef.current = projectPath;
    setQuery('');
    loadViewItems(projectPath, mode, '');
  }, [projectPath, loadViewItems]);

  const exitView = useCallback(() => {
    setViewMode('default');
    viewModeRef.current = 'default';
    setCurrentPath(null);
    currentPathRef.current = null;
    setQuery('');
    loadDefaultItems();
  }, [loadDefaultItems]);

  const onKeyDown = useCallback((e: React.KeyboardEvent): boolean => {
    if (!isOpen) return false;

    const currentViewMode = viewModeRef.current;
    const currentQuery = query;

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setSelectedIndex((prev) => (items.length > 0 ? (prev + 1) % items.length : 0));
        return true;

      case 'ArrowUp':
        e.preventDefault();
        setSelectedIndex((prev) => (items.length > 0 ? (prev - 1 + items.length) % items.length : 0));
        return true;

      case 'Tab':
      case 'Enter':
        // Return false so MessageEditor handles the actual chip insertion
        return false;

      case 'Escape':
        e.preventDefault();
        if (currentViewMode !== 'default') {
          exitView();
        } else {
          close();
        }
        return true;

      case 'Backspace':
        if (currentQuery === '' && currentViewMode !== 'default') {
          e.preventDefault();
          exitView();
          return true;
        }
        return false;

      default:
        return false;
    }
  }, [isOpen, items.length, viewMode, query, close, exitView]);

  return {
    isOpen,
    viewMode,
    query,
    items,
    selectedIndex,
    hoveredItem,
    currentPath,
    onInput,
    onKeyDown,
    enterView,
    exitView,
    setHoveredItem,
    close,
    atTriggerIndex,
  };
}
