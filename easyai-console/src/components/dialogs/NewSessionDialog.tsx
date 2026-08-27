import React, { useEffect } from 'react';
import { Dialog } from '../ui/Dialog';
import { SessionItem } from '../ui/SessionItem';
import { useSessionStore } from '@/services/stores/session-store';
import { useChatStore } from '@/services/stores/chat-store';
import { useNavStore } from '@/services/stores/nav-store';
import { sessionService } from '@/services/session-service';
import { getCheckpoints, getFileReviewState } from '@/services/checkpoint-service';
import { getStreamingStatus } from '@/services/chat-service';
import type { CheckpointInfo } from '@/types/checkpoint';
import { i18n } from '@/utils/i18n';
import { MessageSquare } from 'lucide-react';

interface NewSessionDialogProps {
  open: boolean;
  onClose: () => void;
}

export const NewSessionDialog: React.FC<NewSessionDialogProps> = ({ open, onClose }) => {
  const { remoteSessions, remoteSessionHasMore, remoteSessionLoading, setCurrentSessionId, loadRemoteSessions, loadMoreRemoteSessions } = useSessionStore();
  const { setSessionId, loadSessionMessages, sessionId: chatSessionId, setFileReviewOverrides, setRunningSessionId, setStreaming } = useChatStore();

  useEffect(() => {
    if (open) {
      loadRemoteSessions(10);
    }
  }, [open]);

  const handleSelectSession = async (sessionId: string) => {
    try {
      // Step 1: Check if session is still streaming on this server
      const streamingStatus = await getStreamingStatus(sessionId);

      // Load existing messages first (for both running and completed sessions)
      const [detail, checkpoints] = await Promise.all([
        sessionService.getSessionDetail(sessionId),
        getCheckpoints(sessionId).catch((e) => {
          console.warn('Failed to load checkpoints:', e);
          return [] as CheckpointInfo[];
        }),
      ]);
      loadSessionMessages(detail.messages, detail.pendingPermission, checkpoints, detail.endReason, detail.variables, detail.modelContextLength);
      useNavStore.getState().setSelectedFile(null);
      // Load file review state
      const reviewState = await getFileReviewState(sessionId).catch((e) => {
        console.warn('Failed to load file review state:', e);
        return null;
      });
      if (reviewState?.reviews) {
        setFileReviewOverrides(reviewState.reviews);
      }

      if (streamingStatus.local || streamingStatus.streaming) {
        // Session is still running — enter polling mode to get new messages
        setSessionId(sessionId);
        setRunningSessionId(sessionId);
        // When there's a pending permission request, loadSessionMessages already
        // set isStreaming=false so the PermissionBar is interactive. Don't override it.
        if (!detail.pendingPermission) {
          setStreaming(true);
        }
        setCurrentSessionId(sessionId);
        onClose();
        return;
      }

      // Session completed — stop any running polling and show this session
      setRunningSessionId(null);
      setCurrentSessionId(sessionId);
      setSessionId(sessionId);
      onClose();
    } catch (e) {
      console.error('Failed to load session:', e);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} title={i18n('New Session')}>
      {/* Welcome section */}
      <div className="text-center py-6">
        <div className="inline-flex items-center justify-center w-16 h-16 rounded-lg bg-primary/10 mb-4">
          <MessageSquare className="w-8 h-8 text-primary" />
        </div>
        <h2 className="text-xl font-semibold mb-2">{i18n('Collaborate with Agent')}</h2>
        <p className="text-sm text-muted-foreground">{i18n('End-to-end dev tasks with MCP')}</p>
      </div>

      {/* Recent sessions */}
      {remoteSessions.length > 0 && (
        <div>
          <h3 className="text-sm font-medium text-muted-foreground mb-3">{i18n('History')}</h3>
          <div className="space-y-2 max-h-56 overflow-y-auto">
            {remoteSessions.map((session) => (
              <SessionItem
                key={session.id}
                session={session}
                isSelected={chatSessionId === session.id}
                showDelete={false}
                onSelect={handleSelectSession}
              />
            ))}
          </div>
          {remoteSessionHasMore && (
            <div className="mt-3 pt-3 border-t border-border">
              <button
                className="w-full py-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
                onClick={() => loadMoreRemoteSessions(10)}
                disabled={remoteSessionLoading}
              >
                {remoteSessionLoading ? i18n('Loading...') : i18n('Load More')}
              </button>
            </div>
          )}
        </div>
      )}
    </Dialog>
  );
};
