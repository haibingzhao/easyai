import React, { useState } from 'react';
import { FileTree } from './FileTree';
import { FileViewer } from './FileViewer';
import { i18n } from '@/utils/i18n';
import { FileText, PanelLeftClose, PanelLeft, RefreshCw } from 'lucide-react';
import { useResizable } from '@/hooks/useResizable';

interface FilesSplitViewProps {
  /** Absolute path to the root directory shown in the tree. */
  rootPath: string;
  /** Project ID for API path validation; empty browses without project scoping. */
  projectId: string;
  selectedFile: string | null;
  onFileSelect: (path: string) => void;
  /** Hide mutating/project-scoped tree actions (New Folder, Reveal, Add to Chat). */
  readOnly?: boolean;
  /** Placeholder shown in the viewer when no file is selected. */
  emptyHint?: string;
  /** Placeholder shown when there is no root directory to browse. */
  noRootHint?: string;
}

const TREE_MIN = 120;
const TREE_MAX = 400;
const TREE_DEFAULT = 200;

/**
 * Reusable file tree + file viewer split view with a resizable, collapsible tree.
 * Used by the Chat right panel Files tab and the Skills page file browser.
 */
export const FilesSplitView: React.FC<FilesSplitViewProps> = ({
  rootPath,
  projectId,
  selectedFile,
  onFileSelect,
  readOnly = false,
  emptyHint = 'No files are open',
  noRootHint = 'Select a Project',
}) => {
  const [treeCollapsed, setTreeCollapsed] = useState(false);
  const [treeWidth, setTreeWidth] = useState(TREE_DEFAULT);
  const [resizing, setResizing] = useState(false);
  const [refreshToken, setRefreshToken] = useState(0);

  const treeResizer = useResizable({
    minWidth: TREE_MIN,
    maxWidth: TREE_MAX,
    onResize: (w) => setTreeWidth(Math.round(w)),
    direction: 'right',
    onResizeStart: () => setResizing(true),
    onResizeEnd: () => setResizing(false),
  });

  // Resolve selectedFile to absolute path for tree reveal
  const revealPath = selectedFile
    ? (selectedFile.startsWith('/') ? selectedFile : `${rootPath}/${selectedFile}`)
    : null;

  if (!rootPath) {
    return (
      <div className="h-full flex items-center justify-center text-muted-foreground text-sm">
        {i18n(noRootHint)}
      </div>
    );
  }

  return (
    <div className={`h-full flex ${resizing ? 'resizing' : ''}`}>
      {/* File tree (left side, collapsible + resizable) */}
      {!treeCollapsed && (
        <div className="shrink-0 border-r border-border overflow-hidden flex flex-col" style={{ width: treeWidth }}>
          <div className="flex items-center justify-between px-2 py-1 border-b border-border shrink-0">
            <span className="text-xs font-medium text-muted-foreground">{i18n('Explorer')}</span>
            <div className="flex items-center gap-0.5">
              <button
                onClick={() => setRefreshToken((n) => n + 1)}
                className="p-0.5 rounded hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
                title={i18n('Refresh')}
              >
                <RefreshCw className="w-3.5 h-3.5" />
              </button>
              <button
                onClick={() => setTreeCollapsed(true)}
                className="p-0.5 rounded hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
                title={i18n('Collapse')}
              >
                <PanelLeftClose className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
          <div className="flex-1 overflow-y-auto">
            <FileTree
              rootPath={rootPath}
              projectId={projectId}
              onFileSelect={onFileSelect}
              selectedFile={selectedFile}
              revealPath={revealPath}
              refreshToken={refreshToken}
              readOnly={readOnly}
            />
          </div>
        </div>
      )}

      {/* Drag handle between tree and viewer */}
      {!treeCollapsed && (
        <div
          className={`resize-handle ${resizing ? 'active' : ''}`}
          onMouseDown={(e) => {
            treeResizer.setCurrentWidth(treeWidth);
            treeResizer.onMouseDown(e);
          }}
          onTouchStart={(e) => {
            treeResizer.setCurrentWidth(treeWidth);
            treeResizer.onTouchStart(e);
          }}
        />
      )}

      {/* Collapse/expand toggle */}
      {treeCollapsed && (
        <button
          onClick={() => setTreeCollapsed(false)}
          className="shrink-0 w-6 border-r border-border flex flex-col items-center justify-center hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
          title={i18n('Expand')}
        >
          <PanelLeft className="w-3.5 h-3.5" />
        </button>
      )}

      {/* File viewer (right side) */}
      <div className="flex-1 overflow-hidden">
        {selectedFile ? (
          <FileViewer filePath={selectedFile} />
        ) : (
          <div className="h-full flex flex-col items-center justify-center text-muted-foreground gap-2">
            <FileText className="w-8 h-8" />
            <span className="text-sm">{i18n(emptyHint)}</span>
          </div>
        )}
      </div>
    </div>
  );
};
