import React, { useState, useRef, useCallback, useEffect } from 'react';

interface ToolTooltipProps {
  name: string;
  description?: string;
  children: React.ReactNode;
}

export const ToolTooltip: React.FC<ToolTooltipProps> = ({ name, description, children }) => {
  const [hovered, setHovered] = useState(false);
  const [position, setPosition] = useState<{ x: number; y: number }>({ x: 0, y: 0 });
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    setPosition({ x: e.clientX, y: e.clientY });
  }, []);

  const handleMouseEnter = useCallback((e: React.MouseEvent) => {
    setPosition({ x: e.clientX, y: e.clientY });
    timerRef.current = setTimeout(() => setHovered(true), 200);
  }, []);

  const handleMouseLeave = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    setHovered(false);
  }, []);

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  // Keep tooltip within viewport bounds: flip above cursor when space below is insufficient
  const tooltipStyle: React.CSSProperties = (() => {
    if (!hovered) return { top: -9999, left: -9999 };
    const tooltipHeight = tooltipRef.current?.offsetHeight ?? 200;
    const margin = 12;
    const spaceBelow = window.innerHeight - position.y - margin;
    const top = spaceBelow >= tooltipHeight + margin
      ? position.y + margin
      : Math.max(8, position.y - margin - tooltipHeight);
    return {
      top,
      left: Math.min(position.x + margin, window.innerWidth - 300),
      maxHeight: window.innerHeight - 16,
    };
  })();

  return (
    <>
      <div
        onMouseMove={handleMouseMove}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
      >
        {children}
      </div>
      {hovered && (
        <div
          ref={tooltipRef}
          className="fixed z-[9999] w-72 p-3 bg-zinc-800 border border-zinc-700 rounded-lg shadow-xl pointer-events-none overflow-y-auto"
          style={tooltipStyle}
        >
          <p className="text-sm font-mono font-semibold text-zinc-100 mb-1">{name}</p>
          {description && (
            <p className="text-xs text-zinc-300 leading-relaxed whitespace-pre-wrap">{description}</p>
          )}
        </div>
      )}
    </>
  );
};
