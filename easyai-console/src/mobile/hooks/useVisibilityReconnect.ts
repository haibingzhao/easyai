import { useEffect } from 'react';

/**
 * Run `onVisible` every time the page becomes visible again.
 *
 * iOS suspends in-flight `fetch` as soon as the app leaves the foreground, so a stream that was
 * mid-response is simply gone when the user comes back — the recovery poll must be kicked
 * immediately rather than waiting out its 3s tick.
 */
export function useVisibilityReconnect(onVisible: () => void): void {
  useEffect(() => {
    const handler = () => {
      if (document.visibilityState === 'visible') onVisible();
    };
    document.addEventListener('visibilitychange', handler);
    return () => document.removeEventListener('visibilitychange', handler);
  }, [onVisible]);
}
