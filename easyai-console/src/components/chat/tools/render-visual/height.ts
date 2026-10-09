/** Height protocol constants for render_visual sandboxes. */

/** Malicious or runaway fragments get capped; the shell document scrolls internally. */
export const MAX_IFRAME_HEIGHT = 2000;

export const MIN_IFRAME_HEIGHT = 40;

export function clampHeight(height: number): number {
  if (!Number.isFinite(height) || height <= 0) return MIN_IFRAME_HEIGHT;
  return Math.min(Math.ceil(height), MAX_IFRAME_HEIGHT);
}
