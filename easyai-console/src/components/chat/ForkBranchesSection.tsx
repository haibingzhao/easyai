import React, { useEffect, useState } from 'react';
import { GitBranch, MessageSquare } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { sessionService } from '@/services/session-service';
import { switchToSession } from '@/services/session-switch';
import type { ForkBranchInfo } from '@/services/session-service';
import { i18n } from '@/utils/i18n';

function formatBranchTime(createdAt: number): string {
  return new Date(createdAt).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Summary panel section listing all fork branches of the current main session.
 * Clicking a branch switches the chat view to it; clicking the main session returns.
 * Renders nothing while the main session has no branches.
 */
export const ForkBranchesSection: React.FC = () => {
  const sessionId = useChatStore((s) => s.sessionId);
  const forkRootId = useChatStore((s) => s.forkRootId);
  const [branches, setBranches] = useState<ForkBranchInfo[]>([]);

  const rootId = forkRootId ?? sessionId;

  useEffect(() => {
    if (!rootId) {
      setBranches([]);
      return;
    }
    let cancelled = false;
    sessionService.listForks(rootId)
      .then((forks) => { if (!cancelled) setBranches(forks); })
      .catch((e) => {
        console.error('Failed to load fork branches:', e);
        if (!cancelled) setBranches([]);
      });
    return () => { cancelled = true; };
  }, [rootId, sessionId]);

  if (!rootId || branches.length === 0) return null;

  const titleOf = (branch: ForkBranchInfo): string => {
    const base = `${i18n('Branch')} · ${formatBranchTime(branch.createdAt)}`;
    if (branch.forkedFromSessionId === rootId) return base;
    const parent = branches.find((b) => b.id === branch.forkedFromSessionId);
    const parentLabel = parent
      ? `${i18n('Branch')} · ${formatBranchTime(parent.createdAt)}`
      : i18n('Main session');
    return `${base} (${i18n('from')} ${parentLabel})`;
  };

  const handleSwitch = async (targetId: string) => {
    if (targetId === sessionId) return;
    try {
      await switchToSession(targetId);
    } catch (e) {
      console.error('Failed to switch to branch:', e);
    }
  };

  return (
    <>
      <div className="border-t border-dashed border-border my-2" />
      <div className="px-1">
        <h3 className="text-xs font-medium text-muted-foreground mb-1.5 flex items-center gap-1">
          <GitBranch className="w-3 h-3" />
          {i18n('Branches')}
        </h3>
        <div className="space-y-1">
          <button
            onClick={() => handleSwitch(rootId)}
            className={`w-full text-left px-2 py-1.5 rounded text-xs transition-colors flex items-center gap-1.5 hover:bg-muted ${
              sessionId === rootId ? 'bg-muted font-medium' : ''
            }`}
          >
            <MessageSquare className="w-3 h-3 shrink-0 text-muted-foreground" />
            <span className="truncate">{i18n('Main session')}</span>
          </button>
          {branches.map((branch) => (
            <button
              key={branch.id}
              onClick={() => handleSwitch(branch.id)}
              className={`w-full text-left px-2 py-1.5 rounded text-xs transition-colors flex items-center gap-1.5 hover:bg-muted ${
                sessionId === branch.id ? 'bg-muted font-medium' : ''
              }`}
            >
              <GitBranch className="w-3 h-3 shrink-0 text-muted-foreground" />
              <span className="truncate">{titleOf(branch)}</span>
            </button>
          ))}
        </div>
      </div>
    </>
  );
};
