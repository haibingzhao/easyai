import React from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { MobileTabBar } from './components/MobileTabBar';
import { ConnectionBanner } from './components/ConnectionBanner';
import { useKeyboardInset } from './hooks/useKeyboardInset';

/** Routes that own the whole viewport: they bring their own header and must lose the tab bar. */
const CHROMELESS_PREFIXES = ['/tool', '/artifact'];

/**
 * Mobile shell: the page owns its header, this component owns the vertical frame (status-bar inset,
 * banner, scroll host, tab bar) — the mobile counterpart of `AppLayout`.
 *
 * The layout box is shortened by the keyboard height rather than scrolled. iOS does not shrink the
 * layout viewport when the keyboard appears (and `interactive-widget`, the CSS that would ask it to,
 * is Chromium-only), so shortening the box is what actually keeps a bottom-anchored composer on
 * screen — without the `scrollIntoView` trick that leaves Safari's document panned halfway down.
 */
export const MobileAppLayout: React.FC = () => {
  const location = useLocation();
  const keyboardInset = useKeyboardInset();

  const chromeless = CHROMELESS_PREFIXES.some((prefix) => location.pathname.startsWith(prefix));
  const keyboardOpen = keyboardInset > 0;

  return (
    <div
      className="mobile-app"
      style={keyboardOpen ? { height: `calc(100dvh - ${keyboardInset}px)` } : undefined}
    >
      <ConnectionBanner />
      <main className="mobile-app-main">
        <Outlet />
      </main>
      {!chromeless && !keyboardOpen && <MobileTabBar />}
    </div>
  );
};
