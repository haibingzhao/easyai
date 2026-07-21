import React from 'react';
import { Loader2 } from 'lucide-react';
import { i18n } from '../../utils/i18n';

export const StreamingIndicator: React.FC = () => {
  return (
    <div className="flex items-center gap-2 px-4 py-2 text-muted-foreground">
      <Loader2 className="w-4 h-4 animate-spin" />
      <span className="text-sm">{i18n('Thinking')}</span>
    </div>
  );
};
