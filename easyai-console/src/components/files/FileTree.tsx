import React, { useState, useCallback, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { ChevronRight, ChevronDown, Folder, FolderOpen, File, Loader2, AlertCircle } from 'lucide-react';
import { browseDirectory, createDirectory, fetchServerPlatform, revealInFinder } from '@/services/permission-service';
import type { FileNodeDto } from '@/types/permission';
import { useNavStore } from '@/services/stores/nav-store';
import { i18n } from '@/utils/i18n';

interface FileTreeProps {
  /** Absolute path to the project root directory */
  rootPath: string;
  /** Project ID for API path validation */
  projectId: string;
  /** Callback when a file is selected */
  onFileSelect: (path: string) => void;
  /** Currently selected file path */
  selectedFile: string | null;
  /** External path to reveal and expand to in the tree */
  revealPath?: string | null;
  /** Increment to force a full tree refresh (re-fetches root and preserves expanded dirs) */
  refreshToken?: number;
}

/** Track state for each directory node */
interface DirState {
  expanded: boolean;
  loaded: boolean;
  loading: boolean;
  error: boolean;
  children: FileNodeDto[];
}

/**
 * Lazy-loading file tree component.
 * Uses the browseDirectory API to load one directory level at a time.
 */
interface ContextMenuState {
  x: number;
  y: number;
  absolutePath: string;
  relativePath: string;
  nodeType: 'file' | 'directory';
}

export const FileTree: React.FC<FileTreeProps> = ({ rootPath, projectId, onFileSelect, selectedFile, revealPath, refreshToken }) => {
  const [dirStates, setDirStates] = useState<Record<string, DirState>>({});
  const dirStatesRef = useRef<Record<string, DirState>>({});
  const treeContainerRef = useRef<HTMLDivElement>(null);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const contextMenuRef = useRef<HTMLDivElement>(null);
  /** Backend host platform; gates OS-specific actions like "Reveal in Finder" */
  const [serverPlatform, setServerPlatform] = useState<string | null>(null);
  /** Directory path where the inline new-folder input is currently shown */
  const [creatingIn, setCreatingIn] = useState<string | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  /** Transient bottom toast for action failures */
  const [notice, setNotice] = useState<string | null>(null);
  /** In-flight browseDirectory promises, keyed by directory path */
  const inFlightRef = useRef<Map<string, Promise<FileNodeDto[]>>>(new Map());

  const handleContextMenu = useCallback((e: React.MouseEvent, absolutePath: string, nodeType: 'file' | 'directory') => {
    e.preventDefault();
    e.stopPropagation();
    const relativePath = absolutePath.startsWith(rootPath)
      ? absolutePath.slice(rootPath.length + 1) || rootPath.split('/').pop() || ''
      : absolutePath;
    setContextMenu({ x: e.clientX, y: e.clientY, absolutePath, relativePath, nodeType });
  }, [rootPath]);

  // Close context menu on click outside or scroll
  useEffect(() => {
    if (!contextMenu) return;
    const handleClick = (e: MouseEvent) => {
      if (contextMenuRef.current && !contextMenuRef.current.contains(e.target as Node)) {
        setContextMenu(null);
      }
    };
    const handleScroll = () => setContextMenu(null);
    const handleEscape = (e: KeyboardEvent) => { if (e.key === 'Escape') setContextMenu(null); };
    document.addEventListener('mousedown', handleClick);
    document.addEventListener('scroll', handleScroll, true);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('mousedown', handleClick);
      document.removeEventListener('scroll', handleScroll, true);
      document.removeEventListener('keydown', handleEscape);
    };
  }, [contextMenu]);

  const copyToClipboard = useCallback(async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // Fallback
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    setContextMenu(null);
  }, []);

  const handleAddToChat = useCallback(() => {
    if (!contextMenu) return;
    useNavStore.getState().requestAddFileToChat(contextMenu.absolutePath, contextMenu.nodeType);
    setContextMenu(null);
  }, [contextMenu]);

  // Probe the backend host platform once; hide OS-specific actions when unknown
  useEffect(() => {
    let cancelled = false;
    fetchServerPlatform()
      .then((platform) => { if (!cancelled) setServerPlatform(platform); })
      .catch(() => { /* older backend without the endpoint: keep actions hidden */ });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(null), 2500);
    return () => clearTimeout(timer);
  }, [notice]);

  // Keep ref in sync with state (also sync synchronously in setDirStates calls below)
  useEffect(() => {
    dirStatesRef.current = dirStates;
  }, [dirStates]);

  // Reset all state when rootPath or projectId changes (project switch)
  useEffect(() => {
    dirStatesRef.current = {};
    setDirStates({});
  }, [rootPath, projectId]);

  // Refresh: reset all state and re-expand root (+ any previously expanded dirs)
  useEffect(() => {
    if (!rootPath || refreshToken === undefined || refreshToken === 0) return;
    // Collect currently expanded directory paths before resetting
    const expandedPaths = Object.entries(dirStatesRef.current)
      .filter(([, s]) => s.expanded)
      .map(([p]) => p);
    // Reset
    dirStatesRef.current = {};
    inFlightRef.current.clear();
    setDirStates({});
    // Re-expand root immediately
    let cancelled = false;
    setDirStates((prev) => {
      const next = {
        ...prev,
        [rootPath]: { expanded: true, loaded: false, loading: true, error: false, children: [] as FileNodeDto[] },
      };
      dirStatesRef.current = next;
      return next;
    });
    const promise = browseDirectory(rootPath, projectId);
    inFlightRef.current.set(rootPath, promise);
    promise.then(async (children) => {
      inFlightRef.current.delete(rootPath);
      if (!cancelled) {
        setDirStates((prev) => {
          const next = {
            ...prev,
            [rootPath]: { expanded: true, loaded: true, loading: false, error: false, children },
          };
          dirStatesRef.current = next;
          return next;
        });
        // Re-expand previously expanded directories (skip root, already expanded)
        for (const dirPath of expandedPaths) {
          if (cancelled || dirPath === rootPath) continue;
          if (!dirPath.startsWith(rootPath)) continue;
          try {
            const dirPromise = browseDirectory(dirPath, projectId);
            inFlightRef.current.set(dirPath, dirPromise);
            const dirChildren = await dirPromise;
            inFlightRef.current.delete(dirPath);
            if (!cancelled) {
              setDirStates((prev) => {
                const next = {
                  ...prev,
                  [dirPath]: { expanded: true, loaded: true, loading: false, error: false, children: dirChildren },
                };
                dirStatesRef.current = next;
                return next;
              });
            }
          } catch {
            inFlightRef.current.delete(dirPath);
          }
        }
      }
    }).catch(() => {
      inFlightRef.current.delete(rootPath);
      if (!cancelled) {
        setDirStates((prev) => {
          const next = {
            ...prev,
            [rootPath]: { expanded: true, loaded: true, loading: false, error: true, children: [] as FileNodeDto[] },
          };
          dirStatesRef.current = next;
          return next;
        });
      }
    });
    return () => { cancelled = true; };
  }, [refreshToken]);

  // Auto-expand the root directory on mount / project switch
  useEffect(() => {
    if (!rootPath) return;
    let cancelled = false;
    setDirStates((prev) => {
      const next = {
        ...prev,
        [rootPath]: { expanded: true, loaded: false, loading: true, error: false, children: [] as FileNodeDto[] },
      };
      dirStatesRef.current = next;
      return next;
    });
    const promise = browseDirectory(rootPath, projectId);
    inFlightRef.current.set(rootPath, promise);
    promise.then((children) => {
      inFlightRef.current.delete(rootPath);
      if (!cancelled) {
        setDirStates((prev) => {
          const next = {
            ...prev,
            [rootPath]: { expanded: true, loaded: true, loading: false, error: false, children },
          };
          dirStatesRef.current = next;
          return next;
        });
      }
    }).catch(() => {
      inFlightRef.current.delete(rootPath);
      if (!cancelled) {
        setDirStates((prev) => {
          const next = {
            ...prev,
            [rootPath]: { expanded: true, loaded: true, loading: false, error: true, children: [] as FileNodeDto[] },
          };
          dirStatesRef.current = next;
          return next;
        });
      }
    });
    return () => { cancelled = true; };
  }, [rootPath, projectId]);

  const toggleDirectory = useCallback(async (path: string) => {
    // Use ref to read current state synchronously (avoids side-effect in state updater)
    const current = dirStatesRef.current[path];

    if (current?.expanded && current.loaded) {
      // Already loaded — just collapse
      setDirStates((prev) => {
        const next = { ...prev, [path]: { ...prev[path], expanded: false } };
        dirStatesRef.current = next;
        return next;
      });
      return;
    }

    if (current?.expanded && !current.loaded) {
      // Currently loading, ignore duplicate click
      return;
    }

    if (current?.loaded) {
      // Previously loaded but collapsed — just expand
      setDirStates((prev) => {
        const next = { ...prev, [path]: { ...prev[path], expanded: true } };
        dirStatesRef.current = next;
        return next;
      });
      return;
    }

    // Not loaded yet — mark as loading and fetch
    setDirStates((prev) => {
      const next = {
        ...prev,
        [path]: { expanded: true, loaded: false, loading: true, error: false, children: [] as FileNodeDto[] },
      };
      dirStatesRef.current = next;
      return next;
    });

    try {
      const promise = browseDirectory(path, projectId);
      inFlightRef.current.set(path, promise);
      const children = await promise;
      inFlightRef.current.delete(path);
      setDirStates((prev) => {
        const next = {
          ...prev,
          [path]: { expanded: true, loaded: true, loading: false, error: false, children },
        };
        dirStatesRef.current = next;
        return next;
      });
    } catch (err) {
      inFlightRef.current.delete(path);
      console.error(`[FileTree] Failed to browse directory: ${path}`, err);
      setDirStates((prev) => {
        const next = {
          ...prev,
          [path]: { expanded: true, loaded: true, loading: false, error: true, children: [] as FileNodeDto[] },
        };
        dirStatesRef.current = next;
        return next;
      });
    }
  }, [projectId]);

  const refreshDirectory = useCallback(async (path: string) => {
    try {
      const children = await browseDirectory(path, projectId);
      setDirStates((prev) => {
        const next = {
          ...prev,
          [path]: { expanded: true, loaded: true, loading: false, error: false, children },
        };
        dirStatesRef.current = next;
        return next;
      });
    } catch (err) {
      console.error(`[FileTree] Failed to refresh directory: ${path}`, err);
    }
  }, [projectId]);

  const startCreating = useCallback((dirPath: string) => {
    setCreateError(null);
    setCreatingIn(dirPath);
    if (!dirStatesRef.current[dirPath]?.expanded) {
      void toggleDirectory(dirPath);
    }
  }, [toggleDirectory]);

  const handleCreateSubmit = useCallback(async (name: string) => {
    const parent = creatingIn;
    if (!parent) return;
    if (!name || name === '.' || name === '..' || name.includes('/') || name.includes('\\')) {
      setCreateError(i18n('Invalid folder name'));
      return;
    }
    if (dirStatesRef.current[parent]?.children.some((child) => child.name === name)) {
      setCreateError(i18n('Folder already exists'));
      return;
    }
    try {
      await createDirectory(parent, name, projectId);
      setCreatingIn(null);
      setCreateError(null);
      await refreshDirectory(parent);
    } catch (err) {
      console.error('[FileTree] Failed to create folder', err);
      setCreateError(i18n('Failed to create folder'));
    }
  }, [creatingIn, projectId, refreshDirectory]);

  const handleCreateCancel = useCallback(() => {
    setCreatingIn(null);
    setCreateError(null);
  }, []);

  const handleReveal = useCallback(async () => {
    if (!contextMenu) return;
    const { absolutePath } = contextMenu;
    setContextMenu(null);
    try {
      await revealInFinder(absolutePath, projectId);
    } catch (err) {
      console.error('[FileTree] Failed to reveal in Finder', err);
      setNotice(i18n('Failed to reveal in Finder'));
    }
  }, [contextMenu, projectId]);

  /** Root folder name from path */
  const rootName = rootPath.split('/').pop() || rootPath;

  // Reveal path: expand tree to target file and select it
  useEffect(() => {
    if (!revealPath || !rootPath) return;
    if (!revealPath.startsWith(rootPath)) return;

    let cancelled = false;
    const relativePath = revealPath.slice(rootPath.length);
    const segments = relativePath.split('/').filter(Boolean);
    if (segments.length === 0) return;

    // Build chain of directory paths to expand
    const expandChain: string[] = [rootPath];
    let currentPath = rootPath;
    for (let i = 0; i < segments.length - 1; i++) {
      currentPath = currentPath + '/' + segments[i];
      expandChain.push(currentPath);
    }

    // Sequentially expand each directory level
    (async () => {
      for (const dirPath of expandChain) {
        if (cancelled) return;
        const state = dirStatesRef.current[dirPath];
        if (state?.loaded) {
          // Already loaded, just expand
          setDirStates((prev) => ({
            ...prev,
            [dirPath]: { ...prev[dirPath], expanded: true },
          }));
        } else if (state?.loading) {
          // Another effect is already loading this directory — wait for it
          const inFlight = inFlightRef.current.get(dirPath);
          if (inFlight) {
            try {
              await inFlight;
            } catch {
              return; // Loading failed, stop expanding
            }
          } else {
            // Loading state but no promise tracked — poll briefly
            while (dirStatesRef.current[dirPath]?.loading && !cancelled) {
              await new Promise((r) => setTimeout(r, 50));
            }
          }
          if (cancelled) return;
          // After loading completes, just expand
          setDirStates((prev) => ({
            ...prev,
            [dirPath]: { ...prev[dirPath], expanded: true },
          }));
        } else {
          // Need to load children ourselves
          try {
            const promise = browseDirectory(dirPath, projectId);
            inFlightRef.current.set(dirPath, promise);
            const children = await promise;
            inFlightRef.current.delete(dirPath);
            if (cancelled) return;
            setDirStates((prev) => ({
              ...prev,
              [dirPath]: { expanded: true, loaded: true, loading: false, error: false, children },
            }));
          } catch {
            inFlightRef.current.delete(dirPath);
            if (cancelled) return;
            setDirStates((prev) => ({
              ...prev,
              [dirPath]: { expanded: true, loaded: true, loading: false, error: true, children: [] },
            }));
            return; // Stop expanding on error
          }
        }
      }

      // Select the target file after expanding
      if (!cancelled) {
        onFileSelect(revealPath);
        // Scroll to the selected node after a short delay for DOM update
        requestAnimationFrame(() => {
          const container = treeContainerRef.current;
          if (!container) return;
          const selectedEl = container.querySelector('[data-selected="true"]');
          selectedEl?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        });
      }
    })();

    return () => { cancelled = true; };
  }, [revealPath, rootPath, projectId, onFileSelect]);

  return (
    <div ref={treeContainerRef} className="h-full overflow-y-auto text-sm">
      {/* Root node */}
      <div
        className="flex items-center gap-1 px-2 py-1.5 cursor-pointer hover:bg-muted transition-colors font-medium"
        onClick={() => toggleDirectory(rootPath)}
        onContextMenu={(e) => handleContextMenu(e, rootPath, 'directory')}
      >
        {dirStates[rootPath]?.expanded
          ? <ChevronDown className="w-3.5 h-3.5 shrink-0 text-muted-foreground" />
          : <ChevronRight className="w-3.5 h-3.5 shrink-0 text-muted-foreground" />
        }
        {dirStates[rootPath]?.expanded
          ? <FolderOpen className="w-4 h-4 shrink-0 text-blue-500" />
          : <Folder className="w-4 h-4 shrink-0 text-blue-500" />
        }
        <span className="truncate">{rootName}</span>
      </div>

      {/* Root children */}
      {dirStates[rootPath]?.expanded && (
        <div className="ml-2">
          {creatingIn === rootPath && (
            <NewFolderRow
              depth={1}
              error={createError}
              onSubmit={handleCreateSubmit}
              onCancel={handleCreateCancel}
            />
          )}
          {dirStates[rootPath]?.loading && (
            <div className="flex items-center gap-2 px-2 py-1 text-muted-foreground">
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
              <span className="text-xs">Loading...</span>
            </div>
          )}
          {dirStates[rootPath]?.error && !dirStates[rootPath]?.loading && (
            <div className="flex items-center gap-2 px-2 py-1 text-destructive">
              <AlertCircle className="w-3.5 h-3.5" />
              <span className="text-xs">Failed to load</span>
            </div>
          )}
          {dirStates[rootPath]?.children.map((node) => (
            <TreeNode
              key={node.path}
              node={node}
              depth={1}
              selectedFile={selectedFile}
              dirStates={dirStates}
              creatingIn={creatingIn}
              createError={createError}
              onToggle={toggleDirectory}
              onSelect={onFileSelect}
              onContextMenu={handleContextMenu}
              onCreateSubmit={handleCreateSubmit}
              onCreateCancel={handleCreateCancel}
            />
          ))}
        </div>
      )}

      {/* Context menu */}
      {contextMenu && createPortal(
        <div
          ref={contextMenuRef}
          className="fixed z-[9999] min-w-[180px] py-1 bg-popover border border-border rounded-md shadow-lg text-sm"
          style={{ left: contextMenu.x, top: contextMenu.y }}
        >
          {contextMenu.nodeType === 'directory' && (
            <button
              className="w-full text-left px-3 py-1.5 hover:bg-accent transition-colors flex items-center gap-2"
              onClick={() => {
                const dirPath = contextMenu.absolutePath;
                setContextMenu(null);
                startCreating(dirPath);
              }}
            >
              <span className="text-xs text-muted-foreground">{i18n('New Folder')}</span>
            </button>
          )}
          {contextMenu.nodeType === 'directory' && serverPlatform === 'macos' && (
            <button
              className="w-full text-left px-3 py-1.5 hover:bg-accent transition-colors flex items-center gap-2"
              onClick={handleReveal}
            >
              <span className="text-xs text-muted-foreground">{i18n('Reveal in Finder')}</span>
            </button>
          )}
          <button
            className="w-full text-left px-3 py-1.5 hover:bg-accent transition-colors flex items-center gap-2"
            onClick={() => copyToClipboard(contextMenu.relativePath)}
          >
            <span className="text-xs text-muted-foreground">{i18n('Copy Relative Path')}</span>
          </button>
          <button
            className="w-full text-left px-3 py-1.5 hover:bg-accent transition-colors flex items-center gap-2"
            onClick={() => copyToClipboard(contextMenu.absolutePath)}
          >
            <span className="text-xs text-muted-foreground">{i18n('Copy Absolute Path')}</span>
          </button>
          <button
            className="w-full text-left px-3 py-1.5 hover:bg-accent transition-colors flex items-center gap-2"
            onClick={handleAddToChat}
          >
            <span className="text-xs text-muted-foreground">{i18n('Add to Chat')}</span>
          </button>
        </div>,
        document.body,
      )}

      {/* Transient failure toast */}
      {notice && createPortal(
        <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-[9999] flex items-center gap-1.5 px-3 py-1.5 bg-foreground text-background text-xs rounded-md shadow-lg">
          <AlertCircle className="w-3.5 h-3.5" />
          {notice}
        </div>,
        document.body,
      )}
    </div>
  );
};

