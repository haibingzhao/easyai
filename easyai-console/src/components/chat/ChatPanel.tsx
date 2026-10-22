import React, { useState, useEffect, useRef, useMemo } from 'react';
import { MessageList } from './MessageList';
import { MessageEditor } from './MessageEditor';
import { QuestionNavigator } from './QuestionNavigator';
import { ArtifactPanel } from '../artifacts/ArtifactPanel';
import { WelcomeScreen } from './WelcomeScreen';
import { useChatStore } from '@/services/stores/chat-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { Badge } from '../ui/Badge';
import { TokenContextBar } from './TokenContextBar';
import { PermissionBar } from './PermissionBar';
import { RevertBanner } from './RevertBanner';
import { UserMessagePreview } from './UserMessagePreview';
import { Plus, PanelRight, PanelRightClose } from 'lucide-react';
import { revertToMessage, unrevert } from '@/services/checkpoint-service';
import { i18n } from '../../utils/i18n';
import { RightPanel } from '../files/RightPanel';
import { TeamMemberDetail } from './team/TeamMemberDetail';
import { SelectionToolbar } from './SelectionToolbar';
import { SideAskPanel } from './SideAskPanel';
import { useNavStore } from '@/services/stores/nav-store';
import { useSideAskStore } from '@/services/stores/side-ask-store';
import { useTeamStore } from '@/services/stores/team-store';
import { useResizable } from '@/hooks/useResizable';
import { useMessageAutoScroll } from '@/hooks/useMessageAutoScroll';
import { useSessionRecovery } from '@/hooks/useSessionRecovery';
import { useAggregatedReferences } from '@/hooks/useAggregatedReferences';
import { useChatBootstrap } from '@/hooks/useChatBootstrap';

const BREAKPOINT = 800;

