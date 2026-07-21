import React from 'react';
import { AlertCircle } from 'lucide-react';
import { i18n } from '../../utils/i18n';
import type { ErrorMessage as ErrorMessageType } from '../../types/message';

interface ErrorMessageProps {
  message: ErrorMessageType;
}

export const ErrorMessage: React.FC<ErrorMessageProps> = ({ message }) => {
  return (
    <div className="mx-4 p-3 bg-destructive/10 text-destructive rounded-lg">
      <div className="flex items-start gap-2">
        <AlertCircle className="size-4 mt-0.5 flex-shrink-0" />
        <div className="flex-1">
          <p className="font-medium text-sm">{i18n('Error')}</p>
          <p className="text-sm opacity-80 mt-1 whitespace-pre-wrap">{message.content}</p>
        </div>
      </div>
    </div>
  );
};