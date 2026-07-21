import { useRef, useCallback, useEffect } from 'react';

interface UseResizableOptions {
  minWidth: number;
  maxWidth: number;
  onResize: (width: number) => void;
  /** Which side the handle is on: 'right' means handle is on the right edge (dragging right increases width) */
  direction: 'left' | 'right';
  /** Called when dragging starts (e.g. to disable CSS transitions) */
  onResizeStart?: () => void;
  /** Called when dragging ends */
  onResizeEnd?: () => void;
}

interface UseResizableReturn {
  /** Set the current width before starting a drag (call from onMouseDown handler) */
  setCurrentWidth: (w: number) => void;
  onMouseDown: (e: React.MouseEvent) => void;
  onTouchStart: (e: React.TouchEvent) => void;
}

/**
 * Generic drag-to-resize hook.
 * Handles mousedown/mousemove/mouseup + touch events on a handle element.
 * Uses requestAnimationFrame throttling to avoid excessive re-renders.
 *
 * Usage: In the handle's onMouseDown, call setCurrentWidth(actualWidth) first,
 * then call onMouseDown(e) to start the drag.
 */
export function useResizable(options: UseResizableOptions): UseResizableReturn {
  const { minWidth, maxWidth } = options;
  const draggingRef = useRef(false);
  const rafRef = useRef<number | null>(null);
  const lastXRef = useRef(0);
  const currentWidthRef = useRef(0);

  // Store latest callbacks in refs so the stable mousedown handler
  // always attaches/removes the current versions of the document listeners.
  const onMouseMoveRef = useRef<(e: MouseEvent) => void>(() => {});
  const onMouseUpRef = useRef<() => void>(() => {});
  const onTouchMoveRef = useRef<(e: TouchEvent) => void>(() => {});
  const onTouchEndRef = useRef<() => void>(() => {});

  // Keep options in a ref for handler bodies to read latest values
  const optionsRef = useRef(options);
  optionsRef.current = options;

  onMouseMoveRef.current = (e: MouseEvent) => {
    if (!draggingRef.current) return;
    const { direction, onResize } = optionsRef.current;
    const delta = direction === 'right'
      ? e.clientX - lastXRef.current
      : lastXRef.current - e.clientX;
    lastXRef.current = e.clientX;
    const newWidth = Math.min(maxWidth, Math.max(minWidth, currentWidthRef.current + delta));
    currentWidthRef.current = newWidth;

    if (rafRef.current === null) {
      rafRef.current = requestAnimationFrame(() => {
        onResize(currentWidthRef.current);
        rafRef.current = null;
      });
    }
  };

  onMouseUpRef.current = () => {
    draggingRef.current = false;
    document.body.style.userSelect = '';
    document.body.style.cursor = '';
    document.removeEventListener('mousemove', onMouseMoveRef.current);
    document.removeEventListener('mouseup', onMouseUpRef.current);
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
    optionsRef.current.onResizeEnd?.();
  };

  onTouchMoveRef.current = (e: TouchEvent) => {
    if (!draggingRef.current || e.touches.length === 0) return;
    const { direction, onResize } = optionsRef.current;
    const touch = e.touches[0];
    const delta = direction === 'right'
      ? touch.clientX - lastXRef.current
      : lastXRef.current - touch.clientX;
    lastXRef.current = touch.clientX;
    const newWidth = Math.min(maxWidth, Math.max(minWidth, currentWidthRef.current + delta));
    currentWidthRef.current = newWidth;

    if (rafRef.current === null) {
      rafRef.current = requestAnimationFrame(() => {
        onResize(currentWidthRef.current);
        rafRef.current = null;
      });
    }
  };

  onTouchEndRef.current = () => {
    draggingRef.current = false;
    // Restore body styles (matches mouse handler) for mixed-input devices
    document.body.style.userSelect = '';
    document.body.style.cursor = '';
    document.removeEventListener('touchmove', onTouchMoveRef.current);
    document.removeEventListener('touchend', onTouchEndRef.current);
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
    optionsRef.current.onResizeEnd?.();
  };

  const setCurrentWidth = useCallback((w: number) => {
    currentWidthRef.current = w;
  }, []);

  // Stable mousedown handler — uses refs so it never needs to be recreated
  const onMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    draggingRef.current = true;
    lastXRef.current = e.clientX;
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'col-resize';
    document.addEventListener('mousemove', onMouseMoveRef.current);
    document.addEventListener('mouseup', onMouseUpRef.current);
    optionsRef.current.onResizeStart?.();
  }, []);

  // Stable touchstart handler
  const onTouchStart = useCallback((e: React.TouchEvent) => {
    if (e.touches.length === 0) return;
    draggingRef.current = true;
    lastXRef.current = e.touches[0].clientX;
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'col-resize';
    document.addEventListener('touchmove', onTouchMoveRef.current, { passive: false });
    document.addEventListener('touchend', onTouchEndRef.current);
    optionsRef.current.onResizeStart?.();
  }, []);

  // Cleanup on unmount: remove any lingering document listeners and restore body styles
  useEffect(() => {
    return () => {
      if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
      if (draggingRef.current) {
        draggingRef.current = false;
        document.body.style.userSelect = '';
        document.body.style.cursor = '';
        document.removeEventListener('mousemove', onMouseMoveRef.current);
        document.removeEventListener('mouseup', onMouseUpRef.current);
        document.removeEventListener('touchmove', onTouchMoveRef.current);
        document.removeEventListener('touchend', onTouchEndRef.current);
      }
    };
  }, []);

  return {
    setCurrentWidth,
    onMouseDown,
    onTouchStart,
  };
}
