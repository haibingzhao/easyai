import { useEffect, useState } from 'react';
import { modelConfigService } from '@/services/model-config-service';
import type { SpeechCapabilities } from '@/types/settings';

export type SpeechCatalogState = 'idle' | 'hit' | 'miss';

const DEBOUNCE_MS = 400;

/**
 * Catalog lookup for a speech model id, narrowed to `speechCapabilities`.
 *
 * Separate from `useModelCatalogPrefill` because a SPEECH row wants none of the chat prefill: no
 * capability toggles, no token limits, no thinking matrix — only the voice list and the format /
 * sample-rate domain. The capabilities also reset to null the moment the id changes, so the form
 * cannot keep rendering one model's 139 voices while the next lookup is in flight.
 */
export function useSpeechCatalogPrefill(modelId: string) {
  const [state, setState] = useState<SpeechCatalogState>('idle');
  const [speech, setSpeech] = useState<SpeechCapabilities | null>(null);
  // The id `speech` was fetched for. Consumers must compare it with their current id before
  // acting: state updates land one render late, so "speech is set" alone can mean "the previous
  // model's capabilities".
  const [forModelId, setForModelId] = useState('');

  useEffect(() => {
    const trimmed = modelId.trim();
    setSpeech(null);
    setState('idle');
    setForModelId('');
    if (!trimmed) return;

    let cancelled = false;
    const timer = setTimeout(() => {
      modelConfigService
        .lookupModelCatalog(trimmed)
        .then((result) => {
          if (cancelled) return;
          const capabilities = result.entry?.speechCapabilities ?? null;
          setSpeech(capabilities);
          setState(capabilities ? 'hit' : 'miss');
          setForModelId(trimmed);
        })
        .catch(() => {
          if (cancelled) return;
          setSpeech(null);
          setState('idle');
          setForModelId('');
        });
    }, DEBOUNCE_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [modelId]);

  return { state, speech, forModelId };
}
