import React, { useRef, useState } from 'react';
import { i18n } from '@/utils/i18n';

/** One revealed action occupies this width; the reveal band is `actions.length` of these. */
const ACTION_WIDTH = 84;

export interface MobileSwipeAction {
  id: string;
  labelKey: string;
  icon: React.ComponentType<{ className?: string }>;
  /** `destructive` paints the band in the error surface; `default` reads as a normal action. */
  tone?: 'default' | 'destructive';
  onSelect: () => void;
}

interface MobileSwipeRowProps {
  actions: MobileSwipeAction[];
  children: React.ReactNode;
}

/**
 * Swipe-left-to-act row.
 *
 * Safari already owns the browser's own pull-to-refresh gesture, and a nested vertical drag
 * detector inside a scroll container fights it, so lists refresh from their header button
 * instead. Horizontal space is free, so row-level verbs keep the swipe.
 */
export const MobileSwipeRow: React.FC<MobileSwipeRowProps> = ({ actions, children }) => {
  const [offset, setOffset] = useState(0);
  const startRef = useRef<{ x: number; y: number } | null>(null);
  const lockedRef = useRef(false);
  const revealWidth = actions.length * ACTION_WIDTH;

  const onTouchStart = (e: React.TouchEvent) => {
    const t = e.touches[0];
    startRef.current = { x: t.clientX, y: t.clientY };
    lockedRef.current = false;
  };

  const onTouchMove = (e: React.TouchEvent) => {
    const start = startRef.current;
    if (!start) return;
    const t = e.touches[0];
    const dx = start.x - t.clientX;
    const dy = Math.abs(start.y - t.clientY);
    // Decide once per gesture: horizontal means the row owns it, vertical means the list does.
    if (!lockedRef.current) {
      if (Math.abs(dx) < 8 && dy < 8) return;
      if (Math.abs(dx) <= dy) {
        startRef.current = null;
        return;
      }
      lockedRef.current = true;
    }
    setOffset(Math.max(0, Math.min(revealWidth, dx)));
  };

  const onTouchEnd = () => {
    startRef.current = null;
    setOffset((prev) => (prev > revealWidth / 2 ? revealWidth : 0));
  };

  return (
    <div className="relative overflow-hidden border-b border-border">
      <div className="absolute right-0 top-0 bottom-0 flex" style={{ width: revealWidth }}>
        {actions.map((action) => (
          <button
            key={action.id}
            className={`flex items-center justify-center gap-1 text-xs ${
              action.tone === 'destructive'
                ? 'bg-destructive text-destructive-foreground'
                : 'bg-primary text-primary-foreground'
            }`}
            style={{ width: ACTION_WIDTH }}
            onClick={() => {
              setOffset(0);
              action.onSelect();
            }}
          >
            <action.icon className="w-4 h-4" />
            {i18n(action.labelKey)}
          </button>
        ))}
      </div>
      <div
        className="relative bg-background"
        style={{
          transform: `translateX(-${offset}px)`,
          transition: offset === 0 || offset === revealWidth ? 'transform 0.18s ease' : 'none',
        }}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
        onTouchCancel={onTouchEnd}
      >
        {children}
      </div>
    </div>
  );
};
