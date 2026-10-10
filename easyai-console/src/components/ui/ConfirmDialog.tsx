import React, { useEffect } from 'react';
import { Button } from './Button';
import { i18n } from '@/utils/i18n';

interface ConfirmDialogProps {
  open: boolean;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
  open,
  message,
  confirmLabel,
  danger = false,
  onConfirm,
  onCancel,
}) => {
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onCancel]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onCancel}>
      <div
        className="w-full max-w-sm mx-4 bg-background rounded-lg shadow-lg border border-border p-4"
        onClick={(e) => e.stopPropagation()}
      >
        <p className="text-sm">{message}</p>
        <div className="mt-4 flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={onCancel}>{i18n('Cancel')}</Button>
          <Button type="button" variant={danger ? 'destructive' : 'default'} size="sm" onClick={onConfirm}>
            {confirmLabel ?? i18n('Confirm')}
          </Button>
        </div>
      </div>
    </div>
  );
};
