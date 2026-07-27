import React, { useState } from 'react';
import { FileTree } from './FileTree';
import { FileViewer } from './FileViewer';
import { ReviewTab } from './ReviewTab';
import { SessionsTab } from './SessionsTab';
import { ReferencePanel } from '../chat/ReferencePanel';
import { TodoPanel } from '../chat/TodoPanel';
import { GoalCard } from '../chat/GoalCard';
import { SwarmRunCard } from '../chat/SwarmRunCard';
import { TeamMemberPanel } from '../chat/team/TeamMemberPanel';
import { useProjectStore } from '@/services/stores/project-store';
import { useNavStore, type RightPanelTab } from '@/services/stores/nav-store';
import { i18n } from '@/utils/i18n';
import { FileText, X, PanelLeftClose, PanelLeft, RefreshCw } from 'lucide-react';
import { useResizable } from '@/hooks/useResizable';
import type { ContextReferences } from '@/types/message';
import type { TodoInfo, SubAgentTodoGroup } from '@/types/todo';
import type { GoalStatusEvent } from '@/types/socket-event';
import type { SwarmRunTracking } from '@/services/stores/chat-store';

interface RightPanelProps {
  onClose: () => void;
  references?: ContextReferences;
  /** Session-scoped variables for the Summary tab References panel */
  sessionVariables?: Record<string, string>;
  mainTodos?: TodoInfo[];
  subAgentTodos?: Record<string, SubAgentTodoGroup>;
  swarmRuns?: Record<string, SwarmRunTracking>;
  goal?: GoalStatusEvent | null;
  /** Whether the current chat agent is a TEAM agent (shows the Team tab) */
  isTeamAgent?: boolean;
}

const BASE_TABS: { id: RightPanelTab; labelKey: string }[] = [
  { id: 'summary', labelKey: 'Summary' },
  { id: 'review', labelKey: 'Review' },
  { id: 'files', labelKey: 'Files' },
  { id: 'sessions', labelKey: 'History' },
];

const TEAM_TAB: { id: RightPanelTab; labelKey: string } = { id: 'team', labelKey: 'Team' };

/**
 * Right-side panel with Summary, Review, Files, and Sessions tabs.
 * Tab switching is driven by nav-store actions (openFile, openReviewTab, openSessionsTab).
 */
export const RightPanel: React.FC<RightPanelProps> = ({ onClose, references, sessionVariables, mainTodos, subAgentTodos, swarmRuns, goal, isTeamAgent = false }) => {
  const activeTab = useNavStore((s) => s.rightPanelTab);
  const setActiveTab = useNavStore((s) => s.setRightPanelTab);
  const selectedFile = useNavStore((s) => s.selectedFile);
  const setSelectedFile = useNavStore((s) => s.setSelectedFile);
  const currentProject = useProjectStore((s) => s.currentProject);

  const rootPath = currentProject?.path || '';
  const projectId = currentProject?.id || '';

  // Team tab only visible for TEAM agents; fall back to summary if stale
  const tabs = isTeamAgent ? [TEAM_TAB, ...BASE_TABS] : BASE_TABS;
  const effectiveTab = activeTab === 'team' && !isTeamAgent ? 'summary' : activeTab;

  return (
    <div className="h-full flex flex-col bg-background border-l border-border">
      {/* Tab bar */}
      <div className="flex items-center justify-between border-b border-border shrink-0">
        <div className="flex">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`px-3 py-2 text-xs transition-colors border-b-2 ${
                effectiveTab === tab.id
                  ? 'border-primary font-medium text-foreground'
                  : 'border-transparent text-muted-foreground hover:text-foreground'
              }`}
            >
              {i18n(tab.labelKey)}
            </button>
          ))}
        </div>
        <button
          onClick={onClose}
          className="p-1.5 mr-1 rounded-md hover:bg-muted transition-colors text-muted-foreground"
          title={i18n('Close Panel')}
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-hidden">
        {effectiveTab === 'team' && isTeamAgent && (
          <TeamMemberPanel />
        )}
        {effectiveTab === 'summary' && (
          <SummaryTab references={references} sessionVariables={sessionVariables} mainTodos={mainTodos} subAgentTodos={subAgentTodos} swarmRuns={swarmRuns} goal={goal} />
        )}
        {effectiveTab === 'review' && (
          <ReviewTab />
        )}
        {effectiveTab === 'files' && (
          <FilesTab
            rootPath={rootPath}
            projectId={projectId}
            selectedFile={selectedFile}
            onFileSelect={setSelectedFile}
          />
        )}
        {effectiveTab === 'sessions' && (
          <SessionsTab />
        )}
      </div>
    </div>
  );
};

/** Summary tab — shows Goal + Swarm + Progress + References */
const SummaryTab: React.FC<{
  references?: ContextReferences;
  sessionVariables?: Record<string, string>;
  mainTodos?: TodoInfo[];
  subAgentTodos?: Record<string, SubAgentTodoGroup>;
  swarmRuns?: Record<string, SwarmRunTracking>;
  goal?: GoalStatusEvent | null;
}> = ({ references, sessionVariables, mainTodos, subAgentTodos, swarmRuns, goal }) => {
  const emptyRefs: ContextReferences = { memories: [], rules: [] };
  const hasSwarms = swarmRuns && Object.keys(swarmRuns).length > 0;
  return (
    <div className="p-2 space-y-0">
      {goal && (
        <>
          <GoalCard goal={goal} />
          <div className="border-t border-dashed border-border my-2" />
        </>
      )}
      {hasSwarms && (
        <>
          <SwarmRunCard runs={swarmRuns} />
          <div className="border-t border-dashed border-border my-2" />
        </>
      )}
      <TodoPanel mainTodos={mainTodos || []} subAgentTodos={subAgentTodos || {}} swarmRuns={swarmRuns} />
      <div className="border-t border-dashed border-border my-2" />
      <ReferencePanel references={references || emptyRefs} sessionVariables={sessionVariables} />
    </div>
  );
};

interface FilesTabProps {
  rootPath: string;
  projectId: string;
  selectedFile: string | null;
  onFileSelect: (path: string) => void;
}

const TREE_MIN = 120;
const TREE_MAX = 400;
const TREE_DEFAULT = 200;

/** Files tab — file tree + file viewer split view with resizable divider */
const FilesTab: React.FC<FilesTabProps> = ({ rootPath, projectId, selectedFile, onFileSelect }) => {
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
        {i18n('Select a Project')}
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
            <span className="text-sm">{i18n('No files are open')}</span>
          </div>
        )}
      </div>
    </div>
  );
};
