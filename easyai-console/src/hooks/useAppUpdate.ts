import { useCallback, useEffect, useRef, useState } from 'react';
import { useVisibilityReconnect } from './useVisibilityReconnect';

/**
 * Detects that the server is now serving a different build and hands the client a way onto it.
 *
 * There is no service worker, so the only stale layer left is the document itself. Hashed chunks
 * under `/assets/**` are cached `immutable` on purpose (see `ConsoleResourcesAutoConfiguration`) —
 * a deploy renames them, so a cached copy can never shadow new code as long as the HTML that
 * references it is fresh. The entry documents are served `no-store`, but iOS keeps its own launch
 * cache for a standalone web app and honours `no-store` there inconsistently, and a desktop window
 * simply stays open across deploys. Both are what this probe works around.
 *
 * The two shells present the result differently: `AppLayout` shows a dismissible banner, a phone
 * gets the blocking `AppUpdateDialog` because there the only way back is deleting the web app from
 * the home screen and re-adding it.
 */

const STARTUP_PROBE_DELAY_MS = 4_000;
const PROBE_MIN_INTERVAL_MS = 60_000;

/** The hashed entry chunk a document booted from — its build identity. */
function entryChunkOf(doc: Document): string {
  return doc.querySelector('script[type="module"][src^="/assets/"]')?.getAttribute('src') ?? '';
}

// Module scripts are deferred, so the parsed document is available here and never changes:
// this is the version currently running, against which every probe is compared.
const loadedEntryChunk = entryChunkOf(document);

let lastProbeAt = 0;

/**
 * Compare the served HTML's entry chunk with ours. The request carries a unique query and
 * `no-store` so it cannot be answered from any cache, including the one being tested.
 */
async function isBuildStale(): Promise<boolean> {
  if (!loadedEntryChunk) return false;
  lastProbeAt = Date.now();
  try {
    const response = await fetch(`${location.pathname}?buildprobe=${lastProbeAt}`, { cache: 'no-store' });
    if (!response.ok) return false;
    const served = entryChunkOf(new DOMParser().parseFromString(await response.text(), 'text/html'));
    return served !== '' && served !== loadedEntryChunk;
  } catch {
    // A failed probe over a dropped tunnel says nothing about which build is deployed.
    return false;
  }
}

/**
 * Navigate to the new build under a URL no launch cache can hold.
 *
 * `location.reload()` is not used because it re-issues the same document URL, which is exactly
 * the request iOS may answer from its standalone launch cache. Hash routing keeps the current
 * screen because only the query changes.
 */
export function applyBuildUpdate(): void {
  const url = new URL(location.href);
  url.searchParams.set('build', loadedEntryChunk.split('/').pop() ?? String(Date.now()));
  location.replace(url.toString());
}

export interface AppUpdateState {
  updateAvailable: boolean;
  dismiss: () => void;
}

/**
 * Mounted once per shell. Probes shortly after start-up (not during first paint, which is already
 * saturating the link) and again whenever the page returns to the foreground — that is when a build
 * deployed while it was closed would otherwise go unnoticed.
 */
export function useAppUpdate(): AppUpdateState {
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const settled = useRef(false);

  const probe = useCallback(() => {
    if (settled.current) return;
    if (Date.now() - lastProbeAt < PROBE_MIN_INTERVAL_MS) return;
    void isBuildStale().then(stale => {
      if (stale) {
        settled.current = true;
        setUpdateAvailable(true);
      }
    });
  }, []);

  useEffect(() => {
    const timer = setTimeout(probe, STARTUP_PROBE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [probe]);

  useVisibilityReconnect(probe);

  return {
    updateAvailable,
    dismiss: () => {
      // Dismissing must not re-ask on every foreground return; the next cold start probes again.
      settled.current = true;
      setUpdateAvailable(false);
    },
  };
}
