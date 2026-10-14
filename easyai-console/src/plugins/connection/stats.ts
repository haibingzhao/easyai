import { CONNECTION_CONFIG as CFG } from './config';
import type { ProbeSample } from './types';

/** Samples the min/max are read over: long enough to be a trend, short enough to age out a stall. */
export const LATENCY_WINDOW_SAMPLES = 20;

export interface LatencyStats {
  ewmaMs: number | null;
  minMs: number | null;
  maxMs: number | null;
}

/** One EWMA step. A sample with no latency (network failure, timeout) leaves the average alone. */
export function nextEwma(previousMs: number | null, ttfbMs: number | undefined): number | null {
  if (ttfbMs === undefined) return previousMs;
  if (previousMs === null) return ttfbMs;
  return Math.round((CFG.ewmaAlpha * ttfbMs + (1 - CFG.ewmaAlpha) * previousMs) * 10) / 10;
}

/**
 * Rolls the sample history into the numbers the badge shows.
 *
 * Folded from the whole capped history rather than one step at a time, so a failed round cannot
 * double-count the previous success: samples without a latency simply do not exist in this fold.
 */
export function summariseLatency(samples: ProbeSample[]): LatencyStats {
  const okSamples = samples.filter((sample) => sample.ok && sample.ttfbMs !== undefined);
  const ewmaMs = okSamples.reduce<number | null>((acc, sample) => nextEwma(acc, sample.ttfbMs), null);
  const recent = okSamples.slice(-LATENCY_WINDOW_SAMPLES).map((sample) => sample.ttfbMs as number);

  return {
    ewmaMs,
    minMs: recent.length ? Math.min(...recent) : null,
    maxMs: recent.length ? Math.max(...recent) : null,
  };
}
