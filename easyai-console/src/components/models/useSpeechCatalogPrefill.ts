import { useEffect, useState } from 'react';
import { modelConfigService } from '@/services/model-config-service';
import type { SpeechCapabilities } from '@/types/settings';

export type SpeechCatalogState = 'idle' | 'hit' | 'miss';

const DEBOUNCE_MS = 400;

/**
 * Catalog lookup for a speech or recognition model id.
 *
 * Separate from `useModelCatalogPrefill` because a SPEECH/ASR row wants none of the chat prefill:
 * no capability toggles, no token limits, no thinking matrix — only the block that describes its
 * own request surface. Both blocks share one column, so one lookup serves both; [speech] is null for
 * an ASR row and [asr] is null for a TTS row, which is what keeps one form from rendering the
 * other's controls. The result resets to null the moment the id changes, so the form cannot keep
 * rendering one model's voices while the next lookup is in flight.
 */
export function useSpeechCatalogPrefill(modelId: string) {
  const [state, setState] = useState<SpeechCatalogState>('idle');
  const [capabilities, setCapabilities] = useState<SpeechCapabilities | null>(null);
  // The id `capabilities` was fetched for. Consumers must compare it with their current id before
  // acting: state updates land one render late, so "capabilities is set" alone can mean "the previous
  // model's capabilities".
  const [forModelId, setForModelId] = useState('');

  useEffect(() => {
    const trimmed = modelId.trim();
    setCapabilities(null);
    setState('idle');
    setForModelId('');
    if (!trimmed) return;

    let cancelled = false;
    const timer = setTimeout(() => {
      modelConfigService
        .lookupModelCatalog(trimmed)
        .then((result) => {
          if (cancelled) return;
          const entry = result.entry?.speechCapabilities ?? null;
          setCapabilities(entry);
          setState(entry ? 'hit' : 'miss');
          setForModelId(trimmed);
        })
        .catch(() => {
          if (cancelled) return;
          setCapabilities(null);
          setState('idle');
          setForModelId('');
        });
    }, DEBOUNCE_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [modelId]);

  return {
    state,
    speech: hasSynthesisBlock(capabilities) ? capabilities : null,
    asr: capabilities?.asr ?? null,
    forModelId,
  };
}

/** A row with neither voices nor output formats is an ASR row whose TTS flags are just defaults. */
function hasSynthesisBlock(capabilities: SpeechCapabilities | null): capabilities is SpeechCapabilities {
  if (!capabilities) return false;
  return (capabilities.voices?.length ?? 0) > 0 || (capabilities.formats?.length ?? 0) > 0;
}