interface TreeNodeProps {
  node: FileNodeDto;
  depth: number;
  selectedFile: string | null;
  dirStates: Record<string, DirState>;
  creatingIn: string | null;
  createError: string | null;
  onToggle: (path: string) => void;
  onSelect: (path: string) => void;
  onContextMenu: (e: React.MouseEvent, absolutePath: string, nodeType: 'file' | 'directory') => void;
  onCreateSubmit: (name: string) => void;
  onCreateCancel: () => void;
}

const TreeNode: React.FC<TreeNodeProps> = ({
  node,
  depth,
  selectedFile,
  dirStates,
  creatingIn,
  createError,
  onToggle,
  onSelect,
  onContextMenu,
  onCreateSubmit,
  onCreateCancel,
}) => {
  const isDir = node.type === 'directory';
  const state = isDir ? dirStates[node.path] : undefined;
  const isSelected = selectedFile === node.path;

  const handleClick = () => {
    if (isDir) {
      onToggle(node.path);
    } else {
      onSelect(node.path);
    }
  };

  return (
    <div>
      <div
        className={`flex items-center gap-1 py-1 pr-2 cursor-pointer transition-colors ${
          isSelected ? 'bg-muted font-medium' : 'hover:bg-muted'
        }`}
        style={{ paddingLeft: `${depth * 12 + 8}px` }}
        onClick={handleClick}
        onContextMenu={(e) => onContextMenu(e, node.path, node.type)}
        title={node.path}
        data-selected={isSelected ? 'true' : undefined}
      >
        {isDir ? (
          <>
            {state?.loading ? (
              <Loader2 className="w-3 h-3 shrink-0 animate-spin text-muted-foreground" />
            ) : state?.expanded ? (
              <ChevronDown className="w-3 h-3 shrink-0 text-muted-foreground" />
            ) : (
              <ChevronRight className="w-3 h-3 shrink-0 text-muted-foreground" />
            )}
            {state?.expanded ? (
              <FolderOpen className="w-3.5 h-3.5 shrink-0 text-blue-500" />
            ) : (
              <Folder className="w-3.5 h-3.5 shrink-0 text-blue-500" />
            )}
          </>
        ) : (
          <>
            <span className="w-3 shrink-0" />
            <FileIcon name={node.name} />
          </>
        )}
        <span className="truncate text-xs">{node.name}</span>
      </div>

      {/* Error indicator for failed directory loads */}
      {isDir && state?.error && state.expanded && !state.loading && (
        <div
          className="flex items-center gap-2 py-1 text-destructive"
          style={{ paddingLeft: `${(depth + 1) * 12 + 8}px` }}
        >
          <AlertCircle className="w-3 h-3" />
          <span className="text-xs">Failed to load</span>
        </div>
      )}

      {/* Inline input for creating a subfolder inside this directory */}
      {isDir && creatingIn === node.path && (
        <NewFolderRow
          depth={depth + 1}
          error={createError}
          onSubmit={onCreateSubmit}
          onCancel={onCreateCancel}
        />
      )}

      {/* Children (for expanded directories) */}
      {isDir && state?.expanded && state.loaded && (
        <div>
          {state.children.map((child) => (
            <TreeNode
              key={child.path}
              node={child}
              depth={depth + 1}
              selectedFile={selectedFile}
              dirStates={dirStates}
              creatingIn={creatingIn}
              createError={createError}
              onToggle={onToggle}
              onSelect={onSelect}
              onContextMenu={onContextMenu}
              onCreateSubmit={onCreateSubmit}
              onCreateCancel={onCreateCancel}
            />
          ))}
        </div>
      )}
    </div>
  );
};

