import React from 'react';
import { RefreshCw } from 'lucide-react';
import { useTranslation } from '@/utils/use-translation';
import { applyBuildUpdate } from '@/hooks/useAppUpdate';

/**
 * Phone counterpart of the desktop `AppUpdateBanner`, and blocking on purpose. The alternative — a
 * toast that scrolls away — leaves the phone running a build the operator already replaced, and the
 * only way back is deleting the web app from the home screen and re-adding it.
 *
 * Centred rather than pinned to an edge so it clears both the tab bar and the status-bar inset,
 * which iOS reports lazily in a standalone web app.
 */
export const AppUpdateDialog: React.FC<{ onDismiss: () => void }> = ({ onDismiss }) => {
  const t = useTranslation();

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t('New version available')}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-6"
    >
      <div className="w-full max-w-xs rounded-2xl border border-border bg-card p-5 shadow-xl">
        <div className="flex items-center gap-2 text-sm font-medium">
          <RefreshCw className="w-4 h-4 shrink-0 text-primary" />
          {t('New version available')}
        </div>
        <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
          {t('The server deployed a newer interface. Update to continue.')}
        </p>
        <div className="mt-4 flex gap-2">
          <button
            type="button"
            onClick={onDismiss}
            className="mobile-hit flex-1 rounded-lg border border-border px-3 py-2 text-xs text-muted-foreground"
          >
            {t('Later')}
          </button>
          <button
            type="button"
            onClick={applyBuildUpdate}
            className="mobile-hit flex-1 rounded-lg bg-primary px-3 py-2 text-xs font-medium text-primary-foreground"
          >
            {t('Update now')}
          </button>
        </div>
      </div>
    </div>
  );
};
