import { CONNECTION_CONFIG as CFG } from './config';
import type { ConnectionInfoDto, ProbeError, ProbeSample } from './types';

export interface ProbeResult {
  sample: ProbeSample;
  /** Parsed `GET /api/connection/info` body, or `null` when the probe ran on the fallback path. */
  info: ConnectionInfoDto | null;
  /** Set when the response was reachable but not the expected payload (404, 401, non-JSON). */
  infoError: string | null;
}

/**
 * Cache-busted absolute URL: a unique name per sample keeps `getEntriesByName` from returning an
 * older round, and an absolute href is what Safari matches on.
 */
export function buildProbeUrl(path: string): string {
  const separator = path.includes('?') ? '&' : '?';
  return new URL(`${path}${separator}_=${Date.now()}`, window.location.href).href;
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}

function resourceTiming(url: string): PerformanceResourceTiming | undefined {
  return performance
    .getEntriesByName(url, 'resource')
    .filter((entry): entry is PerformanceResourceTiming => entry instanceof PerformanceResourceTiming)
    .pop();
}

function classifyError(cause: unknown, abortReason: unknown): ProbeError {
  if (abortReason === 'timeout') return 'timeout';
  if (abortReason === 'aborted') return 'aborted';
  if (cause instanceof DOMException && cause.name === 'AbortError') return 'aborted';
  return 'network';
}

/**
 * One measured round trip.
 *
 * The headline number comes from `performance.now()` around `fetch`, which resolves at response
 * headers — that is the delay the page actually feels. Resource Timing is consulted only for the
 * DNS/TCP/TLS split, and is allowed to be missing: the browser's timing buffer drops entries when
 * full, and cross-buffer eviction must never cost us the latency reading itself.
 */
export async function probeOnce(url: string, parseJson: boolean): Promise<ProbeResult> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort('timeout'), CFG.timeoutMs);
  const startedAt = performance.now();

  let status: number | undefined;
  let text: string | null = null;

  try {
    const res = await fetch(url, {
      method: 'GET',
      cache: 'no-store',
      credentials: 'same-origin',
      signal: controller.signal,
    });
    const headersAt = performance.now() - startedAt;
    status = res.status;
    try {
      text = await res.text();
    } catch {
      // A truncated body still means the path answered.
    }
    clearTimeout(timer);
    const totalAt = performance.now() - startedAt;
    return finish({ url, status, headersAt, totalAt, text, parseJson });
  } catch (cause) {
    clearTimeout(timer);
    const error = classifyError(cause, controller.signal.reason);
    return {
      sample: { at: Date.now(), ok: false, error, ttfbMs: round(performance.now() - startedAt) },
      info: null,
      infoError: error,
    };
  }
}

function finish(input: {
  url: string;
  status: number;
  headersAt: number;
  totalAt: number;
  text: string | null;
  parseJson: boolean;
}): ProbeResult {
  const entry = resourceTiming(input.url);
  const info = input.parseJson ? parseInfo(input.status, input.text) : { info: null, infoError: null };

  // A truncated or evicted timing entry reports zeros rather than nothing, and a zero TTFB would read
  // as "instant" — fall back to the wall clock whenever the timing row is not usable.
  const networkTtfb = entry && entry.responseStart > 0 ? round(entry.responseStart - entry.startTime) : undefined;
  const networkTotal = entry && entry.duration > 0 ? round(entry.duration) : undefined;

  const sample: ProbeSample = {
    at: Date.now(),
    ok: true,
    status: input.status,
    ttfbMs: networkTtfb ?? round(input.headersAt),
    totalMs: networkTotal ?? round(input.totalAt),
  };

  if (entry) {
    const dns = entry.domainLookupEnd - entry.domainLookupStart;
    const handshake = entry.connectEnd - entry.connectStart;
    const secureStart = entry.secureConnectionStart;
    sample.coldConnection = dns > 0 || handshake > 0;
    if (sample.coldConnection) {
      sample.dnsMs = round(dns);
      sample.connectMs = round(handshake);
      sample.tlsMs = secureStart > 0 ? round(entry.connectEnd - secureStart) : 0;
    }
  }

  return { sample, info: info.info, infoError: info.infoError };
}

function parseInfo(status: number, text: string | null): { info: ConnectionInfoDto | null; infoError: string | null } {
  if (status === 404) return { info: null, infoError: 'missing-endpoint' };
  if (status === 401 || status === 403) return { info: null, infoError: 'unauthorized' };
  if (!text) return { info: null, infoError: 'empty-body' };
  try {
    return { info: JSON.parse(text) as ConnectionInfoDto, infoError: null };
  } catch {
    return { info: null, infoError: 'bad-json' };
  }
}
