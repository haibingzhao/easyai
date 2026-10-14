import React, { useEffect, useState } from 'react';
import { i18n } from '@/utils/i18n';
import { CONNECTION_CONFIG as CFG } from './config';
import { ConnectionSheet } from './ConnectionSheet';
import { msText, qualityDot } from './labels';
import { startConnectionProbe, useConnectionStatus } from './store';

/**
 * Phone badge: dot colour plus the latency number, and the relay word only when the path is
 * actually relayed — a header this crowded should spend its width on what needs acting on.
 */
export const ConnectionChip: React.FC = () => {
  const { route, quality, ewmaMs, reachable } = useConnectionStatus();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    startConnectionProbe();
  }, []);

  if (!CFG.enabled || route.access === 'LOCAL') return null;

  return (
    <>
      <button
        className="mobile-hit !min-w-0 p-1.5 flex items-center gap-1 shrink-0"
        onClick={() => setOpen(true)}
        aria-label={i18n('Network diagnostics')}
      >
        <span className={`w-2 h-2 rounded-full shrink-0 ${qualityDot(quality)}`} />
        {route.via === 'RELAY' && <span className="text-[11px] text-muted-foreground">{i18n('Relay')}</span>}
        <span
          className={`text-[11px] tabular-nums ${quality === 'bad' || quality === 'down' ? 'text-destructive' : 'text-muted-foreground'}`}
        >
          {msText(ewmaMs, reachable)}
        </span>
      </button>

      <ConnectionSheet open={open} onClose={() => setOpen(false)} />
    </>
  );
};