export const ChatPanel: React.FC = () => {
  const { messages, isStreaming, hasArtifacts, artifactCount, clearChat, todos, subAgentTodos, swarmRuns, isAwaitingPermission, revertState, setRevertState, sessionId, runningSessionId, liveStreamDropped, currentGoal, sessionVariables } = useChatStore();
  const { agents, selectedAgentId } = useAgentStore();
  const [showArtifactPanel, setShowArtifactPanel] = useState(false);
  const [windowWidth, setWindowWidth] = useState(window.innerWidth);
  const { rightPanelOpen, toggleRightPanel, setRightPanelOpen, rightPanelWidth, setRightPanelWidth } = useNavStore();
  const [panelResizing, setPanelResizing] = useState(false);
  const contentRef = useRef<HTMLDivElement>(null);

  // Team agent detection + member detail selection
  const isTeamAgent = useMemo(
    () => agents.find((a) => a.id === selectedAgentId)?.agentType === 'TEAM',
    [agents, selectedAgentId]
  );
  const selectedExecutionId = useTeamStore((s) => s.selectedExecutionId);
  const resetTeam = useTeamStore((s) => s.resetTeam);
  const clearSelectedMember = useTeamStore((s) => s.clearSelectedMember);

  // Clear member detail view on session switch
  useEffect(() => {
    clearSelectedMember();
  }, [sessionId, clearSelectedMember]);

  const rightPanelResizer = useResizable({
    minWidth: 250,
    maxWidth: 800,
    onResize: (w) => setRightPanelWidth(Math.round(w)),
    direction: 'left',
    onResizeStart: () => setPanelResizing(true),
    onResizeEnd: () => setPanelResizing(false),
  });

  // Side ask panel (ephemeral Q&A about selected text)
  const sideAskOpen = useSideAskStore((s) => s.open);
  const abortSideAsk = useSideAskStore((s) => s.abortAndClose);
  const [sideAskWidth, setSideAskWidth] = useState(380);
  const [sideAskResizing, setSideAskResizing] = useState(false);
  const sideAskResizer = useResizable({
    minWidth: 280,
    maxWidth: 640,
    onResize: (w) => setSideAskWidth(Math.round(w)),
    direction: 'left',
    onResizeStart: () => setSideAskResizing(true),
    onResizeEnd: () => setSideAskResizing(false),
  });

  // Side ask owns the right side exclusively: close the other panels when it opens
  useEffect(() => {
    if (sideAskOpen) {
      setShowArtifactPanel(false);
      setRightPanelOpen(false);
    }
  }, [sideAskOpen, setRightPanelOpen]);

  // Opening the right panel while side ask is open dismisses the ephemeral conversation
  useEffect(() => {
    if (rightPanelOpen && useSideAskStore.getState().open) {
      abortSideAsk();
    }
  }, [rightPanelOpen, abortSideAsk]);

  // Auto-scroll related (shared with the mobile shell)
  const {
    containerRef: messagesContainerRef,
    onScroll: handleMessagesScroll,
    activeUserMessage,
  } = useMessageAutoScroll();

  useEffect(() => {
    let timeoutId: ReturnType<typeof setTimeout>;
    const handleResize = () => {
      clearTimeout(timeoutId);
      timeoutId = setTimeout(() => setWindowWidth(window.innerWidth), 100);
    };
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('resize', handleResize);
      clearTimeout(timeoutId);
    };
  }, []);

  // Agents / tools / snapshot capability / goal, then running-session recovery and the
  // transcript auto-follow. All shared with the mobile shell.
  useChatBootstrap();
  useSessionRecovery(runningSessionId);

  // Undo/Redo keyboard shortcuts (Cmd+Z / Cmd+Shift+Z)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const isMac = navigator.platform.toUpperCase().indexOf('MAC') >= 0;
      const modKey = isMac ? e.metaKey : e.ctrlKey;
      if (!modKey || e.key !== 'z') return;

      // Don't intercept when typing in input/textarea
      const target = e.target as HTMLElement;
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return;

      const chatState = useChatStore.getState();
      if (chatState.isStreaming || !chatState.sessionId) return;

      if (e.shiftKey) {
        // Cmd+Shift+Z → unrevert
        if (chatState.revertState) {
          e.preventDefault();
          unrevert(chatState.sessionId)
            .then(() => chatState.setRevertState(null))
            .catch(console.error);
        }
      } else {
        // Cmd+Z → revert to last checkpoint (only agent-end checkpoints with snapshotHash can be reverted)
        const checkpoints = Object.values(chatState.checkpointsByMessageId)
          .filter((cp) => cp.snapshotHash && cp.messageId && !cp.messageId.startsWith('member:') && !cp.messageId.startsWith('staged:'));
        if (checkpoints.length > 0 && !chatState.revertState) {
          e.preventDefault();
          const lastCheckpoint = checkpoints[checkpoints.length - 1];
          revertToMessage(chatState.sessionId, lastCheckpoint.messageId!)
            .then((result) => {
              chatState.setRevertState({
                messageId: result.messageId,
                additions: result.additions,
                deletions: result.deletions,
                filesCount: result.filesCount,
                timestamp: Date.now(),
              });
            })
            .catch(console.error);
        }
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);


  const isMobile = windowWidth < BREAKPOINT;
  const showRightPanel = !isMobile && rightPanelOpen;
  const showWelcome = messages.length === 0 && !isStreaming;
  const awaitingPermission = isAwaitingPermission();
  const isLocked = isStreaming;

  // References the conversation pulled into context (shared with the mobile tasks sheet)
  const aggregatedReferences = useAggregatedReferences();

  const handleNewSession = () => {
    clearChat();
    resetTeam();
    useAgentStore.getState().resetAgentSelection();
    useNavStore.getState().setSelectedFile(null);
  };

  return (
    <div className="relative w-full h-full overflow-hidden flex flex-col bg-background">
      {/* Main content — overflow-x-auto enables horizontal scrollbar when min-widths exceed viewport */}
      <div ref={contentRef} className={`flex-1 flex overflow-x-auto overflow-y-hidden ${panelResizing ? 'resizing' : ''}`}>
        {/* Chat area */}
        <div
          className="h-full flex flex-col min-w-[300px]"
          style={{ flex: 1 }}
        >
          {/* Top toolbar — constrained to chat area width */}
          <div className="flex items-center justify-end px-3 py-1 border-b border-border bg-background gap-2 shrink-0">
            <UserMessagePreview content={activeUserMessage} />
            <div className="flex items-center gap-1.5">
              <button
                onClick={handleNewSession}
                className="p-1.5 rounded-md hover:bg-muted transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                title={isLocked ? i18n('Outputting, please wait...') : i18n('New Session')}
                disabled={isLocked}
              >
                <Plus className="w-4 h-4" />
              </button>
              <button
                onClick={() => {
                  toggleRightPanel();
                  // Close artifact panel when opening right panel
                  if (!rightPanelOpen && showArtifactPanel) {
                    setShowArtifactPanel(false);
                  }
                }}
                className="p-1.5 rounded-md hover:bg-muted transition-colors"
                title={rightPanelOpen ? i18n('Close Panel') : i18n('Open Panel')}
              >
                {rightPanelOpen
                  ? <PanelRightClose className="w-4 h-4" />
                  : <PanelRight className="w-4 h-4" />
                }
              </button>
            </div>
          </div>

          {/* Message list — replaced by TeamMemberDetail when a team member is selected */}
          {selectedExecutionId ? (
            <TeamMemberDetail />
          ) : (
          <div className="relative flex-1 min-h-0">
            <div
              ref={messagesContainerRef}
              className="h-full overflow-y-auto"
              onScroll={handleMessagesScroll}
            >
              {/* Sticky header: TokenContextBar — aligned with message list */}
              <div className="sticky top-0 z-10 bg-background border-b border-border max-h-[50vh] overflow-y-auto">
                <TokenContextBar />
              </div>

              {showWelcome ? (
                <WelcomeScreen />
              ) : (
                <MessageList messages={messages} isStreaming={isStreaming} />
              )}
              {/* Running session indicator */}
              {runningSessionId && (
                <div className="px-4 py-2 flex items-center gap-2 text-sm text-muted-foreground">
                  <div className="w-2 h-2 rounded-full bg-primary animate-pulse" />
                  <span>{i18n('Session is running...')}</span>
                  {liveStreamDropped && (
                    <span className="text-xs">{i18n('Real-time stream dropped; updates arrive by polling')}</span>
                  )}
                </div>
              )}
            </div>
            {!isMobile && <QuestionNavigator messages={messages} containerRef={messagesContainerRef} />}
          </div>
          )}

          {/* Permission bar - shown when tool execution requires user approval */}
          {awaitingPermission && <PermissionBar />}

          {/* Revert banner - shown when files have been reverted */}
          {revertState && sessionId && (
            <RevertBanner
              timestamp={revertState.timestamp}
              onUnrevert={() => {
                if (!sessionId) return;
                unrevert(sessionId)
                  .then(() => setRevertState(null))
                  .catch(console.error);
              }}
              onContinue={() => setRevertState(null)}
            />
          )}

          {/* Input area */}
          <div className="border-t border-border bg-background px-4 pb-4 pt-2">
            <MessageEditor />
          </div>
        </div>

        {/* Drag handle between chat and side ask panel */}
        {sideAskOpen && (
          <div
            className={`resize-handle ${sideAskResizing ? 'active' : ''}`}
            onMouseDown={(e) => {
              sideAskResizer.setCurrentWidth(sideAskWidth);
              sideAskResizer.onMouseDown(e);
            }}
            onTouchStart={(e) => {
              sideAskResizer.setCurrentWidth(sideAskWidth);
              sideAskResizer.onTouchStart(e);
            }}
          />
        )}

        {/* Side ask panel — ephemeral Q&A about selected chat text */}
        {sideAskOpen && (
          <div className="h-full shrink-0 border-l border-border" style={{ width: sideAskWidth }}>
            <SideAskPanel />
          </div>
        )}

        {/* Drag handle between chat and right panel */}
        {showRightPanel && (
          <div
            className={`resize-handle ${panelResizing ? 'active' : ''}`}
            onMouseDown={(e) => {
              rightPanelResizer.setCurrentWidth(rightPanelWidth);
              rightPanelResizer.onMouseDown(e);
            }}
            onTouchStart={(e) => {
              rightPanelResizer.setCurrentWidth(rightPanelWidth);
              rightPanelResizer.onTouchStart(e);
            }}
          />
        )}

        {/* Right panel (Files/Summary/Review/Sessions) */}
        {showRightPanel && (
          <div className="h-full right-panel shrink-0" style={{ width: rightPanelWidth }}>
            <RightPanel onClose={() => setRightPanelOpen(false)} references={aggregatedReferences} sessionVariables={sessionVariables} mainTodos={todos} subAgentTodos={subAgentTodos} swarmRuns={swarmRuns} goal={currentGoal} isTeamAgent={isTeamAgent} />
          </div>
        )}

        {/* Artifacts area */}
        {(!isMobile || showArtifactPanel) && (
          <div
            className="h-full"
            style={{
              display: !isMobile && (!hasArtifacts || !showArtifactPanel || showRightPanel) ? 'none' : undefined,
              width: isMobile ? undefined : '50%',
            }}
          >
            <ArtifactPanel
              collapsed={!showArtifactPanel}
              overlay={isMobile}
              onClose={() => setShowArtifactPanel(false)}
            />
          </div>
        )}
      </div>

      {/* Floating pill for artifacts */}
      {hasArtifacts && !showArtifactPanel && !showRightPanel && (
        <button
          className="absolute z-30 top-4 left-1/2 -translate-x-1/2"
          onClick={() => setShowArtifactPanel(true)}
          title={i18n('Show artifacts')}
        >
          <Badge>
            <span className="inline-flex items-center gap-1">
              <span>{i18n('Artifacts')}</span>
              <span className="text-[10px] leading-none bg-primary-foreground/20 text-primary-foreground rounded px-1 font-mono tabular-nums">
                {artifactCount}
              </span>
            </span>
          </Badge>
        </button>
      )}

      {/* Floating toolbar for text selections inside the message list */}
      <SelectionToolbar containerRef={messagesContainerRef} />
    </div>
  );
};
