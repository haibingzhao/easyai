import { useEffect, useRef } from 'react';

const LONG_PRESS_MS = 500;
/** Movement that counts as scrolling, not pressing. */
const CANCEL_SLOP = 12;

/**
 * Long-press detector for a scroll container.
 *
 * Attached to the *container* rather than to each bubble: the transcript is rendered by
 * `MessageList`, which the mobile layer reuses untouched. The handler resolves the touched
 * message from the DOM (`[data-msg-index]`), the same anchor the question navigator uses.
 */
export function useLongPress(
  containerRef: React.RefObject<HTMLElement | null>,
  onLongPress: (element: HTMLElement) => void
): void {
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const startRef = useRef<{ x: number; y: number } | null>(null);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const clear = () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      startRef.current = null;
    };

    const onTouchStart = (e: TouchEvent) => {
      if (e.touches.length !== 1) return;
      const touch = e.touches[0];
      const target = touch.target as HTMLElement | null;
      if (!target) return;
      // Never hijack a press on a control: buttons and links own their own tap.
      if (target.closest('button, a, input, textarea, [contenteditable]')) return;
      startRef.current = { x: touch.clientX, y: touch.clientY };
      timerRef.current = setTimeout(() => {
        onLongPress(target);
      }, LONG_PRESS_MS);
    };

    const onTouchMove = (e: TouchEvent) => {
      const start = startRef.current;
      if (!start) return;
      const touch = e.touches[0];
      if (Math.abs(touch.clientX - start.x) > CANCEL_SLOP || Math.abs(touch.clientY - start.y) > CANCEL_SLOP) {
        clear();
      }
    };

    el.addEventListener('touchstart', onTouchStart, { passive: true });
    el.addEventListener('touchmove', onTouchMove, { passive: true });
    el.addEventListener('touchend', clear, { passive: true });
    el.addEventListener('touchcancel', clear, { passive: true });

    return () => {
      clear();
      el.removeEventListener('touchstart', onTouchStart);
      el.removeEventListener('touchmove', onTouchMove);
      el.removeEventListener('touchend', clear);
      el.removeEventListener('touchcancel', clear);
    };
  }, [containerRef, onLongPress]);
}
