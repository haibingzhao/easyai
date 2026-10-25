import React, { useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { MobileTabBar } from './components/MobileTabBar';
import { ConnectionBanner } from './components/ConnectionBanner';
import { MOBILE_MORE_TAB_ID, MOBILE_TOP_ITEMS } from './registries';
import { AppUpdateDialog } from './components/AppUpdateDialog';
import { useKeyboardInset } from './hooks/useKeyboardInset';
import { useAppUpdate } from '@/hooks/useAppUpdate';
import { APP_CONFIG } from '@/constants/navigation';
import { startNotificationPolling, useNotificationStore } from '@/services/stores/notification-store';
import { useMobileUiStore } from './stores/mobile-ui-store';

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
  // In the layout so the prompt also covers the chromeless pages, which own the whole viewport.
  const { updateAvailable, dismiss } = useAppUpdate();

  const notificationsPath = APP_CONFIG.notificationsPath;
  const summary = useNotificationStore((state) => state.summary);
  const setTabBadge = useMobileUiStore((state) => state.setTabBadge);

  // The layout is where a phone owns the badge: it renders the tab bar, and the inbox entry lives
  // one level down inside the "More" page.
  useEffect(() => {
    if (!notificationsPath) return;
    startNotificationPolling();
  }, [notificationsPath]);

  useEffect(() => {
    if (!notificationsPath) return;
    setTabBadge(MOBILE_MORE_TAB_ID, summary.attention);
  }, [notificationsPath, summary, setTabBadge]);

  const chromeless = CHROMELESS_PREFIXES.some((prefix) => location.pathname.startsWith(prefix));
  const keyboardOpen = keyboardInset > 0;

  return (
    <div
      className="mobile-app"
      style={keyboardOpen ? { height: `calc(100dvh - ${keyboardInset}px)` } : undefined}
    >
      <ConnectionBanner />
      {MOBILE_TOP_ITEMS.length > 0 && (
        <div className="mobile-app-top-items">
          {MOBILE_TOP_ITEMS.map((TopItem, index) => (
            <TopItem key={index} />
          ))}
        </div>
      )}
      <main className="mobile-app-main">
        <Outlet />
      </main>
      {!chromeless && !keyboardOpen && <MobileTabBar />}
      {updateAvailable && <AppUpdateDialog onDismiss={dismiss} />}
    </div>
  );
};
