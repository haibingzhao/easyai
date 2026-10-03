/**
 * Host-side theme resolution and broadcast for render_visual sandboxes.
 *
 * The `.dark` class on <html> is the authoritative signal (utils/theme.ts sets
 * it for both explicit and system themes), so watching that class keeps the
 * sandboxes in lockstep with what the app actually paints. Active sandboxes
 * register here; a switch broadcasts to all of them (a missed instance would
 * leave half the cards in the old theme).
 */

import { serializeTokens, tokensForMode, type VisualThemeMode } from './tokens';

type ThemeSubscriber = (mode: VisualThemeMode) => void;

const subscribers = new Set<ThemeSubscriber>();

let observer: MutationObserver | null = null;

function ensureObserver(): void {
  if (observer !== null) return;
  observer = new MutationObserver(() => {
    const mode = currentMode();
    subscribers.forEach((subscriber) => subscriber(mode));
  });
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
}

export function currentMode(): VisualThemeMode {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light';
}

export function tokensCssForMode(mode: VisualThemeMode): string {
  return serializeTokens(tokensForMode(mode));
}

/** Register a sandbox for theme updates; returns the unsubscribe function. */
export function subscribeTheme(subscriber: ThemeSubscriber): () => void {
  subscribers.add(subscriber);
  ensureObserver();
  return () => {
    subscribers.delete(subscriber);
  };
}
