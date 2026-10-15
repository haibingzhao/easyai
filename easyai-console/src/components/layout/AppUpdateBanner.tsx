import React from 'react';
import { RefreshCw, X } from 'lucide-react';
import { useTranslation } from '@/utils/use-translation';
import { applyBuildUpdate } from '@/hooks/useAppUpdate';

/**
 * Desktop counterpart of the mobile `AppUpdateDialog`.
 *
 * Deliberately not modal: a desktop window stays open for days across deploys, and unlike a phone
 * the user can always just reload, so the prompt floats instead of taking the screen. Pinned below
 * the 48px top bar rather than at the bottom, where it would sit on top of the chat composer.
 */
export const AppUpdateBanner: React.FC<{ onDismiss: () => void }> = ({ onDismiss }) => {
  const t = useTranslation();

  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed top-[56px] left-1/2 -translate-x-1/2 z-40 flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-2 text-xs shadow-lg"
    >
      <RefreshCw className="w-3.5 h-3.5 shrink-0 text-primary" />
      <span className="max-w-sm">{t('A new version is available — reload to update')}</span>
      <button
        type="button"
        onClick={applyBuildUpdate}
        className="shrink-0 rounded-md bg-primary px-2 py-1 font-medium text-primary-foreground"
      >
        {t('Reload')}
      </button>
      <button
        type="button"
        onClick={onDismiss}
        aria-label={t('Close')}
        className="shrink-0 p-1 text-muted-foreground hover:text-foreground"
      >
        <X className="w-3.5 h-3.5" />
      </button>
    </div>
  );
};
