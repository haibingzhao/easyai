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
