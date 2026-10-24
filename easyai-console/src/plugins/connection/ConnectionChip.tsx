import React, { useEffect, useState } from 'react';
import { i18n } from '@/utils/i18n';
import { CONNECTION_CONFIG as CFG } from './config';
import { ConnectionSheet } from './ConnectionSheet';
import { msText, qualityDot, routeText } from './labels';
import { startConnectionProbe, useConnectionStatus } from './store';

/**
 * Phone badge: dot colour plus the latency number, and the relay word only when the path is
 * actually relayed — a header this crowded should spend its width on what needs acting on.
 *
 * `compact` is the host-status-strip variant: it also shows the route word and drops the
 * 44pt hit area, because the strip is a persistent indicator rather than a page action.
 * The default branch is the unchanged header chip.
 */
export const ConnectionChip: React.FC<{ compact?: boolean }> = ({ compact = false }) => {
  const { route, quality, p50Ms, reachable } = useConnectionStatus();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    startConnectionProbe();
  }, []);

  if (!CFG.enabled || (route.access === 'LOCAL' && !CFG.showOnLocal)) return null;

  return (
    <>
      <button
        className={
          compact
            ? 'flex items-center gap-1 rounded-md border border-border bg-muted/40 px-2 py-0.5 shrink-0'
            : 'mobile-hit !min-w-0 p-1.5 flex items-center gap-1 shrink-0'
        }
        onClick={() => setOpen(true)}
        aria-label={i18n('Network diagnostics')}
      >
        <span className={`w-2 h-2 rounded-full shrink-0 ${qualityDot(quality)}`} />
        {compact
          ? <span className="text-[11px] text-muted-foreground whitespace-nowrap">{routeText(route)}</span>
          : route.via === 'RELAY' && <span className="text-[11px] text-muted-foreground">{i18n('Relay')}</span>}
        <span
          className={`text-[11px] tabular-nums ${quality === 'bad' || quality === 'down' ? 'text-destructive' : 'text-muted-foreground'}`}
        >
          {msText(p50Ms, reachable)}
        </span>
      </button>

      <ConnectionSheet open={open} onClose={() => setOpen(false)} />
    </>
  );
};
