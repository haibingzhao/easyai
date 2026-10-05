import React, { useState, useRef, useCallback, useEffect } from 'react';

interface ToolTooltipProps {
  name?: string;
  description?: string;
  children: React.ReactNode;
  className?: string;
}

const MARGIN = 12;
/** Minimum below-viewport space before flipping the tooltip above the cursor */
const MIN_BELOW = 180;

export const ToolTooltip: React.FC<ToolTooltipProps> = ({ name, description, children, className }) => {
  const [visible, setVisible] = useState(false);
  const [position, setPosition] = useState<{ x: number; y: number }>({ x: 0, y: 0 });
  const showTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearShowTimer = useCallback(() => {
    if (showTimerRef.current) clearTimeout(showTimerRef.current);
    showTimerRef.current = null;
  }, []);

  const clearHideTimer = useCallback(() => {
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    hideTimerRef.current = null;
  }, []);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    setPosition({ x: e.clientX, y: e.clientY });
  }, []);

  const handleMouseEnter = useCallback((e: React.MouseEvent) => {
    setPosition({ x: e.clientX, y: e.clientY });
    clearHideTimer();
    if (!visible && !showTimerRef.current) {
      showTimerRef.current = setTimeout(() => {
        showTimerRef.current = null;
        setVisible(true);
      }, 200);
    }
  }, [visible, clearHideTimer]);

  // Grace period so the cursor can cross into the tooltip and scroll long content
  const handleMouseLeave = useCallback(() => {
    clearShowTimer();
    if (visible && !hideTimerRef.current) {
      hideTimerRef.current = setTimeout(() => {
        hideTimerRef.current = null;
        setVisible(false);
      }, 300);
    }
  }, [visible, clearShowTimer]);

  useEffect(() => {
    return () => {
      clearShowTimer();
      clearHideTimer();
    };
  }, [clearShowTimer, clearHideTimer]);

  // Anchor below the cursor; flip to a bottom-anchored box above when space below is tight.
  // maxHeight is clamped to the viewport so long content scrolls instead of overflowing.
  const tooltipStyle: React.CSSProperties = (() => {
    const left = Math.max(8, Math.min(position.x + MARGIN, window.innerWidth - 300));
    const spaceBelow = window.innerHeight - position.y - MARGIN;
    if (spaceBelow >= MIN_BELOW) {
      return { top: position.y + MARGIN, left, maxHeight: Math.max(MIN_BELOW, spaceBelow - 8) };
    }
    const spaceAbove = position.y - MARGIN;
    return { bottom: window.innerHeight - position.y + MARGIN, left, maxHeight: Math.max(120, spaceAbove - 8) };
  })();

  return (
    <>
      <span
        className={className}
        onMouseMove={handleMouseMove}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
      >
        {children}
      </span>
      {visible && (
        <div
          className="fixed z-[9999] w-72 p-3 bg-zinc-800 border border-zinc-700 rounded-lg shadow-xl overflow-y-auto overscroll-contain"
          style={tooltipStyle}
          onMouseEnter={clearHideTimer}
          onMouseLeave={() => setVisible(false)}
        >
          {name && (
            <p className="text-sm font-mono font-semibold text-zinc-100 mb-1 break-all">{name}</p>
          )}
          {description && (
            <p className="text-xs text-zinc-300 leading-relaxed whitespace-pre-wrap break-all">{description}</p>
          )}
        </div>
      )}
    </>
  );
};
