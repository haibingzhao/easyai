export function formatTokenCount(count: number): string {
  if (count >= 1000000) {
    return `${(count / 1000000).toFixed(1)}M`;
  }
  if (count >= 1000) {
    return `${(count / 1000).toFixed(1)}K`;
  }
  return count.toString();
}

export function formatUsage(usage: { inputTokens: number; outputTokens: number; totalTokens: number }): string {
  return `${formatTokenCount(usage.inputTokens)} in / ${formatTokenCount(usage.outputTokens)} out / ${formatTokenCount(usage.totalTokens)} total`;
}

export function formatCost(cost: number): string {
  if (cost < 0.01) {
    return `$${cost.toFixed(4)}`;
  }
  return `$${cost.toFixed(2)}`;
}

export function formatModelCost(cost: { input: number; output: number }): string {
  return `$${cost.input}/M in / $${cost.output}/M out`;
}

/**
 * Safely parse an integer from a string, returning the fallback value
 * when the input is empty or NaN. Unlike `parseInt(s) || fallback`,
 * this correctly handles the value 0.
 */
export function safeParseInt(value: string, fallback: number): number {
  const n = parseInt(value, 10);
  return Number.isNaN(n) ? fallback : n;
}

/**
 * Escape special regex metacharacters in a string so it can be used
 * as a literal pattern inside a RegExp constructor.
 */
export function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Format a duration in milliseconds to a human-readable string.
 * - Default: "X.Xs" for >= 1000ms, "Xms" below (precision=1)
 * - precision=0: "Xs" for >= 1000ms
 * - showHours: full "Xh Ym Zs" format
 */
export function formatDurationMs(ms: number, options?: { showHours?: boolean; precision?: number }): string {
  if (options?.showHours) {
    const totalSec = Math.round(ms / 1000);
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    const parts: string[] = [];
    if (h > 0) parts.push(`${h}h`);
    if (m > 0) parts.push(`${m}m`);
    if (s > 0 || parts.length === 0) parts.push(`${s}s`);
    return parts.join(' ');
  }
  const precision = options?.precision ?? 1;
  if (ms >= 1_000) return `${(ms / 1_000).toFixed(precision)}s`;
  return `${ms}ms`;
}

/**
 * Format a duration in seconds to "Xm Ys" or "Xh Ym Zs" format.
 */
export function formatDurationSeconds(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (h > 0) return `${h}h ${m}m ${s}s`;
  return `${m}m ${s}s`;
}
