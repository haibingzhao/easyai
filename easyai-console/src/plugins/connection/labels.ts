import { i18n } from '@/utils/i18n';
import { CONNECTION_CONFIG as CFG } from './config';
import type { AccessKind, Confidence, EvidenceSource, Quality, RouteSnapshot } from './types';

/**
 * The tailnet reports a hop as an address; the display maps a known node to what the user calls it.
 * Naming stays generic here (`Peer relay`) so a second relay needs no code change.
 */
function peerRelayText(name: string | undefined, address: string | undefined): string {
  if (name) {
    const display = /aliyun/i.test(name) ? i18n('Aliyun relay') : name;
    return `${i18n('Peer relay')} · ${display}`;
  }
  return address ? `${i18n('Peer relay')} · ${address}` : i18n('Peer relay');
}

export function routeText(route: RouteSnapshot): string {
  if (route.access === 'LOCAL') return i18n('Local');
  if (route.via === 'UNKNOWN') return i18n('Route unknown');
  if (route.via === 'DIRECT') {
    return route.access === 'LAN' ? i18n('LAN direct') : i18n('Direct');
  }
  const relay = route.relay;
  switch (relay?.kind) {
    case 'DERP':
      return relay.derpRegion ? `${i18n('Tailscale relay')} · ${relay.derpRegion}` : i18n('Tailscale relay');
    case 'HTTP':
      return relay.name ? `${i18n('HTTP reverse proxy')} · ${relay.name}` : i18n('HTTP reverse proxy');
    case 'PEER':
      return peerRelayText(relay.name, relay.address);
    default:
      return i18n('Relay');
  }
}

const ACCESS_LABEL: Record<AccessKind, string> = {
  LOCAL: 'Local',
  LAN: 'LAN direct',
  TAILNET: 'Tailnet',
  PUBLIC: 'Public host',
};

export function accessText(access: AccessKind): string {
  return i18n(ACCESS_LABEL[access]);
}

const SOURCE_TEXT: Record<EvidenceSource, string> = {
  url: 'Access URL',
  server: 'Server tailscale view',
  device: 'This device',
  latency: 'Latency inference',
  none: 'No sample yet',
};

export function sourceText(source: EvidenceSource, confidence: Confidence): string {
  const base = i18n(SOURCE_TEXT[source]);
  if (source === 'url') return base;
  return confidence === 'probable' ? `${base} · ${i18n('approximate')}` : base;
}

export function noteText(note: string | undefined): string | undefined {
  if (note === 'serverView') return i18n('Server-side view of the link; WireGuard paths are usually symmetric.');
  if (note === 'relayTypeInvisible') return i18n('Latency proves it is not direct, not which relay it used.');
  return undefined;
}

export function qualityOf(
  reachable: boolean,
  p50Ms: number | null,
  lastError?: string,
  lastStatus?: number,
): Quality {
  if (!reachable || lastError === 'network') return 'down';
  // A probe that hung is the worst reading the path can produce, even though the socket is alive.
  if (lastError === 'timeout') return 'bad';
  if (lastStatus !== undefined && lastStatus >= 500) return 'bad';
  // Nothing measured yet is not "fair": the badge must not claim a quality it has no evidence for.
  if (p50Ms === null) return 'unknown';
  if (p50Ms <= CFG.quality.goodMs) return 'good';
  if (p50Ms <= CFG.quality.fairMs) return 'fair';
  if (p50Ms <= CFG.quality.slowMs) return 'slow';
  return 'bad';
}

const DOT: Record<Quality, string> = {
  good: 'bg-green-500',
  fair: 'bg-amber-500',
  slow: 'bg-orange-500',
  bad: 'bg-destructive',
  down: 'bg-destructive',
  unknown: 'bg-muted-foreground/40',
};

export function qualityDot(quality: Quality): string {
  return DOT[quality];
}

export function msText(value: number | null | undefined, reachable = true): string {
  if (!reachable) return i18n('Unreachable');
  if (value === null || value === undefined) return '—';
  return `${Math.round(value)}ms`;
}

/** A DIRECT reading riding on a relay-shaped latency is worth a line of doubt. */
export function isContradictory(route: RouteSnapshot, p50Ms: number | null): boolean {
  return route.via === 'DIRECT' && route.access !== 'LAN' && p50Ms !== null && p50Ms > CFG.suspectTtfbMs;
}
