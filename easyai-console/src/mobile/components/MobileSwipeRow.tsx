import React, { useRef, useState } from 'react';
import { Trash2 } from 'lucide-react';
import { i18n } from '@/utils/i18n';

const REVEAL_WIDTH = 84;
const CLOSE_THRESHOLD = REVEAL_WIDTH / 2;

interface MobileSwipeRowProps {
  onDelete: () => void;
  children: React.ReactNode;
}

/**
 * Swipe-left-to-delete row.
 *
 * Safari already owns the browser's own pull-to-refresh gesture, and a nested vertical drag
 * detector inside a scroll container fights it, so the history list refreshes from its header
 * button instead. Horizontal space is free, so deletion keeps the swipe.
 */
export const MobileSwipeRow: React.FC<MobileSwipeRowProps> = ({ onDelete, children }) => {
  const [offset, setOffset] = useState(0);
  const startRef = useRef<{ x: number; y: number } | null>(null);
  const lockedRef = useRef(false);

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
    setOffset(Math.max(0, Math.min(REVEAL_WIDTH, dx)));
  };

  const onTouchEnd = () => {
    startRef.current = null;
    setOffset((prev) => (prev > CLOSE_THRESHOLD ? REVEAL_WIDTH : 0));
  };

  return (
    <div className="relative overflow-hidden border-b border-border">
      <button
        className="absolute right-0 top-0 bottom-0 flex items-center justify-center gap-1 bg-destructive text-destructive-foreground text-xs"
        style={{ width: REVEAL_WIDTH }}
        onClick={() => {
          setOffset(0);
          onDelete();
        }}
      >
        <Trash2 className="w-4 h-4" />
        {i18n('Delete')}
      </button>
      <div
        className="relative bg-background"
        style={{
          transform: `translateX(-${offset}px)`,
          transition: offset === 0 || offset === REVEAL_WIDTH ? 'transform 0.18s ease' : 'none',
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
