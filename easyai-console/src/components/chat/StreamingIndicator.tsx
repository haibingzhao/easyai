import React from 'react';
import { Loader2 } from 'lucide-react';
import { i18n } from '../../utils/i18n';
import type { RetryInfo } from '@/services/stores/chat/types';

interface StreamingIndicatorProps {
  retryInfo?: RetryInfo | null;
}

export const StreamingIndicator: React.FC<StreamingIndicatorProps> = ({ retryInfo }) => {
  if (retryInfo) {
    const text = i18n('Request timed out, retrying ({attempt}/{maxRetries})...')
      .replace('{attempt}', String(retryInfo.attempt))
      .replace('{maxRetries}', String(retryInfo.maxRetries));
    return (
      <div className="flex items-center gap-2 px-4 py-2 text-amber-600 dark:text-amber-400">
        <Loader2 className="w-4 h-4 animate-spin" />
        <span className="text-sm">{text}</span>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-2 px-4 py-2 text-muted-foreground">
      <Loader2 className="w-4 h-4 animate-spin" />
      <span className="text-sm">{i18n('Thinking')}</span>
    </div>
  );
};
