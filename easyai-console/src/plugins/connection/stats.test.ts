import { describe, expect, it } from 'vitest';
import { LATENCY_WINDOW_SAMPLES, nextEwma, summariseLatency } from './stats';
import type { ProbeSample } from './types';

const ok = (ttfbMs: number, at = 0): ProbeSample => ({ at, ok: true, ttfbMs });
const failed = (at = 0): ProbeSample => ({ at, ok: false, error: 'network' });

describe('nextEwma', () => {
  it('seeds from the first sample and then weights the new one at alpha', () => {
    expect(nextEwma(null, 120)).toBe(120);
    expect(nextEwma(100, 200)).toBe(130);
  });

  it('keeps the previous average when the sample carries no latency', () => {
    expect(nextEwma(130, undefined)).toBe(130);
  });
});

describe('summariseLatency', () => {
  it('reports nothing before a successful sample', () => {
    expect(summariseLatency([])).toEqual({ ewmaMs: null, minMs: null, maxMs: null });
    expect(summariseLatency([failed()])).toEqual({ ewmaMs: null, minMs: null, maxMs: null });
  });

  it('does not double-count the previous success when the newest round failed', () => {
    const before = summariseLatency([ok(100, 1), ok(200, 2)]);
    const after = summariseLatency([ok(100, 1), ok(200, 2), failed(3)]);

    expect(after.ewmaMs).toBe(before.ewmaMs);
    expect(after.ewmaMs).toBe(130);
    expect(after.minMs).toBe(100);
    expect(after.maxMs).toBe(200);
  });

  it('reads min and max over the capped window only', () => {
    const samples = [ok(5000, 0), ...Array.from({ length: LATENCY_WINDOW_SAMPLES }, (_, index) => ok(50, index + 1))];
    const stats = summariseLatency(samples);

    expect(stats.minMs).toBe(50);
    expect(stats.maxMs).toBe(50);
  });

  it('tracks a stall as a high maximum', () => {
    const stats = summariseLatency([ok(40, 1), ok(42, 2), ok(900, 3)]);
    expect(stats.maxMs).toBe(900);
    expect(stats.ewmaMs).toBeGreaterThan(200);
  });
});
