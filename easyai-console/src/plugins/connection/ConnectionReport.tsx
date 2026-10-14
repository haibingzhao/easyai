import React from 'react';
import { RefreshCw } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { CONNECTION_CONFIG as CFG } from './config';
import { accessText, isContradictory, msText, noteText, qualityDot, routeText, sourceText } from './labels';
import { useConnectionStatus } from './store';

const Row: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
  <div className="flex items-baseline justify-between gap-3 py-1">
    <span className="text-xs text-muted-foreground shrink-0">{label}</span>
    <span className="text-xs text-right break-all min-w-0">{value}</span>
  </div>
);

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <div className="pt-2 mt-2 border-t border-border first:border-0 first:mt-0 first:pt-0">
    <div className="text-[11px] uppercase tracking-wide text-muted-foreground mb-1">{title}</div>
    {children}
  </div>
);

function timeText(ts: number): string {
  return new Date(ts).toLocaleTimeString();
}

/**
 * The diagnostic body, shared by the desktop dropdown and the mobile sheet: latency is always ours,
 * the witness block is only rendered when a tailscale daemon actually answered, so an absent backend
 * never looks like a broken reading.
 */
export const ConnectionReport: React.FC = () => {
  const {
    route,
    quality,
    reachable,
    ewmaMs,
    minMs,
    maxMs,
    latest,
    samples,
    probing,
    serverInfoError,
    usingFallbackPath,
    sampleNow,
  } = useConnectionStatus();

  const evidence = route.evidence;
  const recent = samples.slice(-5).reverse();

  return (
    <div className="space-y-0">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${qualityDot(quality)}`} />
          <span className="text-sm font-medium truncate">{routeText(route)}</span>
        </div>
        <button
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors shrink-0"
          onClick={() => void sampleNow()}
          disabled={probing}
        >
          <RefreshCw className={`w-3.5 h-3.5 ${probing ? 'animate-spin' : ''}`} />
          {i18n('Probe again')}
        </button>
      </div>

      <div className="text-[11px] text-muted-foreground mt-1">
        {i18n('Evidence')}: {sourceText(route.source, route.confidence)}
      </div>
      {noteText(route.note) && <p className="text-[11px] leading-snug text-muted-foreground mt-1">{noteText(route.note)}</p>}
      {isContradictory(route, ewmaMs) && (
        <p className="text-[11px] leading-snug text-destructive mt-1">{i18n('Reported direct, but latency is relay-shaped — check for an unnoticed hop.')}</p>
      )}

      <Section title={i18n('Latency')}>
        <Row label={i18n('Average')} value={<span className="tabular-nums">{msText(ewmaMs, reachable)}</span>} />
        <Row label={i18n('Last')} value={<span className="tabular-nums">{msText(latest?.ttfbMs, reachable)}</span>} />
        <Row label={i18n('Min')} value={<span className="tabular-nums">{msText(minMs, reachable)}</span>} />
        <Row label={i18n('Max')} value={<span className="tabular-nums">{msText(maxMs, reachable)}</span>} />
        {recent.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-1">
            {recent.map((s) => (
              <span
                key={s.at}
                className={`text-[10px] tabular-nums px-1.5 py-0.5 rounded ${
                  s.ok ? 'bg-muted text-muted-foreground' : 'bg-destructive/10 text-destructive'
                }`}
              >
                {s.ok ? `${Math.round(s.ttfbMs ?? 0)}ms` : s.error ?? i18n('Error')}
              </span>
            ))}
          </div>
        )}
        {latest?.coldConnection && (
          <Row
            label={i18n('Connection phases (first sample only)')}
            value={
              <span className="tabular-nums">
                DNS {msText(latest.dnsMs)} · TLS {msText(latest.tlsMs)} · TCP+TLS {msText(latest.connectMs)} · TTFB {msText(latest.ttfbMs)}
              </span>
            }
          />
        )}
      </Section>

      <Section title={i18n('Access')}>
        <Row label={i18n('Host')} value={<span className="break-all">{evidence.host}</span>} />
        <Row label={i18n('Channel')} value={accessText(route.access)} />
        <Row
          label={i18n('Probe endpoint')}
          value={<span className="break-all">{usingFallbackPath ? CFG.fallbackProbePath : CFG.probePath}</span>}
        />
        <Row label={i18n('Witness')} value={serverInfoError ?? i18n('Not reported')} />
      </Section>

      {(evidence.xff || evidence.peerRelay || evidence.peerDerpRegion || evidence.serverHostname) && (
        <Section title={i18n('Server observed')}>
          {evidence.remoteAddress && <Row label="remote" value={<span className="tabular-nums">{evidence.remoteAddress}</span>} />}
          {evidence.xff && evidence.xff.length > 0 && (
            <Row label="x-forwarded-for" value={<span className="tabular-nums">{evidence.xff.join(', ')}</span>} />
          )}
          {evidence.xForwardedHost && <Row label="x-forwarded-host" value={evidence.xForwardedHost} />}
          {evidence.viaHeader && <Row label="via" value={evidence.viaHeader} />}
          {evidence.peerAddress && <Row label="peer" value={<span className="tabular-nums">{evidence.peerAddress}</span>} />}
          {evidence.peerCurAddr && <Row label="curAddr" value={<span className="tabular-nums">{evidence.peerCurAddr}</span>} />}
          {evidence.peerRelay && <Row label="peerRelay" value={<span className="tabular-nums">{evidence.peerRelay}</span>} />}
          {evidence.peerDerpRegion && <Row label="derp" value={evidence.peerDerpRegion} />}
          {evidence.serverHostname && <Row label={i18n('Server')} value={evidence.serverHostname} />}
          {evidence.tailscaleAvailable === false && (
            <Row label="tailscale" value={<span className="text-destructive">{i18n('Not available')}</span>} />
          )}
          {evidence.serverInfoAt && <Row label={i18n('At')} value={<span className="tabular-nums">{timeText(evidence.serverInfoAt)}</span>} />}
        </Section>
      )}
    </div>
  );
};
