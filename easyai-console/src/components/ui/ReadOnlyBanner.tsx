import React from 'react';
import { Lock } from 'lucide-react';
import { i18n } from '@/utils/i18n';

interface ReadOnlyBannerProps {
  /** Optional override of the default message. */
  message?: string;
}

/**
 * Shown on a group-configuration screen when the current login may read the shared bucket but not
 * manage it (a group member rather than the owner), or when a deployment-wide layer pins the value.
 * Purely advisory — the backend re-checks `isGroupOwner` on every write.
 */
export const ReadOnlyBanner: React.FC<ReadOnlyBannerProps> = ({ message }) => (
  <div className="flex items-start gap-2 p-3 rounded-lg bg-muted text-muted-foreground text-sm">
    <Lock className="w-4 h-4 mt-0.5 shrink-0" />
    <span>{message ?? i18n('Read-only: only the group owner can change these shared settings.')}</span>
  </div>
);
