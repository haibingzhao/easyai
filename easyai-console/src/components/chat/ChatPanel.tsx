import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { MessageList } from './MessageList';
import { MessageEditor } from './MessageEditor';
import { ArtifactPanel } from '../artifacts/ArtifactPanel';
import { WelcomeScreen } from './WelcomeScreen';
import { useChatStore } from '@/services/stores/chat-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { getStreamingStatus, watchSession } from '@/services/chat-service';
import { sessionService } from '@/services/session-service';
import { getCheckpoints, getFileReviewState } from '@/services/checkpoint-service';
import { Badge } from '../ui/Badge';
import { TokenContextBar } from './TokenContextBar';
import { PermissionBar } from './PermissionBar';
import { RevertBanner } from './RevertBanner';
import { FileChangesPanel } from './FileChangesPanel';
import { UserMessagePreview } from './UserMessagePreview';
import type { CheckpointInfo } from '../../types/checkpoint';
import type { ContextReferences, AssistantMessage as AssistantMessageType } from '../../types/message';
import { revertToMessage, unrevert } from '@/services/checkpoint-service';
import { Plus, PanelRight, PanelRightClose } from 'lucide-react';
import { i18n } from '../../utils/i18n';
import { RightPanel } from '../files/RightPanel';
import { TeamMemberDetail } from './team/TeamMemberDetail';
import { useNavStore } from '@/services/stores/nav-store';
import { useTeamStore } from '@/services/stores/team-store';
import { useResizable } from '@/hooks/useResizable';
import { useSessionFileChanges } from '@/hooks/useSessionFileChanges';

const BREAKPOINT = 800;

/** Threshold (px) to determine if an element is at the bottom */
const SCROLL_BOTTOM_THRESHOLD = 50;

