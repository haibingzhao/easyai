import React, { useEffect, useState, useCallback } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { UserPlus, Trash2, Eye, EyeOff, Loader2, Shield, ShieldCheck, X } from 'lucide-react';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { useCapabilityStore, selectKnowledgeShared, selectHasGroup } from '@/services/stores/capability-store';
import { i18n } from '@/utils/i18n';
import type { KnowledgeArticleDto } from '@/services/knowledge-service';

interface AccessManagementDialogProps {
  open: boolean;
  onClose: () => void;
  article: KnowledgeArticleDto;
}

export const AccessManagementDialog: React.FC<AccessManagementDialogProps> = ({
  open,
  onClose,
  article,
}) => {
  const accessGrants = useKnowledgeStore((s) => s.accessGrants);
  const accessLoading = useKnowledgeStore((s) => s.accessLoading);
  const articleError = useKnowledgeStore((s) => s.articleError);
  const clearArticleError = useKnowledgeStore((s) => s.clearArticleError);
  const loadAccessGrants = useKnowledgeStore((s) => s.loadAccessGrants);
  const grantAccess = useKnowledgeStore((s) => s.grantAccess);
  const revokeAccess = useKnowledgeStore((s) => s.revokeAccess);
  const updateVisibility = useKnowledgeStore((s) => s.updateVisibility);
  const clearAccessGrants = useKnowledgeStore((s) => s.clearAccessGrants);
  const knowledgeShared = useCapabilityStore(selectKnowledgeShared);
  const hasGroup = useCapabilityStore(selectHasGroup);
  const showVisibility = !knowledgeShared && hasGroup;

  const [visibility, setVisibility] = useState(article.visibility);
  const [newGranteeId, setNewGranteeId] = useState('');
  const [newPermission, setNewPermission] = useState<'read' | 'write'>('read');
  const [adding, setAdding] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setVisibility(article.visibility);
      clearArticleError();
    }
  }, [open, article.visibility, clearArticleError]);

  useEffect(() => {
    if (open) {
      void loadAccessGrants(article.id).catch(() => undefined);
    }
  }, [open, article.id, loadAccessGrants]);

  useEffect(() => {
    return () => { clearAccessGrants(); };
  }, [clearAccessGrants]);

  const handleVisibilityChange = useCallback(async (vis: string) => {
    if (vis === visibility) return;
    const previous = visibility;
    setVisibility(vis);
    try {
      await updateVisibility(article.id, vis);
    } catch {
      setVisibility(previous);
    }
  }, [article.id, updateVisibility, visibility]);

  const handleGrant = useCallback(async () => {
    if (!newGranteeId.trim()) return;
    setAdding(true);
    try {
      await grantAccess({
        articleId: article.id,
        granteeId: newGranteeId.trim(),
        permission: newPermission,
      });
      setNewGranteeId('');
    } catch {
      // articleError is rendered below
    } finally {
      setAdding(false);
    }
  }, [article.id, newGranteeId, newPermission, grantAccess]);

  const handleRevoke = useCallback(async (granteeId: string) => {
    setRevokeTarget(null);
    try {
      await revokeAccess(article.id, granteeId);
    } catch {
      // articleError is rendered below
    }
  }, [article.id, revokeAccess]);

  const permissionLabel = (perm: string) =>
    perm === 'write' ? i18n('Read & Write') : i18n('Read only');

  return (
    <Dialog open={open} onClose={onClose} title={i18n('Share & Permissions')}>
      <div className="space-y-5">
        {articleError && (
          <div className="flex items-start justify-between gap-2 text-sm text-destructive">
            <span>{articleError}</span>
            <button onClick={clearArticleError} className="hover:opacity-70 shrink-0">
              <X className="size-3.5" />
            </button>
          </div>
        )}

        {/* Visibility section — meaningless when the whole bucket is group-shared or no group exists */}
        {showVisibility && (
          <div>
            <label className="block text-sm font-medium mb-2">{i18n('Visibility')}</label>
            <div className="flex gap-2">
              <button
                onClick={() => handleVisibilityChange('private')}
                className={`flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-md border text-sm transition-colors ${
                  visibility === 'private'
                    ? 'border-primary bg-primary/5 text-primary'
                    : 'border-border hover:bg-muted'
                }`}
              >
                <EyeOff className="size-4" />
                {i18n('Private')}
              </button>
              <button
                onClick={() => handleVisibilityChange('group')}
                className={`flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-md border text-sm transition-colors ${
                  visibility === 'group'
                    ? 'border-primary bg-primary/5 text-primary'
                    : 'border-border hover:bg-muted'
                }`}
              >
                <Eye className="size-4" />
                {i18n('Group')}
              </button>
            </div>
            <p className="text-xs text-muted-foreground mt-1.5">
              {visibility === 'private'
                ? i18n('Only you can access this article')
                : i18n('All group members can access this article')}
            </p>
          </div>
        )}

        {/* Access grants section */}
        <div>
          <label className="block text-sm font-medium mb-2">{i18n('Individual Access')}</label>

          {/* Add grant form */}
          <div className="flex gap-2 mb-3">
            <input
              type="text"
              value={newGranteeId}
              onChange={(e) => setNewGranteeId(e.target.value)}
              placeholder={i18n('User ID')}
              className="flex-1 px-3 py-1.5 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary"
            />
            <select
              value={newPermission}
              onChange={(e) => setNewPermission(e.target.value as 'read' | 'write')}
              className="px-2 py-1.5 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary"
            >
              <option value="read">{i18n('Read only')}</option>
              <option value="write">{i18n('Read & Write')}</option>
            </select>
            <button
              onClick={handleGrant}
              disabled={adding || !newGranteeId.trim()}
              className="flex items-center gap-1 px-3 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
            >
              {adding ? <Loader2 className="size-3.5 animate-spin" /> : <UserPlus className="size-3.5" />}
              {i18n('Add')}
            </button>
          </div>

          {/* Grants list */}
          {accessLoading ? (
            <div className="flex items-center justify-center py-4">
              <Loader2 className="size-4 animate-spin text-muted-foreground" />
            </div>
          ) : accessGrants.length === 0 ? (
            <div className="text-xs text-muted-foreground text-center py-3 border border-dashed border-border rounded-md">
              {i18n('No individual access grants')}
            </div>
          ) : (
            <div className="space-y-1.5">
              {accessGrants.map((grant) => (
                <div
                  key={grant.id}
                  className="flex items-center gap-2 px-3 py-2 rounded-md border border-border bg-muted/30"
                >
                  {grant.permission === 'write' ? (
                    <ShieldCheck className="size-3.5 text-primary shrink-0" />
                  ) : (
                    <Shield className="size-3.5 text-muted-foreground shrink-0" />
                  )}
                  <span className="text-xs font-medium flex-1 truncate">{grant.granteeId}</span>
                  <span className="text-[10px] text-muted-foreground">
                    {permissionLabel(grant.permission)}
                  </span>
                  <button
                    onClick={() => setRevokeTarget(grant.granteeId)}
                    className="p-1 rounded hover:bg-destructive/10 text-destructive transition-colors"
                    title={i18n('Revoke')}
                  >
                    <Trash2 className="size-3" />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={revokeTarget !== null}
        message={i18n('Revoke this access grant?')}
        danger
        confirmLabel={i18n('Revoke')}
        onConfirm={() => { if (revokeTarget) void handleRevoke(revokeTarget); }}
        onCancel={() => setRevokeTarget(null)}
      />
    </Dialog>
  );
};
