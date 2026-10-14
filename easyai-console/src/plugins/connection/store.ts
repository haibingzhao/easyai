import { create } from 'zustand';
import { CONNECTION_CONFIG as CFG } from './config';
import { qualityOf } from './labels';
import { buildProbeUrl, probeOnce } from './probe';
import { classifyAccess, mergeRoute, witnessIdentity } from './route';
import { summariseLatency } from './stats';
import type { ConnectionInfoDto, ProbeSample, RouteSnapshot } from './types';

const access = classifyAccess(window.location.hostname);
const host = window.location.host;

export interface ConnectionState {
  samples: ProbeSample[];
  latest: ProbeSample | null;
  /** Median TTFB over the recent window — the number shown in the badge. */
  p50Ms: number | null;
  minMs: number | null;
  maxMs: number | null;
  /** False only when the path itself failed; an HTTP 401/404 is still a working connection. */
  reachable: boolean;
  probing: boolean;
  route: RouteSnapshot;
  serverInfo: ConnectionInfoDto | null;
  serverInfoError: string | null;
  usingFallbackPath: boolean;
  sampleNow: () => Promise<void>;
}

export const useConnectionStore = create<ConnectionState>((commit, get) => ({
  samples: [],
  latest: null,
  p50Ms: null,
  minMs: null,
  maxMs: null,
  reachable: true,
  probing: false,
  route: mergeRoute({ access, host, info: null, infoError: null }),
  serverInfo: null,
  serverInfoError: null,
  usingFallbackPath: false,
  sampleNow: async () => {
    if (get().probing) return;
    commit({ probing: true });
    try {
      if (typeof navigator !== 'undefined' && navigator.onLine === false) {
        applySample(commit, get, {
          sample: { at: Date.now(), ok: false, error: 'network' },
          info: null,
          infoError: 'offline',
        });
        return;
      }
      const usePrimary = !get().usingFallbackPath;
      const url = buildProbeUrl(usePrimary ? CFG.probePath : CFG.fallbackProbePath);
      const result = await probeOnce(url, usePrimary);

      // A 404 here means this backend has no adapter — but the round trip still measured the path, so
      // record it and switch the probe over to the endpoint that does exist.
      if (result.infoError === 'missing-endpoint') {
        applySample(commit, get, result);
        commit({ usingFallbackPath: true });
        return;
      }
      applySample(commit, get, result);
    } finally {
      commit({ probing: false });
    }
  },
}));

type Commit = (partial: Partial<ConnectionState>) => void;

type ProbeOutcome = { sample: ProbeSample; info: ConnectionInfoDto | null; infoError: string | null };

function applySample(commit: Commit, get: () => ConnectionState, result: ProbeOutcome) {
  const previous = get();

  const build = (samples: ProbeSample[]) => {
    const stats = summariseLatency(samples);
    const route = mergeRoute({
      access,
      host,
      ttfbMs: stats.p50Ms ?? result.sample.ttfbMs,
      info: result.info ?? previous.serverInfo,
      infoError: result.infoError,
    });
    return { samples, stats, route };
  };

  let next = build([...previous.samples, result.sample].slice(-CFG.historyLength));
  const now = witnessIdentity(next.route);
  const before = witnessIdentity(previous.route);
  if (now !== null && now !== before) {
    // The tunnel switched path (direct ↔ relay): samples from the old path would drag the average
    // across the flip, which is exactly the blend that makes a badge untrustworthy.
    next = build([result.sample]);
  }

  commit({
    samples: next.samples,
    latest: result.sample,
    ...next.stats,
    reachable: result.sample.error !== 'network',
    route: next.route,
    serverInfo: result.info ?? previous.serverInfo,
    // A fallback-path probe parses no body, so it reports neither info nor an error: it cannot clear
    // a verdict it never asked for. Only a round that actually returned the witness clears it.
    serverInfoError: result.infoError ?? (result.info !== null ? null : previous.serverInfoError),
  });
}

let started = false;
let timer: number | null = null;

function nextDelay(): number {
  const base = typeof document !== 'undefined' && document.hidden ? CFG.intervalHiddenMs : CFG.intervalVisibleMs;
  const spread = base * CFG.jitterRatio;
  return Math.max(2000, Math.round(base + (Math.random() * 2 - 1) * spread));
}

function schedule() {
  if (timer !== null) clearTimeout(timer);
  timer = window.setTimeout(() => {
    void useConnectionStore
      .getState()
      .sampleNow()
      .finally(schedule);
  }, nextDelay());
}

/**
 * Kick-start the sampler. Idempotent by design: React StrictMode mounts every effect twice in dev,
 * and a status monitor has no unmount story — the page owns the reading for as long as it is open.
 */
export function startConnectionProbe(): void {
  if (started || !CFG.enabled) return;
  started = true;

  window.addEventListener('online', () => {
    void useConnectionStore.getState().sampleNow();
    schedule();
  });
  window.addEventListener('offline', () => {
    useConnectionStore.setState({ reachable: false });
    schedule();
  });
  // iOS suspends fetches in the background, so the reading on return is stale by whatever the
  // device slept for; re-probe as soon as the page is visible again.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void useConnectionStore.getState().sampleNow();
    schedule();
  });

  void useConnectionStore.getState().sampleNow().finally(schedule);
}

export function useConnectionStatus() {
  const reachable = useConnectionStore((s) => s.reachable);
  const p50Ms = useConnectionStore((s) => s.p50Ms);
  const minMs = useConnectionStore((s) => s.minMs);
  const maxMs = useConnectionStore((s) => s.maxMs);
  const latest = useConnectionStore((s) => s.latest);
  const probing = useConnectionStore((s) => s.probing);
  const route = useConnectionStore((s) => s.route);
  const samples = useConnectionStore((s) => s.samples);
  const serverInfo = useConnectionStore((s) => s.serverInfo);
  const serverInfoError = useConnectionStore((s) => s.serverInfoError);
  const usingFallbackPath = useConnectionStore((s) => s.usingFallbackPath);
  const sampleNow = useConnectionStore((s) => s.sampleNow);

  return {
    route,
    quality: qualityOf(reachable, p50Ms, latest?.error, latest?.status),
    reachable,
    p50Ms,
    minMs,
    maxMs,
    latest,
    samples,
    probing,
    serverInfo,
    serverInfoError,
    usingFallbackPath,
    sampleNow,
  };
}