/** Inline input row for naming a new folder: Enter creates, Escape cancels, blur creates. */
const NewFolderRow: React.FC<{
  depth: number;
  error: string | null;
  onSubmit: (name: string) => void;
  onCancel: () => void;
}> = ({ depth, error, onSubmit, onCancel }) => {
  const [value, setValue] = useState('');
  /** Guards against double submission (Enter then blur) */
  const settledRef = useRef(false);

  const submit = () => {
    if (settledRef.current) return;
    settledRef.current = true;
    const name = value.trim();
    if (name) {
      onSubmit(name);
    } else {
      onCancel();
    }
  };

  return (
    <div className="py-1 pr-2" style={{ paddingLeft: `${depth * 12 + 8}px` }}>
      <div className="flex items-center gap-1">
        <span className="w-3 shrink-0" />
        <Folder className="w-3.5 h-3.5 shrink-0 text-blue-500" />
        <input
          autoFocus
          value={value}
          placeholder={i18n('Folder name')}
          onChange={(e) => setValue(e.target.value)}
          onFocus={() => { settledRef.current = false; }}
          onBlur={submit}
          onClick={(e) => e.stopPropagation()}
          onKeyDown={(e) => {
            e.stopPropagation();
            if (e.key === 'Enter') {
              e.preventDefault();
              submit();
            } else if (e.key === 'Escape') {
              e.preventDefault();
              settledRef.current = true;
              onCancel();
            }
          }}
          className="flex-1 min-w-0 bg-transparent border border-blue-500 rounded px-1 text-xs outline-none"
        />
      </div>
      {error && (
        <div className="ml-8 mt-0.5 text-xs text-destructive">{error}</div>
      )}
    </div>
  );
};

/** File icon based on extension */
const FileIcon: React.FC<{ name: string }> = ({ name }) => {
  const ext = name.includes('.') ? name.split('.').pop()?.toLowerCase() : '';

  let className = 'w-3.5 h-3.5 shrink-0';

  if (['md', 'mdx'].includes(ext || '')) {
    className += ' text-blue-600';
  } else if (['ts', 'tsx', 'js', 'jsx', 'kt', 'kts', 'java', 'py', 'go', 'rs'].includes(ext || '')) {
    className += ' text-yellow-600';
  } else if (['json', 'yaml', 'yml', 'xml', 'toml', 'properties'].includes(ext || '')) {
    className += ' text-orange-500';
  } else if (['html', 'htm', 'css', 'scss'].includes(ext || '')) {
    className += ' text-green-600';
  } else if (['log'].includes(ext || '')) {
    className += ' text-gray-400';
  } else if (name.startsWith('.') || ['gitignore', 'qoderignore'].includes(name)) {
    className += ' text-gray-400';
  } else {
    className += ' text-muted-foreground';
  }

  return <File className={className} />;
};
