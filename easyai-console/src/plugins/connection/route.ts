import { CONNECTION_CONFIG as CFG } from './config';
import type {
  AccessKind,
  ConnectionInfoDto,
  RelayInfo,
  RouteEvidence,
  RouteSnapshot,
  ServerRoute,
  Via,
} from './types';

const LOOPBACK = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);
const TS_NET_SUFFIX = '.ts.net';

function isCgnat(ip: string): boolean {
  // Tailscale allocates from 100.64.0.0/10 (carrier-grade NAT range).
  if (!ip.startsWith('100.')) return false;
  const second = Number(ip.split('.')[1]);
  return second >= 64 && second <= 127;
}

function isPrivateIp(ip: string): boolean {
  if (ip.startsWith('10.') || ip.startsWith('192.168.')) return true;
  if (ip.startsWith('172.')) {
    const second = Number(ip.split('.')[1]);
    return second >= 16 && second <= 31;
  }
  return false;
}

/**
 * Which channel the page was loaded through — a fact about the URL, so it needs no probing and is
 * always available, including before the first sample and on the login screen.
 */
export function classifyAccess(hostname: string): AccessKind {
  const host = hostname.toLowerCase();
  if (LOOPBACK.has(host)) return 'LOCAL';
  if (host.endsWith(TS_NET_SUFFIX) || isCgnat(host)) return 'TAILNET';
  if (isPrivateIp(host) || host.endsWith('.local')) return 'LAN';
  return 'PUBLIC';
}

/** `47.121.24.5:40000:vni:23` → `{ host: '47.121.24.5', address: '47.121.24.5:40000' }`. */
export function parseRelayAddress(raw: string): { host: string; address: string } {
  const parts = raw.trim().split(':');
  if (raw.trim().startsWith('[')) {
    const end = raw.indexOf(']');
    const host = raw.slice(1, end);
    return { host, address: raw.slice(1, end + 6).replace(/:vni.*$/, '') };
  }
  const host = parts[0];
  const port = parts[1] && /^\d+$/.test(parts[1]) ? parts[1] : undefined;
  return { host, address: port ? `${host}:${port}` : host };
}

/** Configured name for a relay host, so an unnamed hop still reads as the thing the user knows. */
export function labelRelayHost(host: string | undefined): string | undefined {
  if (!host) return undefined;
  return CFG.relayLabels[host];
}

function relayFromServer(info: ConnectionInfoDto): RelayInfo | undefined {
  const relay = info.relay;
  const peerRelay = info.peer?.peerRelay;
  if (relay?.kind || relay?.name || relay?.address || relay?.derpRegion) {
    const parsed = relay?.address ? parseRelayAddress(relay.address) : undefined;
    return {
      kind: relay?.kind ?? (parsed ? 'PEER' : relay?.derpRegion ? 'DERP' : 'HTTP'),
      name: relay?.name ?? (parsed ? labelRelayHost(parsed.host) : undefined),
      address: relay?.address ?? undefined,
      derpRegion: relay?.derpRegion ?? undefined,
    };
  }
  if (!peerRelay) return undefined;
  const parsed = parseRelayAddress(peerRelay);
  return { kind: 'PEER', name: labelRelayHost(parsed.host), address: parsed.address };
}

const SERVER_VIA: Partial<Record<ServerRoute, Via>> = {
  LOCAL: 'DIRECT',
  LAN: 'DIRECT',
  TAILNET_DIRECT: 'DIRECT',
  RELAY_PEER: 'RELAY',
  RELAY_DERP: 'RELAY',
  HTTP_PROXY: 'RELAY',
};

const SERVER_RELAY_KIND: Partial<Record<ServerRoute, RelayInfo['kind']>> = {
  RELAY_PEER: 'PEER',
  RELAY_DERP: 'DERP',
  HTTP_PROXY: 'HTTP',
};

function inferFromLatency(access: AccessKind, ttfbMs?: number): Via {
  if (access === 'LOCAL' || access === 'LAN') return 'DIRECT';
  if (ttfbMs === undefined) return 'UNKNOWN';
  // A hop through the Aliyun relay or a DERP region always costs more than this floor; a real
  // punched-direct path between two machines in one city sits under it.
  return ttfbMs <= CFG.directMaxTtfbMs ? 'DIRECT' : 'RELAY';
}

export interface MergeInput {
  access: AccessKind;
  ttfbMs?: number;
  info: ConnectionInfoDto | null;
  infoError: string | null;
  host: string;
}

/**
 * Fold the witnesses into one route reading. A tailscale-backed answer wins over the URL and over
 * latency; the URL wins over latency for `LOCAL`/`LAN` because those are literals, not guesses.
 */
export function mergeRoute(input: MergeInput): RouteSnapshot {
  const evidence: RouteEvidence = { host: input.host, ttfbMs: input.ttfbMs };
  const observed = input.info?.observed;
  if (observed) {
    evidence.xff = observed.xForwardedFor ?? undefined;
    evidence.remoteAddress = observed.remoteAddress ?? undefined;
    evidence.viaHeader = observed.via ?? undefined;
    evidence.xForwardedHost = observed.xForwardedHost ?? undefined;
  }
  const peer = input.info?.peer;
  if (peer) {
    evidence.peerAddress = peer.address ?? undefined;
    evidence.peerCurAddr = peer.curAddr ?? undefined;
    evidence.peerRelay = peer.peerRelay ?? undefined;
    evidence.peerDerpRegion = peer.relay ?? undefined;
    evidence.peerOnline = peer.online ?? undefined;
  }
  if (input.info?.server) {
    evidence.serverHostname = input.info.server.hostname ?? undefined;
    evidence.tailscaleAvailable = input.info.server.tailscaleAvailable ?? undefined;
  }
  if (input.info?.generatedAt) evidence.serverInfoAt = input.info.generatedAt;

  const route = input.info?.route;
  const serverVia = route ? SERVER_VIA[route] : undefined;
  if (input.info && serverVia) {
    const relay = relayFromServer(input.info) ?? (route ? relayFromRouteKind(route, evidence) : undefined);
    return {
      access: input.access,
      via: serverVia,
      relay: serverVia === 'RELAY' ? relay : undefined,
      source: 'server',
      confidence: 'probable',
      note: 'serverView',
      evidence,
    };
  }

  if (input.access === 'LOCAL' || input.access === 'LAN') {
    return {
      access: input.access,
      via: 'DIRECT',
      source: 'url',
      confidence: 'certain',
      evidence,
    };
  }

  const via = inferFromLatency(input.access, input.ttfbMs);
  return {
    access: input.access,
    via,
    source: via === 'UNKNOWN' ? 'none' : 'latency',
    confidence: via === 'UNKNOWN' ? 'none' : 'probable',
    note: via === 'RELAY' ? 'relayTypeInvisible' : undefined,
    evidence,
  };
}

function relayFromRouteKind(route: ServerRoute, evidence: RouteEvidence): RelayInfo | undefined {
  const kind = SERVER_RELAY_KIND[route];
  if (!kind) return undefined;
  if (kind === 'DERP') return { kind, derpRegion: evidence.peerDerpRegion };
  if (kind === 'PEER') {
    const parsed = evidence.peerRelay ? parseRelayAddress(evidence.peerRelay) : undefined;
    return { kind, address: parsed?.address, name: labelRelayHost(parsed?.host) };
  }
  return { kind, name: evidence.xForwardedHost ?? evidence.viaHeader ?? undefined };
}
