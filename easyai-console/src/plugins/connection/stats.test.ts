import { describe, expect, it } from 'vitest';
import { LATENCY_WINDOW_SAMPLES, summariseLatency } from './stats';
import type { ProbeSample } from './types';

const ok = (ttfbMs: number, at = 0): ProbeSample => ({ at, ok: true, ttfbMs });
const failed = (at = 0): ProbeSample => ({ at, ok: false, error: 'network' });

describe('summariseLatency', () => {
  it('reports nothing before a successful sample', () => {
    expect(summariseLatency([])).toEqual({ p50Ms: null, minMs: null, maxMs: null });
    expect(summariseLatency([failed()])).toEqual({ p50Ms: null, minMs: null, maxMs: null });
  });

  it('takes the middle of the window, not a moving average', () => {
    expect(summariseLatency([ok(100, 1), ok(200, 2)]).p50Ms).toBe(150);
    expect(summariseLatency([ok(100, 1), ok(200, 2), ok(300, 3)]).p50Ms).toBe(200);
  });

  it('leaves the reading untouched when the newest round failed', () => {
    const before = summariseLatency([ok(100, 1), ok(200, 2)]);
    const after = summariseLatency([ok(100, 1), ok(200, 2), failed(3)]);

    expect(after).toEqual(before);
    expect(after.minMs).toBe(100);
    expect(after.maxMs).toBe(200);
  });

  it('does not let one spike hijack the headline', () => {
    // The shape from the field: four direct-path samples and one 1.5s outlier.
    const stats = summariseLatency([ok(41, 1), ok(37, 2), ok(111, 3), ok(1473, 4), ok(50, 5)]);

    expect(stats.p50Ms).toBe(50);
    expect(stats.maxMs).toBe(1473);
  });

  it('reads min and max over the capped window only', () => {
    const samples = [ok(5000, 0), ...Array.from({ length: LATENCY_WINDOW_SAMPLES }, (_, index) => ok(50, index + 1))];
    const stats = summariseLatency(samples);

    expect(stats.p50Ms).toBe(50);
    expect(stats.minMs).toBe(50);
    expect(stats.maxMs).toBe(50);
  });
});
