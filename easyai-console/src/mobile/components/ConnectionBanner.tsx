import React, { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { setupService } from '@/services/setup-service';
import { i18n } from '@/utils/i18n';

/**
 * Reachability banner.
 *
 * A phone reaches this app through a tunnel (Tailscale) or a LAN address, so "the server is
 * gone" is a routine state rather than an edge case: the VPN drops, the laptop sleeps. Without
 * a banner every send just spins. Probing `/api/setup/status` is the cheapest already-authenticated
 * call that answers through the same path chat uses.
 */
export const ConnectionBanner: React.FC = () => {
  const [unreachable, setUnreachable] = useState(false);
  const [probing, setProbing] = useState(false);

  const probe = useCallback(async () => {
    if (!navigator.onLine) {
      setUnreachable(true);
      return;
    }
    setProbing(true);
    try {
      await setupService.getStatus();
      setUnreachable(false);
    } catch {
      setUnreachable(true);
    } finally {
      setProbing(false);
    }
  }, []);

  useEffect(() => {
    void probe();
    const onOnline = () => void probe();
    const onOffline = () => setUnreachable(true);
    const onVisible = () => {
      if (document.visibilityState === 'visible') void probe();
    };
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [probe]);

  if (!unreachable) return null;

  return (
    <div className="flex items-center gap-2 px-3 bg-destructive/10 text-destructive text-xs border-b border-destructive/20" style={{ minHeight: 28 }}>
      <AlertTriangle className="w-4 h-4 shrink-0" />
      <span className="flex-1 min-w-0 truncate">{i18n('Network unavailable — check your connection or VPN')}</span>
      <button className="mobile-hit !min-w-0 !min-h-0 p-1" onClick={() => void probe()} disabled={probing} aria-label={i18n('Retry')}>
        <RefreshCw className={`w-4 h-4 ${probing ? 'animate-spin' : ''}`} />
      </button>
    </div>
  );
};
