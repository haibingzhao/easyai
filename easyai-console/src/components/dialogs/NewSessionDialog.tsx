import React, { useEffect } from 'react';
import { Dialog } from '../ui/Dialog';
import { SessionItem } from '../ui/SessionItem';
import { useSessionStore } from '@/services/stores/session-store';
import { useChatStore } from '@/services/stores/chat-store';
import { switchToSession } from '@/services/session-switch';
import { i18n } from '@/utils/i18n';
import { MessageSquare } from 'lucide-react';

interface NewSessionDialogProps {
  open: boolean;
  onClose: () => void;
}

export const NewSessionDialog: React.FC<NewSessionDialogProps> = ({ open, onClose }) => {
  const { remoteSessions, remoteSessionHasMore, remoteSessionLoading, loadRemoteSessions, loadMoreRemoteSessions } = useSessionStore();
  const chatSessionId = useChatStore((s) => s.sessionId);

  useEffect(() => {
    if (open) {
      loadRemoteSessions(10);
    }
  }, [open]);

  const handleSelectSession = async (sessionId: string) => {
    try {
      // Delegated: the dialog used to carry its own copy of the load sequence, which drifted from
      // the History tab's. One path means the checkpoint state machine, the paging window and the
      // run-state probe behave identically no matter where the user picks a session.
      await switchToSession(sessionId);
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
