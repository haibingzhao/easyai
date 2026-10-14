import React, { useEffect, useRef, useState } from 'react';
import { i18n } from '@/utils/i18n';
import { CONNECTION_CONFIG as CFG } from './config';
import { ConnectionReport } from './ConnectionReport';
import { msText, qualityDot, routeText } from './labels';
import { startConnectionProbe, useConnectionStatus } from './store';

/**
 * Desktop badge. Hosts mount it with one line:
 * `TOPBAR_EXTRA_ITEMS.push(ConnectionPill)`.
 */
export const ConnectionPill: React.FC = () => {
  const { route, quality, ewmaMs, reachable } = useConnectionStatus();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    startConnectionProbe();
  }, []);

  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (!CFG.enabled || route.access === 'LOCAL') return null;

  const summary = `${routeText(route)} · ${msText(ewmaMs, reachable)}`;

  return (
    <div className="relative shrink-0" ref={containerRef}>
      <button
        className="flex items-center gap-1.5 h-7 px-2 rounded-md border border-border bg-muted/40 hover:bg-muted transition-colors"
        onClick={() => setOpen((value) => !value)}
        title={summary}
        aria-label={i18n('Network diagnostics')}
        aria-expanded={open}
      >
        <span className={`w-2 h-2 rounded-full shrink-0 ${qualityDot(quality)}`} />
        <span className="text-xs text-muted-foreground whitespace-nowrap">{routeText(route)}</span>
        <span
          className={`text-xs tabular-nums ${quality === 'bad' || quality === 'down' ? 'text-destructive' : 'text-muted-foreground'}`}
        >
          {msText(ewmaMs, reachable)}
        </span>
      </button>

      {open && (
        <div className="absolute right-0 top-full mt-1 w-[360px] max-h-[70vh] overflow-y-auto rounded-lg border border-border bg-background shadow-lg p-3 z-50">
          <ConnectionReport />
        </div>
      )}
    </div>
  );
};
