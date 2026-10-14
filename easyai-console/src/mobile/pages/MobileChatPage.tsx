import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowDown, Boxes, ChevronDown, Clock, Layers, Plus } from 'lucide-react';
import { MessageList } from '@/components/chat/MessageList';
import { MessageEditor } from '@/components/chat/MessageEditor';
import { WelcomeScreen } from '@/components/chat/WelcomeScreen';
import { TokenContextBar } from '@/components/chat/TokenContextBar';
import { PermissionBar } from '@/components/chat/PermissionBar';
import { RevertBanner } from '@/components/chat/RevertBanner';
import { TeamMemberDetail } from '@/components/chat/team/TeamMemberDetail';
import { useChatStore } from '@/services/stores/chat-store';
import { useTeamStore } from '@/services/stores/team-store';
import { useNavStore } from '@/services/stores/nav-store';
import { useDisplaySessions } from '@/hooks/useDisplaySessions';
import { useChatBootstrap } from '@/hooks/useChatBootstrap';
import { useMessageAutoScroll } from '@/hooks/useMessageAutoScroll';
import { useSessionRecovery } from '@/hooks/useSessionRecovery';
import { switchToSession } from '@/services/session-switch';
import { unrevert } from '@/services/checkpoint-service';
import { i18n } from '@/utils/i18n';
import { ConnectionChip } from '@/plugins/connection/ConnectionChip';
import { MobileHeader } from '../components/MobileHeader';
import { MobileQuestionRail } from '../components/MobileQuestionRail';
import { MobileSheet } from '../components/MobileSheet';
import { TasksSheet } from '../components/TasksSheet';
import { MessageActionSheet } from '../components/MessageActionSheet';
import { useKeyboardInset } from '../hooks/useKeyboardInset';
import { useVisibilityReconnect } from '../hooks/useVisibilityReconnect';
import { useLongPress } from '../hooks/useLongPress';
import { useMobileUiStore } from '../stores/mobile-ui-store';

/**
 * The conversation tab.
 *
 * Reuses the desktop transcript (`MessageList` and everything under `segments/`) and the desktop
 * composer (`MessageEditor` in its mobile variant), so streaming, process folding, tool rendering,
 * queueing and dictation behave identically here. What differs is only the frame: one 44pt header
 * instead of two bars, the side panels replaced by sheets, and a recovery poll that is kicked the
 * moment the app returns to the foreground — iOS suspends the stream when it does not.
 */
