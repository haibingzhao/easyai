import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Attachment } from '@/types/message';
import { authFetch } from '@/services/api-client';

interface ImageSource {
  kind: 'remote' | 'proxy' | 'data' | 'missing';
  src?: string;
  proxyUrl?: string;
}

/** Only the fixed internal file endpoint may receive the application's credentials. */
export function getAttachmentImageSource(attachment: Pick<Attachment, 'url' | 'filePath' | 'data' | 'mimeType'>): ImageSource {
  const internalUrl = attachment.url && /^\/api\/files\/serve(?:\?|$)/.test(attachment.url)
    ? attachment.url : undefined;
  const proxyUrl = attachment.filePath
    ? `/api/files/serve?path=${encodeURIComponent(attachment.filePath)}` : internalUrl;
  if (attachment.url && /^https?:\/\//i.test(attachment.url)) {
    return { kind: 'remote', src: attachment.url, proxyUrl };
  }
  if (proxyUrl) return { kind: 'proxy', src: proxyUrl, proxyUrl };
  if (attachment.mimeType.startsWith('image/') && attachment.data && /^[A-Za-z0-9+/]+={0,2}$/.test(attachment.data)) {
    return { kind: 'data', src: `data:${attachment.mimeType};base64,${attachment.data}` };
  }
  return { kind: 'missing' };
}

interface ImageState {
  source: ImageSource;
  attempt: number;
  src?: string;
  error: boolean;
}

export function useAttachmentImage({ url, filePath, data, mimeType }: Attachment) {
  const source = useMemo(() => getAttachmentImageSource({ url, filePath, data, mimeType }), [url, filePath, data, mimeType]);
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<ImageState>();
  const errorHandler = useRef<(failedSrc: string) => void>(() => {});

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | undefined;
    let displayedSrc: string | undefined;
    let proxyUsed = false;
    const controller = new AbortController();
    const update = (src?: string, error = false) => {
      if (cancelled) return;
      displayedSrc = src;
      setState({ source, attempt, src, error });
    };
    const loadProxy = async () => {
      if (proxyUsed || !source.proxyUrl) return;
      proxyUsed = true;
      update();
      try {
        // Never authFetch a signed/external URL, or follow redirects with a JWT.
        const response = await authFetch(source.proxyUrl, { signal: controller.signal, redirect: 'error' });
        if (!response.ok) throw new Error(`Image request failed (${response.status})`);
        const blob = await response.blob();
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        update(objectUrl);
      } catch {
        update(undefined, true);
      }
    };

    errorHandler.current = (failedSrc) => {
      // Thumbnail and lightbox can fail together; ignore stale/duplicate errors.
      if (cancelled || !displayedSrc || failedSrc !== displayedSrc) return;
      if (source.kind === 'remote' && !proxyUsed && source.proxyUrl) {
        void loadProxy();
      } else {
        if (objectUrl) {
          URL.revokeObjectURL(objectUrl);
          objectUrl = undefined;
        }
        update(undefined, true);
      }
    };
    if (source.kind === 'proxy') {
      void loadProxy();
    } else {
      update(source.src, source.kind === 'missing');
    }

    return () => {
      cancelled = true;
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [source, attempt]);

  const onError = useCallback((failedSrc: string) => errorHandler.current(failedSrc), []);
  const retry = useCallback(() => setAttempt((current) => current + 1), []);
  const current = state?.source === source && state.attempt === attempt ? state : undefined;
  return { src: current?.src, error: current?.error ?? false, onError, retry };
}
