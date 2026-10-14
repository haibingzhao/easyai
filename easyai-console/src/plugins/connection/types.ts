/**
 * Connection plugin contract.
 *
 * Two facts, deliberately kept apart because they have different witnesses:
 * - **latency** belongs to the browser and is measured here (nothing else can see what the page waited for);
 * - **which relay the tunnel used** lives only in a tailscale daemon, so it arrives as evidence from
 *   whoever can read one — the backend (`server`), a future native shell (`device`), or not at all.
 */

/** How the page was reached. Read off `location.hostname`, never inferred. */
export type AccessKind = 'LOCAL' | 'LAN' | 'TAILNET' | 'PUBLIC';

export type Via = 'DIRECT' | 'RELAY' | 'UNKNOWN';

export type RelayKind = 'PEER' | 'DERP' | 'HTTP';

/** Who vouched for `via`. `latency` is an inference, the rest are observations. */
export type EvidenceSource = 'url' | 'server' | 'device' | 'latency' | 'none';

export type Confidence = 'certain' | 'probable' | 'none';

export interface RelayInfo {
  kind: RelayKind;
  /** Node name when the witness knows it, e.g. `aliyun-relay`. */
  name?: string;
  /** `host:port` of the relay hop. */
  address?: string;
  derpRegion?: string;
}

export interface RouteEvidence {
  host: string;
  ttfbMs?: number;
  /** `x-forwarded-for` chain as the backend saw it. */
  xff?: string[];
  remoteAddress?: string;
  viaHeader?: string;
  xForwardedHost?: string;
  /** The requesting node, as the server's tailscale daemon reports it. */
  peerAddress?: string;
  peerCurAddr?: string;
  peerRelay?: string;
  peerDerpRegion?: string;
  peerOnline?: boolean;
  serverHostname?: string;
  tailscaleAvailable?: boolean;
  serverInfoAt?: number;
}

export interface RouteSnapshot {
  access: AccessKind;
  via: Via;
  relay?: RelayInfo;
  source: EvidenceSource;
  confidence: Confidence;
  /** Caveat to show next to the label — a witness is not an oracle. */
  note?: string;
  evidence: RouteEvidence;
}

export type ProbeError = 'network' | 'timeout' | 'aborted' | 'unknown';

export interface ProbeSample {
  at: number;
  /** True whenever an HTTP status came back — a 401 still proves the path works. */
  ok: boolean;
  status?: number;
  ttfbMs?: number;
  totalMs?: number;
  /** TCP + TLS handshake, only meaningful on the sample that actually opened the connection. */
  connectMs?: number;
  dnsMs?: number;
  tlsMs?: number;
  coldConnection?: boolean;
  error?: ProbeError;
}

/** Wire shape of `GET /api/connection/info` (absent on backends without the adapter). */
export interface ConnectionInfoDto {
  observed?: {
    host?: string | null;
    scheme?: string | null;
    remoteAddress?: string | null;
    xForwardedFor?: string[] | null;
    xForwardedHost?: string | null;
    via?: string | null;
  } | null;
  route?: ServerRoute | null;
  relay?: {
    kind?: RelayKind | null;
    name?: string | null;
    address?: string | null;
    derpRegion?: string | null;
  } | null;
  peer?: {
    address?: string | null;
    curAddr?: string | null;
    peerRelay?: string | null;
    relay?: string | null;
    online?: boolean | null;
  } | null;
  server?: {
    hostname?: string | null;
    tailscaleAvailable?: boolean | null;
  } | null;
  generatedAt?: number | null;
}

/** Server-side vocabulary — `LAN`/`LOCAL` overlap with `AccessKind` on purpose. */
export type ServerRoute =
  | 'LOCAL'
  | 'LAN'
  | 'TAILNET_DIRECT'
  | 'RELAY_PEER'
  | 'RELAY_DERP'
  | 'HTTP_PROXY'
  | 'UNKNOWN';

export type Quality = 'unknown' | 'good' | 'fair' | 'slow' | 'bad' | 'down';