export const MobileChatPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    messages,
    isStreaming,
    hasArtifacts,
    artifactCount,
    clearChat,
    sessionId,
    runningSessionId,
    isAwaitingPermission,
    revertState,
    setRevertState,
    liveStreamDropped,
  } = useChatStore();
  const resetTeam = useTeamStore((s) => s.resetTeam);
  const selectedExecutionId = useTeamStore((s) => s.selectedExecutionId);
  const clearSelectedMember = useTeamStore((s) => s.clearSelectedMember);

  useChatBootstrap();
  const { pollNow } = useSessionRecovery(runningSessionId);
  useVisibilityReconnect(pollNow);

  const { containerRef, onScroll, scrollToBottom, followEnabled, resumeFollow, activeUserMessage } =
    useMessageAutoScroll();

  const keyboardInset = useKeyboardInset();
  const openTasksSheet = useMobileUiStore((s) => s.openTasksSheet);
  const setActionSheetTarget = useMobileUiStore((s) => s.setActionSheetTarget);
  const [switcherOpen, setSwitcherOpen] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  const displaySessions = useDisplaySessions();
  const currentTitle = displaySessions.find((s) => s.id === sessionId)?.title ?? null;

  // Member transcript takes over the message area, same as on desktop
  useEffect(() => {
    clearSelectedMember();
  }, [sessionId, clearSelectedMember]);

  useEffect(() => {
    if (!runningSessionId) {
      setElapsedSeconds(0);
      return;
    }
    const started = Date.now();
    const timer = setInterval(() => setElapsedSeconds(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => clearInterval(timer);
  }, [runningSessionId]);

  // The keyboard pushing up the composer must not leave the newest message below the fold — but a
  // reader parked above it keeps their place, so this respects the follow flag like any other scroll.
  useEffect(() => {
    if (keyboardInset > 0) scrollToBottom();
  }, [keyboardInset, scrollToBottom]);

  const handleLongPress = useCallback(
    (element: HTMLElement) => {
      // Long-pressing a tool row opens its full-screen detail; a tap on the same row still belongs
      // to the renderer, so expanding a step and drilling into it stay separate gestures.
      const toolRow = element.closest<HTMLElement>('[data-tool-call-id]');
      if (toolRow?.dataset.toolCallId) {
        void navigate(`/tool/${encodeURIComponent(toolRow.dataset.toolCallId)}`);
        return;
      }
      const row = element.closest<HTMLElement>('[data-msg-index]');
      if (!row) return;
      const content = row.querySelector<HTMLElement>('.whitespace-pre-wrap');
      const text = content?.getAttribute('data-message-content') ?? content?.textContent?.trim() ?? '';
      if (text) setActionSheetTarget({ text });
    },
    [setActionSheetTarget, navigate]
  );
  useLongPress(containerRef, handleLongPress);

  const startNewSession = () => {
    if (isStreaming) return;
    clearChat();
    resetTeam();
    useNavStore.getState().setSelectedFile(null);
  };

  const awaitingPermission = isAwaitingPermission();
  const showWelcome = messages.length === 0 && !isStreaming;

  return (
    <div className="flex flex-col flex-1 min-h-0">
      <MobileHeader
        left={
          <button className="mobile-hit p-1.5 -ml-1.5" onClick={openTasksSheet} aria-label={i18n('Tasks')}>
            <Layers className="w-5 h-5" />
          </button>
        }
        title={
          <button className="flex items-center gap-1 max-w-full" onClick={() => setSwitcherOpen(true)}>
            <span className="truncate">{currentTitle ?? i18n('New Session')}</span>
            <ChevronDown className="w-4 h-4 shrink-0 text-muted-foreground" />
          </button>
        }
        subtitle={activeUserMessage ?? undefined}
        right={
          <div className="flex items-center gap-0.5 shrink-0">
            <ConnectionChip />
            {runningSessionId && (
              <span className="flex items-center gap-1 text-[11px] text-muted-foreground">
                <span className="w-2 h-2 rounded-full bg-primary animate-pulse" />
                {elapsedSeconds}s
              </span>
            )}
            {hasArtifacts && (
              <button
                className="mobile-hit p-1.5 relative"
                onClick={() => navigate('/artifact')}
                aria-label={i18n('Artifacts')}
              >
                <Boxes className="w-5 h-5" />
                <span className="absolute bottom-0.5 right-0.5 text-[10px] leading-none bg-primary text-primary-foreground rounded px-1 tabular-nums">
                  {artifactCount}
                </span>
              </button>
            )}
            <button
              className="mobile-hit p-1.5 disabled:opacity-40"
              onClick={startNewSession}
              disabled={isStreaming}
              aria-label={i18n('New Session')}
            >
              <Plus className="w-5 h-5" />
            </button>
          </div>
        }
      />

      <div className="shrink-0 border-b border-border">
        <TokenContextBar />
      </div>

      {selectedExecutionId ? (
        <div className="flex-1 min-h-0">
          <TeamMemberDetail />
        </div>
      ) : (
        <div className="relative flex-1 min-h-0">
          <div ref={containerRef} className="h-full overflow-y-auto message-list-scroll px-3" onScroll={onScroll}>
            {showWelcome ? <WelcomeScreen /> : <MessageList messages={messages} isStreaming={isStreaming} />}
            {runningSessionId && (
              <div className="px-1 py-2 flex flex-col gap-0.5 text-sm text-muted-foreground">
                <span className="flex items-center gap-2">
                  <Clock className="w-4 h-4 animate-pulse" />
                  {i18n('Session is running...')}
                </span>
                {liveStreamDropped && (
                  <span className="pl-6 text-xs">{i18n('Real-time stream dropped; updates arrive by polling')}</span>
                )}
              </div>
            )}
          </div>
          <MobileQuestionRail containerRef={containerRef} />
          {!followEnabled && !showWelcome && (
            <button
              className="absolute bottom-3 left-1/2 -translate-x-1/2 mobile-hit rounded-full bg-muted border border-border shadow-sm"
              onClick={() => {
                resumeFollow();
                scrollToBottom(true);
              }}
              aria-label={i18n('Jump to latest')}
            >
              <ArrowDown className="w-4 h-4" />
            </button>
          )}
        </div>
      )}

      {awaitingPermission && <PermissionBar />}

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

      {/* Either the tab bar or the keyboard already carries the bottom inset, so 8px is enough here. */}
      <div className="mobile-app-composer mobile-app-composer--above-tabbar">
        <MessageEditor variant="mobile" />
      </div>

      <MobileSheet open={switcherOpen} onClose={() => setSwitcherOpen(false)} title="Sessions" detent="half">
        {displaySessions.slice(0, 12).map((session) => (
          <button
            key={session.id}
            className="mobile-row w-full text-left border-b border-border"
            onClick={() => {
              setSwitcherOpen(false);
              void switchToSession(session.id).catch((e) => console.error('Failed to load session:', e));
            }}
          >
            <span className="flex-1 min-w-0 text-sm truncate">{session.title ?? i18n('Untitled')}</span>
            {session.streaming && <span className="w-2 h-2 rounded-full bg-primary shrink-0" />}
          </button>
        ))}
      </MobileSheet>

      <TasksSheet />
      <MessageActionSheet />
    </div>
  );
};
