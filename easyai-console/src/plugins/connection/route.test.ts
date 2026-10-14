import { describe, expect, it } from 'vitest';
import { classifyAccess, labelRelayHost, mergeRoute, parseRelayAddress } from './route';
import { isContradictory, routeText } from './labels';
import type { ConnectionInfoDto } from './types';

const HOST = 'mac-mini.tailae6c41.ts.net';

describe('classifyAccess', () => {
  it('splits loopback, LAN, tailnet and public hosts', () => {
    expect(classifyAccess('localhost')).toBe('LOCAL');
    expect(classifyAccess('127.0.0.1')).toBe('LOCAL');
    expect(classifyAccess('192.168.113.134')).toBe('LAN');
    expect(classifyAccess('mini.local')).toBe('LAN');
    expect(classifyAccess('100.78.91.50')).toBe('TAILNET');
    expect(classifyAccess(HOST)).toBe('TAILNET');
    expect(classifyAccess('easyai.example.com')).toBe('PUBLIC');
  });

  it('is case insensitive because browsers hand back whatever was typed', () => {
    expect(classifyAccess('Mac-Mini.TailAE6C41.TS.NET')).toBe('TAILNET');
  });

  it('does not read 100.128.x as tailnet space', () => {
    expect(classifyAccess('100.128.0.1')).toBe('PUBLIC');
  });
});

describe('parseRelayAddress', () => {
  it('keeps host and host:port out of a vni-suffixed report', () => {
    expect(parseRelayAddress('47.121.24.5:40000:vni:23')).toEqual({
      host: '47.121.24.5',
      address: '47.121.24.5:40000',
    });
  });

  it('labels a configured relay host', () => {
    expect(labelRelayHost('47.121.24.5')).toBe('aliyun-relay');
    expect(labelRelayHost('10.0.0.9')).toBeUndefined();
  });
});

describe('mergeRoute', () => {
  it('trusts the URL for LAN and loopback without any sample', () => {
    const lan = mergeRoute({ access: 'LAN', host: '192.168.113.20:8080', info: null, infoError: null });
    expect(lan).toMatchObject({ via: 'DIRECT', source: 'url', confidence: 'certain' });

    const local = mergeRoute({ access: 'LOCAL', host: 'localhost:8080', info: null, infoError: null });
    expect(local.via).toBe('DIRECT');
  });

  it('reads a slow tailnet round trip as relayed, and names the relay as unknown', () => {
    const route = mergeRoute({ access: 'TAILNET', host: HOST, ttfbMs: 260, info: null, infoError: null });
    expect(route).toMatchObject({ via: 'RELAY', source: 'latency', confidence: 'probable' });
    expect(route.relay).toBeUndefined();
    expect(route.note).toBe('relayTypeInvisible');
  });

  it('reads a fast tailnet round trip as direct and a missing sample as unknown', () => {
    expect(
      mergeRoute({ access: 'TAILNET', host: HOST, ttfbMs: 9, info: null, infoError: null }).via,
    ).toBe('DIRECT');
    expect(
      mergeRoute({ access: 'TAILNET', host: HOST, info: null, infoError: null }),
    ).toMatchObject({ via: 'UNKNOWN', source: 'none', confidence: 'none' });
  });

  it('prefers the server witness and maps a peer relay hop onto its configured name', () => {
    const info: ConnectionInfoDto = {
      observed: { host: HOST, remoteAddress: '127.0.0.1', xForwardedFor: ['100.65.179.113'] },
      route: 'RELAY_PEER',
      peer: { address: '100.65.179.113', peerRelay: '47.121.24.5:40000:vni:23', relay: 'hkg' },
      server: { hostname: 'mac-mini', tailscaleAvailable: true },
      generatedAt: 1_760_000_000_000,
    };
    const route = mergeRoute({ access: 'TAILNET', host: HOST, ttfbMs: 12, info, infoError: null });

    expect(route).toMatchObject({ via: 'RELAY', source: 'server', confidence: 'probable', note: 'serverView' });
    expect(route.relay).toEqual({ kind: 'PEER', name: 'aliyun-relay', address: '47.121.24.5:40000', derpRegion: undefined });
    // A fast TTFB must not override the witness: latency is the weaker claim.
    expect(routeText(route)).toContain('Aliyun relay');
    expect(route.evidence.xff).toEqual(['100.65.179.113']);
    expect(route.evidence.peerRelay).toBe('47.121.24.5:40000:vni:23');
  });

  it('carries a DERP region through as the relay identity', () => {
    const info: ConnectionInfoDto = { route: 'RELAY_DERP', relay: { kind: 'DERP', derpRegion: 'sfo' } };
    const route = mergeRoute({ access: 'TAILNET', host: HOST, ttfbMs: 900, info, infoError: null });
    expect(route.via).toBe('RELAY');
    expect(route.relay).toMatchObject({ kind: 'DERP', derpRegion: 'sfo' });
    expect(routeText(route)).toContain('sfo');
  });

  it('reports a punched direct path even when the URL says nothing about it', () => {
    const info: ConnectionInfoDto = { route: 'TAILNET_DIRECT', peer: { address: '100.65.179.113', curAddr: '192.168.113.134:41641' } };
    const route = mergeRoute({ access: 'TAILNET', host: HOST, ttfbMs: 4, info, infoError: null });
    expect(route).toMatchObject({ via: 'DIRECT', source: 'server' });
    expect(route.evidence.peerCurAddr).toBe('192.168.113.134:41641');
  });

  it('falls back to latency when the witness itself is unsure', () => {
    const info: ConnectionInfoDto = { route: 'UNKNOWN', server: { tailscaleAvailable: false } };
    const route = mergeRoute({ access: 'TAILNET', host: HOST, ttfbMs: 260, info, infoError: 'unauthorized' });
    expect(route).toMatchObject({ via: 'RELAY', source: 'latency' });
    expect(route.evidence.tailscaleAvailable).toBe(false);
  });

  it('flags a DIRECT claim riding on relay-shaped latency', () => {
    const info: ConnectionInfoDto = { route: 'TAILNET_DIRECT' };
    const route = mergeRoute({ access: 'TAILNET', host: HOST, ttfbMs: 480, info, infoError: null });
    expect(isContradictory(route, 480)).toBe(true);
    expect(isContradictory(route, 30)).toBe(false);
  });
});