export const ChatPanel: React.FC = () => {
  const { messages, isStreaming, isFileWriting, hasArtifacts, artifactCount, clearChat, streamingBlocks, todos, subAgentTodos, swarmRuns, isAwaitingPermission, revertState, setRevertState, sessionId, runningSessionId, setRunningSessionId, setStreaming, loadSessionMessages, loadSessionMessagesIncremental, setTodos, setAllSubAgentTodos, setFileReviewOverrides, refreshGoal, currentGoal, pendingMessageData } = useChatStore();
  const { loadAgents, loadTools, agents, selectedAgentId } = useAgentStore();
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
  const selectedMemberId = useTeamStore((s) => s.selectedMemberId);
  const resetTeam = useTeamStore((s) => s.resetTeam);
  const clearSelectedMember = useTeamStore((s) => s.clearSelectedMember);

  // Clear member detail view on session switch
  useEffect(() => {
    clearSelectedMember();
  }, [sessionId, clearSelectedMember]);

  // Use shared hook for session file changes + accept/reject handlers
  const { sessionFileChanges, handleAcceptFile, handleRejectFile, handleAcceptAll, handleRejectAll } = useSessionFileChanges();
  
  // Active user message preview — tracks the user message currently visible at the top of the scroll container
  const [activeUserMessage, setActiveUserMessage] = useState<string | null>(null);
  const activeMsgRafRef = useRef<number | null>(null);

  // Right panel resizable drag
  const rightPanelResizer = useResizable({
    minWidth: 250,
    maxWidth: 800,
    onResize: (w) => setRightPanelWidth(Math.round(w)),
    direction: 'left',
    onResizeStart: () => setPanelResizing(true),
    onResizeEnd: () => setPanelResizing(false),
  });

  // Auto-scroll related
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const autoScrollEnabledRef = useRef(true);
  const prevScrollTopRef = useRef(0);

  /** Check if the messages container is at the bottom */
  const isMessagesAtBottom = useCallback(() => {
    const el = messagesContainerRef.current;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= SCROLL_BOTTOM_THRESHOLD;
  }, []);

  /** Scroll messages to the bottom */
  const scrollMessagesToBottom = useCallback(() => {
    const el = messagesContainerRef.current;
    if (!el || !autoScrollEnabledRef.current) return;
    prevScrollTopRef.current = el.scrollTop;
    el.scrollTo({
      top: el.scrollHeight,
      behavior: 'auto'
    });
  }, []);

  /**
   * Detect the nearest user message at or above the top of the visible viewport.
   * This shows the user message that initiated the currently visible assistant response,
   * not necessarily the one scrolled into view.
   */
  const updateActiveUserMessage = useCallback(() => {
    const container = messagesContainerRef.current;
    if (!container) return;

    const containerRect = container.getBoundingClientRect();
    const viewportTop = containerRect.top;

    // Collect all user message elements inside the scroll container
    const userMsgElements = container.querySelectorAll<HTMLElement>('[data-user-message="true"]');
    if (userMsgElements.length === 0) {
      setActiveUserMessage((prev) => (prev === null ? prev : null));
      return;
    }

    // Find the last user message whose top is at or above the viewport top
    let foundText: string | null = null;
    for (const el of userMsgElements) {
      const elRect = el.getBoundingClientRect();
      if (elRect.top <= viewportTop) {
        const contentDiv = el.querySelector('.whitespace-pre-wrap');
        const text = contentDiv?.textContent?.trim() ?? '';
        if (text) foundText = text;
      } else {
        // Elements are in DOM order; once we pass the viewport top, stop
        break;
      }
    }

    // Fallback: if scrolled to the very top and no message is above viewport,
    // use the first user message
    if (!foundText) {
      const first = userMsgElements[0];
      const contentDiv = first.querySelector('.whitespace-pre-wrap');
      foundText = contentDiv?.textContent?.trim() ?? null;
    }

    setActiveUserMessage((prev) => (prev === foundText ? prev : foundText));
  }, []);

  /**
   * Handle messages container scroll event:
   * - Non-streaming: toggle auto-scroll based on scroll position
   * - Streaming: detect user scroll-up via scrollTop decrease (wheel event handled separately below)
   */
  const handleMessagesScroll = useCallback(() => {
    const el = messagesContainerRef.current;
    if (!el) return;

    const currentScrollTop = el.scrollTop;
    const prevScrollTop = prevScrollTopRef.current;
    prevScrollTopRef.current = currentScrollTop;

    if (isStreaming) {
      // scrollTop decrease = user scrolled up (programmatic scrollTo only increases scrollTop)
      if (currentScrollTop < prevScrollTop - 5) {
        autoScrollEnabledRef.current = false;
      } else if (isMessagesAtBottom() && !autoScrollEnabledRef.current) {
        // Re-enable auto-scroll when user manually scrolls back to bottom
        autoScrollEnabledRef.current = true;
      }
      return;
    }

    autoScrollEnabledRef.current = isMessagesAtBottom();
    // Also update toolbar preview on scroll
    updateActiveUserMessage();
  }, [isStreaming, isMessagesAtBottom, updateActiveUserMessage]);

  // Wheel event: immediately disable auto-scroll when user scrolls up during streaming (fires before scroll event, most reliable)
  useEffect(() => {
    const el = messagesContainerRef.current;
    if (!el) return;
    const handleWheel = (e: WheelEvent) => {
      if (isStreaming && e.deltaY < 0) {
        autoScrollEnabledRef.current = false;
      }
    };
    el.addEventListener('wheel', handleWheel, { passive: true });
    return () => el.removeEventListener('wheel', handleWheel);
  }, [isStreaming]);

  // Auto-scroll to bottom during streaming
  useEffect(() => {
    if (isStreaming && autoScrollEnabledRef.current) {
      requestAnimationFrame(() => {
        scrollMessagesToBottom();
      });
    }
  }, [messages, streamingBlocks, isStreaming, scrollMessagesToBottom]);

  // Update toolbar preview when messages change (session load, new message, etc.)
  useEffect(() => {
    // Use rAF to ensure DOM has rendered before detecting
    if (activeMsgRafRef.current) cancelAnimationFrame(activeMsgRafRef.current);
    activeMsgRafRef.current = requestAnimationFrame(() => {
      updateActiveUserMessage();
    });
    return () => {
      if (activeMsgRafRef.current) cancelAnimationFrame(activeMsgRafRef.current);
    };
  }, [messages, updateActiveUserMessage]);

  // When streaming ends, re-evaluate whether to enable auto-scroll based on current position
  useEffect(() => {
    if (!isStreaming) {
      autoScrollEnabledRef.current = isMessagesAtBottom();
    }
  }, [isStreaming, isMessagesAtBottom]);

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

  useEffect(() => {
    loadAgents();
    loadTools();
  }, [loadAgents, loadTools]);

  // Restore goal state when switching sessions
  useEffect(() => {
    if (sessionId) {
      refreshGoal(sessionId).catch(() => {
        // Silently ignore — no goal for this session
      });
    }
  }, [sessionId, refreshGoal]);

  // SSE-first with polling fallback when a running session is detected
  useEffect(() => {
    if (!runningSessionId) return;

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let watchHandle: { abort: () => void } | null = null;

    /**
     * Fetch incremental messages from the server.
     * Tries incremental fetch first; falls back to full reload if compaction,
     * in-place message updates, or errors are detected.
     * @param finalize If true, clears running state after recovery.
     * @returns true if a pending permission request is active (caller should keep polling).
     */
    const fetchMessagesIncremental = async (finalize: boolean): Promise<boolean> => {
      const lastTimestamp = useChatStore.getState()._lastSnapshots.reduce(
        (max, s) => Math.max(max, s.timestamp), 0
      );
      const [checkpoints, groupedTodos, reviewState] = await Promise.all([
        getCheckpoints(runningSessionId).catch(() => [] as CheckpointInfo[]),
        sessionService.getGroupedTodos(runningSessionId).catch(() => ({ main: [], subAgents: [] })),
        getFileReviewState(runningSessionId).catch(() => null),
      ]);
      if (cancelled) return false;

      let hasPendingPermission = false;
      try {
        const afterResponse = await sessionService.getSessionMessagesAfter(runningSessionId, lastTimestamp);
        if (cancelled) return false;

        hasPendingPermission = !!afterResponse.pendingPermission;

        if (
          afterResponse.compactionOccurredAfter ||
          afterResponse.contentUpdatedAt > lastTimestamp
        ) {
          // Compaction or in-place message update detected — fall back to full reload
          const detail = await sessionService.getSessionDetail(runningSessionId);
          if (cancelled) return false;
          hasPendingPermission = !!detail?.pendingPermission;
          if (detail) {
            loadSessionMessages(detail.messages, detail.pendingPermission, checkpoints, detail.endReason);
          }
        } else {
          // Safe to use incremental merge — pass pendingPermission from backend
          loadSessionMessagesIncremental(afterResponse.messages, afterResponse.pendingPermission ?? null, checkpoints, afterResponse.endReason);
        }
      } catch {
        // Incremental fetch failed — fall back to full reload
        const detail = await sessionService.getSessionDetail(runningSessionId);
        if (cancelled) return false;
        hasPendingPermission = !!detail?.pendingPermission;
        if (detail) {
          loadSessionMessages(detail.messages, detail.pendingPermission, checkpoints, detail.endReason);
        }
      }

      setTodos(groupedTodos.main);
      setAllSubAgentTodos(
        Object.fromEntries(groupedTodos.subAgents.map((g) => [g.agentName, { todos: g.todos, toolCallId: g.agentName }]))
      );
      if (reviewState?.reviews) {
        setFileReviewOverrides(reviewState.reviews);
      }
      if (finalize && !hasPendingPermission) {
        // Only finalize when there is no pending permission.
        // When the SSE stream ended but the agent is paused for a permission request,
        // we must keep polling so the PermissionBar stays interactive.
        setRunningSessionId(null);
        setStreaming(false);
      }
      return hasPendingPermission;
    };

    const poll = async () => {
      try {
        const status = await getStreamingStatus(runningSessionId);
        if (cancelled) return;

        if (!status.streaming && !status.local) {
          // Session is completely done — final fetch and stop polling
          await fetchMessagesIncremental(true);
          return;
        }

        if (status.local && status.streaming) {
          // Still running — fetch incremental messages and poll again in 3 seconds
          await fetchMessagesIncremental(false);
          if (!cancelled) timer = setTimeout(poll, 3000);
          return;
        }

        // e.g. local but not streaming (permission pause) —
        // fetch final messages, keep polling only if there's a pending permission
        const hasPendingPermission = await fetchMessagesIncremental(true);
        if (hasPendingPermission && !cancelled) {
          // Agent is paused for permission — keep polling to detect state changes
          timer = setTimeout(poll, 3000);
        }
      } catch (e) {
        console.warn('[SSE Recovery] Poll failed:', e);
        if (!cancelled) timer = setTimeout(poll, 5000);
      }
    };

    /**
     * Full reconciliation: load authoritative messages from DB.
     * Called once when the SSE watch stream ends normally (done event).
     */
    const finalReconciliation = async () => {
      try {
        const [detail, checkpoints, groupedTodos] = await Promise.all([
          sessionService.getSessionDetail(runningSessionId),
          getCheckpoints(runningSessionId).catch(() => [] as CheckpointInfo[]),
          sessionService.getGroupedTodos(runningSessionId).catch(() => ({ main: [], subAgents: [] })),
        ]);
        if (cancelled) return;
        if (detail) {
          loadSessionMessages(detail.messages, detail.pendingPermission, checkpoints, detail.endReason);
        }
        setTodos(groupedTodos.main);
        setAllSubAgentTodos(
          Object.fromEntries(groupedTodos.subAgents.map((g) => [g.agentName, { todos: g.todos, toolCallId: g.agentName }]))
        );
      } catch { /* best-effort */ }
      if (!cancelled) {
        setRunningSessionId(null);
        setStreaming(false);
      }
    };

    // SSE-first: attach to the running session's event broadcast
    watchHandle = watchSession(runningSessionId, {
      onEvent: (event) => {
        useChatStore.getState().handleEvent(event);
      },
      onDone: (event) => {
        if (cancelled) return;
        if (event.reason === 'not_streaming') {
          // Session not active on this server — fall back to polling
          poll();
        } else {
          // Normal completion — full reconciliation
          finalReconciliation();
        }
      },
      onError: () => {
        if (cancelled) return;
        // SSE connection failed — fall back to polling
        poll();
      },
    });

    return () => {
      cancelled = true;
      watchHandle?.abort();
      if (timer) clearTimeout(timer);
    };
  }, [runningSessionId, loadSessionMessages, loadSessionMessagesIncremental, setRunningSessionId, setStreaming]);

  // Auto-scroll to bottom when entering a running session
  useEffect(() => {
    if (runningSessionId) {
      const el = messagesContainerRef.current;
      if (el) {
        el.scrollTo({ top: el.scrollHeight, behavior: 'auto' });
      }
    }
  }, [runningSessionId]);

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
          .filter((cp) => cp.snapshotHash && cp.messageId);
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

  // Aggregate references from all assistant messages in the current conversation,
  // plus any pending references from message_end events not yet committed.
  const aggregatedReferences = useMemo<ContextReferences>(() => {
    const memoryMap = new Map<string, ContextReferences['memories'][0]>();
    const ruleMap = new Map<string, ContextReferences['rules'][0]>();

    const addRefs = (refs: ContextReferences) => {
      for (const mem of refs.memories) {
        const key = `${mem.scope}:${mem.name}`;
        if (!memoryMap.has(key)) memoryMap.set(key, mem);
      }
      for (const rule of refs.rules) {
        const key = `${rule.source}:${rule.name}`;
        if (!ruleMap.has(key)) ruleMap.set(key, rule);
      }
    };

    // From committed messages
    for (const msg of messages) {
      if (msg.role === 'assistant') {
        const refs = (msg as AssistantMessageType).references;
        if (refs) addRefs(refs);
      }
    }

    // From pendingMessageData (references received via message_end but not yet committed)
    for (const data of Object.values(pendingMessageData)) {
      if (data.references) addRefs(data.references);
    }

    return {
      memories: Array.from(memoryMap.values()),
      rules: Array.from(ruleMap.values()),
    };
  }, [messages, pendingMessageData]);

  const handleNewSession = () => {
    clearChat();
    resetTeam();
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
          {selectedMemberId ? (
            <TeamMemberDetail />
          ) : (
          <div
            ref={messagesContainerRef}
            className="flex-1 overflow-y-auto"
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
              </div>
            )}
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

          {/* Session-level file changes panel - above input area */}
          {sessionFileChanges.length > 0 && (
            <div className="px-4">
              <FileChangesPanel
                state={isStreaming && isFileWriting ? 'generating' : 'applied'}
                files={sessionFileChanges}
                sessionId={sessionId || undefined}
                onAcceptFile={handleAcceptFile}
                onRejectFile={handleRejectFile}
                onAcceptAll={handleAcceptAll}
                onRejectAll={handleRejectAll}
              />
            </div>
          )}

          {/* Input area */}
          <div className="border-t border-border bg-background px-4 pb-4 pt-2">
            <MessageEditor />
          </div>
        </div>

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
            <RightPanel onClose={() => setRightPanelOpen(false)} references={aggregatedReferences} mainTodos={todos} subAgentTodos={subAgentTodos} swarmRuns={swarmRuns} goal={currentGoal} isTeamAgent={isTeamAgent} />
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
    </div>
  );
};
