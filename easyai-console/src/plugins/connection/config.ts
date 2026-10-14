/**
 * Mutable knobs for the connection plugin — same registration idiom as `TOPBAR_EXTRA_ITEMS`,
 * so a host tunes it before render without the plugin owning configuration.
 */
export const CONNECTION_CONFIG = {
  enabled: true,
  /** Probes this path first: one call returns latency *and* the relay witness when it exists. */
  probePath: '/api/connection/info',
  /** Used once the primary path is missing, so latency survives a backend without the adapter. */
  fallbackProbePath: '/api/chat/health',
  timeoutMs: 8000,
  intervalVisibleMs: 15000,
  intervalHiddenMs: 60000,
  /** Sampling periods are desynchronised by up to this fraction so several devices do not align. */
  jitterRatio: 0.2,
  historyLength: 60,
  ewmaAlpha: 0.3,
  /** `.ts.net` + a TTFB under this cannot be a relayed hop — the relay always adds more. */
  directMaxTtfbMs: 40,
  /** A sample claiming DIRECT above this is contradictory and gets flagged in the report. */
  suspectTtfbMs: 200,
  quality: {
    goodMs: 80,
    fairMs: 200,
    slowMs: 400,
  },
  /**
   * Relay host → display name. The witness reports a hop as `47.121.24.5:40000:vni:23`, and a
   * relayed relay node often has no `Addrs` of its own to reverse-lookup against, so the label
   * comes from configuration rather than from the tailnet.
   */
  relayLabels: {
    '47.121.24.5': 'aliyun-relay',
  } as Record<string, string>,
};
