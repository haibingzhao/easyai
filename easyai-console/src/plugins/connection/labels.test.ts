import { describe, expect, it } from 'vitest';
import { CONNECTION_CONFIG as CFG } from './config';
import { isContradictory, qualityOf } from './labels';
import type { RouteSnapshot } from './types';

const route = (via: RouteSnapshot['via'], access: RouteSnapshot['access'] = 'TAILNET'): RouteSnapshot => ({
  access,
  via,
  source: 'latency',
  confidence: 'probable',
  evidence: { host: 'mac-mini.tailae6c41.ts.net' },
});

describe('qualityOf', () => {
  it('refuses to claim a quality before anything was measured', () => {
    expect(qualityOf(true, null)).toBe('unknown');
  });

  it('treats a failed path as down, and a hang as bad while the socket is still up', () => {
    expect(qualityOf(false, 30)).toBe('down');
    expect(qualityOf(true, 30, 'network')).toBe('down');
    expect(qualityOf(true, 30, 'timeout')).toBe('bad');
  });

  it('treats a server-side 5xx as bad even though it answered fast', () => {
    expect(qualityOf(true, 12, undefined, 500)).toBe('bad');
    expect(qualityOf(true, 12, undefined, 401)).toBe('good');
  });

  it('buckets latency against the configured thresholds', () => {
    expect(qualityOf(true, CFG.quality.goodMs)).toBe('good');
    expect(qualityOf(true, CFG.quality.goodMs + 1)).toBe('fair');
    expect(qualityOf(true, CFG.quality.fairMs + 1)).toBe('slow');
    expect(qualityOf(true, CFG.quality.slowMs + 1)).toBe('bad');
  });
});

describe('isContradictory', () => {
  it('flags a direct claim riding on relay-shaped latency, but not on a LAN literal', () => {
    expect(isContradictory(route('DIRECT'), CFG.suspectTtfbMs + 100)).toBe(true);
    expect(isContradictory(route('DIRECT'), 12)).toBe(false);
    expect(isContradictory(route('DIRECT', 'LAN'), CFG.suspectTtfbMs + 100)).toBe(false);
    expect(isContradictory(route('RELAY'), CFG.suspectTtfbMs + 100)).toBe(false);
  });
});
