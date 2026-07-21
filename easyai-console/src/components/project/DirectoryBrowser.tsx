import { useState, useEffect, useCallback } from 'react';
import { Folder, ChevronRight, ShieldAlert, ExternalLink } from 'lucide-react';
import { projectService } from '@/services/project-service';
import { i18n } from '@/utils/i18n';

interface DirectoryBrowserProps {
  initialPath?: string;
  onSelect: (path: string) => void;
  onCancel: () => void;
}

export function DirectoryBrowser({ initialPath, onSelect, onCancel }: DirectoryBrowserProps) {
  const [currentPath, setCurrentPath] = useState('');
  const [directories, setDirectories] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedPath, setSelectedPath] = useState('');
  const [permissionDenied, setPermissionDenied] = useState(false);

  // Load directories from the backend
  const loadDirectories = useCallback(async (path?: string) => {
    setLoading(true);
    try {
      const result = await projectService.listDirectories(path);
      setCurrentPath(result.currentPath);
      setDirectories(result.directories);
      setPermissionDenied(result.permissionDenied ?? false);
      setSelectedPath('');
    } finally {
      setLoading(false);
    }
  }, []);

  // On mount: if initialPath is provided, browse its parent directory
  useEffect(() => {
    if (initialPath) {
      // Extract parent directory from initialPath
      const lastSlash = initialPath.lastIndexOf('/');
      const parentPath = lastSlash > 0 ? initialPath.substring(0, lastSlash) : undefined;
      loadDirectories(parentPath);
    } else {
      loadDirectories();
    }
  }, []);

  // Split path into segments for breadcrumb
  const getBreadcrumbs = () => {
    if (!currentPath) return [];
    const parts = currentPath.split('/').filter(Boolean);
    const crumbs: { label: string; path: string }[] = [];
    let accumulated = '';
    for (const part of parts) {
      accumulated += '/' + part;
      crumbs.push({ label: part, path: accumulated });
    }
    return crumbs;
  };

  // Extract directory name from full path
  const getDirName = (fullPath: string): string => {
    const parts = fullPath.split('/');
    return parts[parts.length - 1] || fullPath;
  };

  // Navigate to a breadcrumb segment
  const handleBreadcrumbClick = (path: string) => {
    loadDirectories(path);
  };

  // Double-click to enter a directory
  const handleDoubleClick = (dirPath: string) => {
    loadDirectories(dirPath);
  };

  // Single click to select
  const handleClick = (dirPath: string) => {
    setSelectedPath(dirPath);
  };

  // Confirm selection
  const handleConfirm = () => {
    if (selectedPath) {
      onSelect(selectedPath);
    } else if (currentPath) {
      onSelect(currentPath);
    }
  };

  const breadcrumbs = getBreadcrumbs();

  return (
    <div className="border border-border rounded-md bg-background">
      {/* Breadcrumb navigation */}
      <div className="flex items-center gap-0.5 px-2 py-1.5 border-b border-border overflow-x-auto text-xs">
        {breadcrumbs.length === 0 ? (
          <span className="text-muted-foreground">/</span>
        ) : (
          <>
            <button
              onClick={() => handleBreadcrumbClick('/')}
              className="text-muted-foreground hover:text-foreground shrink-0"
            >
              /
            </button>
            {breadcrumbs.map((crumb, idx) => (
              <span key={crumb.path} className="flex items-center gap-0.5 shrink-0">
                <ChevronRight className="w-3 h-3 text-muted-foreground" />
                <button
                  onClick={() => handleBreadcrumbClick(crumb.path)}
                  className={`hover:text-foreground truncate max-w-[80px] ${
                    idx === breadcrumbs.length - 1 ? 'text-foreground font-medium' : 'text-muted-foreground'
                  }`}
                  title={crumb.path}
                >
                  {crumb.label}
                </button>
              </span>
            ))}
          </>
        )}
      </div>

      {/* Directory listing */}
      <div className="max-h-48 overflow-y-auto">
        {loading ? (
          <div className="p-3 text-center text-xs text-muted-foreground">
            {i18n('Loading...')}
          </div>
        ) : permissionDenied ? (
          <div className="p-3 space-y-2">
            <div className="flex items-start gap-2 text-xs text-amber-600 dark:text-amber-400">
              <ShieldAlert className="w-4 h-4 shrink-0 mt-0.5" />
              <div className="space-y-1">
                <p className="font-medium">{i18n('Directory access restricted by macOS privacy protection')}</p>
                <p className="text-muted-foreground">
                  {i18n('Grant "Files and Folders" or "Full Disk Access" permission to the application in System Settings, then restart it.')}
                </p>
              </div>
            </div>
            {window.easyaiDesktop?.openSystemSettings && (
              <button
                onClick={() => window.easyaiDesktop?.openSystemSettings?.()}
                className="flex items-center gap-1.5 px-2 py-1 text-xs border border-amber-500/40 text-amber-600 dark:text-amber-400 rounded hover:bg-amber-500/10 transition-colors"
              >
                <ExternalLink className="w-3 h-3" />
                {i18n('Open System Settings')}
              </button>
            )}
          </div>
        ) : directories.length === 0 ? (
          <div className="p-3 text-center text-xs text-muted-foreground">
            {i18n('No subdirectories')}
          </div>
        ) : (
          <ul className="py-1">
            {directories.map((dir) => (
              <li
                key={dir}
                onClick={() => handleClick(dir)}
                onDoubleClick={() => handleDoubleClick(dir)}
                className={`flex items-center gap-2 px-3 py-1.5 cursor-pointer text-sm transition-colors ${
                  selectedPath === dir
                    ? 'bg-accent text-accent-foreground'
                    : 'hover:bg-muted'
                }`}
              >
                <Folder className="w-4 h-4 text-yellow-500 shrink-0" />
                <span className="truncate">{getDirName(dir)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Action buttons */}
      <div className="flex gap-2 p-2 border-t border-border">
        <button
          onClick={onCancel}
          className="flex-1 px-2 py-1 text-xs border border-border rounded hover:bg-muted transition-colors"
        >
          {i18n('Cancel')}
        </button>
        <button
          onClick={handleConfirm}
          disabled={!selectedPath && !currentPath}
          className="flex-1 px-2 py-1 text-xs bg-primary text-primary-foreground rounded hover:bg-primary/90 disabled:opacity-50 transition-colors"
        >
          {i18n('Select')}
        </button>
      </div>
    </div>
  );
}
