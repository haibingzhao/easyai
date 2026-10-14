import type { ProbeSample } from './types';

/** Samples every statistic is read over: long enough to be a trend, short enough to age out a stall. */
export const LATENCY_WINDOW_SAMPLES = 20;

export interface LatencyStats {
  /** Median of the window — the number the badge shows. */
  p50Ms: number | null;
  minMs: number | null;
  maxMs: number | null;
}

/**
 * Rolls the sample history into the numbers the badge shows.
 *
 * The headline is the window **median**, not a moving average: one 1.5s spike (a Wi-Fi sleep, a brief
 * relay fallback) must not hijack the reading for the next several samples, and must not trip the
 * "reported direct but latency looks relayed" guard either. Samples without a latency (network
 * failure, timeout) simply do not exist in this fold, so a failed round cannot double-count anything.
 */
export function summariseLatency(samples: ProbeSample[]): LatencyStats {
  const values = samples
    .filter((sample) => sample.ok && sample.ttfbMs !== undefined)
    .slice(-LATENCY_WINDOW_SAMPLES)
    .map((sample) => sample.ttfbMs as number);

  return {
    p50Ms: median(values),
    minMs: values.length ? Math.min(...values) : null,
    maxMs: values.length ? Math.max(...values) : null,
  };
}

function median(values: number[]): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = sorted.length >> 1;
  if (sorted.length % 2 === 1) return sorted[middle];
  return Math.round(((sorted[middle - 1] + sorted[middle]) / 2) * 10) / 10;
}
