import React, { useEffect } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { startConnectionProbe, useConnectionStore } from '@/plugins/connection/store';

/**
 * Reachability banner.
 *
 * A phone reaches this app through a tunnel (Tailscale) or a LAN address, so "the server is
 * gone" is a routine state rather than an edge case: the VPN drops, the laptop sleeps. Without
 * a banner every send just spins. The reading comes from the connection plugin's sampler, so
 * the badge, this banner and the profile row all agree instead of polling on their own.
 */
export const ConnectionBanner: React.FC = () => {
  const reachable = useConnectionStore((s) => s.reachable);
  const probing = useConnectionStore((s) => s.probing);
  const sampleNow = useConnectionStore((s) => s.sampleNow);

  useEffect(() => {
    startConnectionProbe();
  }, []);

  if (reachable) return null;

  return (
    <div className="flex items-center gap-2 px-3 bg-destructive/10 text-destructive text-xs border-b border-destructive/20" style={{ minHeight: 28 }}>
      <AlertTriangle className="w-4 h-4 shrink-0" />
      <span className="flex-1 min-w-0 truncate">{i18n('Network unavailable — check your connection or VPN')}</span>
      <button className="mobile-hit !min-w-0 !min-h-0 p-1" onClick={() => void sampleNow()} disabled={probing} aria-label={i18n('Retry')}>
        <RefreshCw className={`w-4 h-4 ${probing ? 'animate-spin' : ''}`} />
      </button>
    </div>
  );
};
