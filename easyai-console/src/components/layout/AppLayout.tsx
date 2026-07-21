import React, { useState, useRef, useEffect } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';
import { useNavStore } from '@/services/stores/nav-store';
import { useResizable } from '@/hooks/useResizable';

const SIDEBAR_MIN = 160;
const SIDEBAR_MAX = 400;
export const SIDEBAR_COLLAPSED_WIDTH = 40;
const SIDEBAR_COLLAPSE_BREAKPOINT = 900;

export const AppLayout: React.FC = () => {
  const sidebarCollapsed = useNavStore((s) => s.sidebarCollapsed);
  const sidebarWidth = useNavStore((s) => s.sidebarWidth);
  const setSidebarWidth = useNavStore((s) => s.setSidebarWidth);
  const [resizing, setResizing] = useState(false);
  const prevWidthRef = useRef(window.innerWidth);

  const effectiveWidth = sidebarCollapsed ? SIDEBAR_COLLAPSED_WIDTH : sidebarWidth;

  const resizer = useResizable({
    minWidth: SIDEBAR_MIN,
    maxWidth: SIDEBAR_MAX,
    onResize: (w) => setSidebarWidth(Math.round(w)),
    direction: 'right',
    onResizeStart: () => setResizing(true),
    onResizeEnd: () => setResizing(false),
  });

  // Responsive collapse: auto-close sidebar when window crosses below threshold.
  // Only fires on threshold crossing (not on every resize), with no reverse action.
  useEffect(() => {
    const handleResize = () => {
      const current = window.innerWidth;
      const prev = prevWidthRef.current;
      prevWidthRef.current = current;

      if (prev >= SIDEBAR_COLLAPSE_BREAKPOINT && current < SIDEBAR_COLLAPSE_BREAKPOINT) {
        const state = useNavStore.getState();
        if (!state.sidebarCollapsed) {
          state.toggleSidebar();
        }
      }
    };

    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  return (
    <div
      className={`app-layout w-full h-full grid relative ${resizing ? 'resizing' : ''}`}
      style={{
        gridTemplateColumns: `${effectiveWidth}px 1fr`,
        gridTemplateRows: '48px 1fr',
        gridTemplateAreas: `"sidebar topbar" "sidebar main"`,
      }}
    >
      <div style={{ gridArea: 'sidebar' }} className="border-r border-border overflow-hidden">
        <Sidebar />
      </div>
      {/* Drag handle between sidebar and main */}
      {!sidebarCollapsed && (
        <div
          className={`resize-handle ${resizing ? 'active' : ''}`}
          style={{
            position: 'absolute',
            left: effectiveWidth - 2,
            top: 0,
            bottom: 0,
            zIndex: 10,
          }}
          onMouseDown={(e) => {
            resizer.setCurrentWidth(sidebarWidth);
            resizer.onMouseDown(e);
          }}
          onTouchStart={(e) => {
            resizer.setCurrentWidth(sidebarWidth);
            resizer.onTouchStart(e);
          }}
        />
      )}
      <div style={{ gridArea: 'topbar' }} className="border-b border-border">
        <TopBar />
      </div>
      <div style={{ gridArea: 'main', minHeight: 0 }} className="overflow-hidden">
        <Outlet />
      </div>
    </div>
  );
};
