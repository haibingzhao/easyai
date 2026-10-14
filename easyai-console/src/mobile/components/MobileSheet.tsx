import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { i18n } from '@/utils/i18n';

export type MobileSheetDetent = 'half' | 'full' | 'auto';

interface MobileSheetProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  /**
   * `half` ≈ iOS medium sheet, `full` fills the viewport under the status bar,
   * `auto` sizes to content (menus, confirmations).
   */
  detent?: MobileSheetDetent;
  headerRight?: React.ReactNode;
  children: React.ReactNode;
}

/** Distance in pt a downward drag must cover to close. Below it the sheet snaps back. */
const DRAG_CLOSE_THRESHOLD = 80;

/** Exit-animation duration; must match the CSS transition on `.mobile-sheet`. */
const EXIT_MS = 250;

/**
 * Bottom sheet — the mobile layer's only overlay primitive.
 *
 * Desktop dialogs centre a 480px box, which on a phone lands under the thumb and clips.
 * Everything (permission prompt, tasks, session switcher, confirmations) rides this sheet
 * so there is one gesture model to learn: drag the header down, or tap the backdrop.
 */
export const MobileSheet: React.FC<MobileSheetProps> = ({
  open,
  onClose,
  title,
  detent = 'auto',
  headerRight,
  children,
}) => {
  const [rendered, setRendered] = useState(open);
  const [dragY, setDragY] = useState(0);
  const dragStartY = useRef<number | null>(null);

  useEffect(() => {
    if (open) {
      setRendered(true);
      return;
    }
    if (!rendered) return;
    const timer = setTimeout(() => setRendered(false), EXIT_MS);
    return () => clearTimeout(timer);
  }, [open, rendered]);

  const onTouchStart = useCallback((e: React.TouchEvent) => {
    dragStartY.current = e.touches[0].clientY;
  }, []);

  const onTouchMove = useCallback((e: React.TouchEvent) => {
    if (dragStartY.current === null) return;
    // Upward drags are the inner scroller's business, not the sheet's.
    setDragY(Math.max(0, e.touches[0].clientY - dragStartY.current));
  }, []);

  const onTouchEnd = useCallback(() => {
    if (dragY > DRAG_CLOSE_THRESHOLD) onClose();
    dragStartY.current = null;
    setDragY(0);
  }, [dragY, onClose]);

  if (!rendered) return null;

  const heightStyle =
    detent === 'half' ? '60dvh' : detent === 'full' ? 'calc(100dvh - env(safe-area-inset-top) - 44px)' : undefined;

  return createPortal(
    <>
      <div
        className="mobile-sheet-backdrop"
        data-open={open ? 'true' : 'false'}
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        className="mobile-sheet"
        data-open={open && dragY === 0 ? 'true' : 'false'}
        role="dialog"
        aria-label={title}
        style={{
          height: heightStyle,
          transform: dragY > 0 ? `translateY(${dragY}px)` : undefined,
          transition: dragY > 0 ? 'none' : undefined,
        }}
      >
        <div
          className="mobile-sheet-drag-area"
          onTouchStart={onTouchStart}
          onTouchMove={onTouchMove}
          onTouchEnd={onTouchEnd}
          onTouchCancel={onTouchEnd}
        >
          <div className="mobile-sheet-grabber" />
        </div>
        {(title || headerRight) && (
          <div
            className="mobile-sheet-header mobile-sheet-drag-area"
            onTouchStart={onTouchStart}
            onTouchMove={onTouchMove}
            onTouchEnd={onTouchEnd}
            onTouchCancel={onTouchEnd}
          >
            <span className="text-sm font-medium truncate">{title ? i18n(title) : ''}</span>
            <div className="flex items-center gap-1">
              {headerRight}
              <button className="mobile-hit !min-w-0 p-1.5 rounded-md text-muted-foreground" onClick={onClose} aria-label={i18n('Close')}>
                <X className="w-5 h-5" />
              </button>
            </div>
          </div>
        )}
        <div className="mobile-sheet-body">{children}</div>
      </div>
    </>,
    document.body
  );
};
