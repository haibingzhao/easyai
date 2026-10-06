import { useCallback, useEffect, useMemo, useState } from 'react';
import { authFetch } from '@/services/api-client';
import { getAvatarSource } from '@/utils/avatar-utils';

/**
 * Resolve a stored avatar reference to something an `<img>` can show.
 *
 * `src` stays undefined until the picture is actually available, which keeps the colour-seeded initials
 * as the base layer: no flicker on load, and a dead link or a deleted object simply keeps showing them.
 * Only our own media endpoint is fetched with credentials, and its object URL is revoked on change.
 */
export function useAvatarImage(avatar: string | null | undefined): { src?: string; onError: () => void } {
  const source = useMemo(() => getAvatarSource(avatar), [avatar]);
  const [ownedSrc, setOwnedSrc] = useState<string>();
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    setOwnedSrc(undefined);
    setDismissed(false);
    if (source.kind !== 'owned') return;

    const controller = new AbortController();
    let objectUrl: string | undefined;
    void (async () => {
      try {
        const response = await authFetch(source.src, { signal: controller.signal, redirect: 'error' });
        if (!response.ok) throw new Error(`Avatar request failed (${response.status})`);
        objectUrl = URL.createObjectURL(await response.blob());
        setOwnedSrc(objectUrl);
      } catch {
        // A missing object is the preset again, not an error worth surfacing.
      }
    })();

    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [source]);

  const onError = useCallback(() => setDismissed(true), []);

  if (dismissed) return { src: undefined, onError };
  if (source.kind === 'external') return { src: source.src, onError };
  if (source.kind === 'owned') return { src: ownedSrc, onError };
  return { src: undefined, onError };
}
