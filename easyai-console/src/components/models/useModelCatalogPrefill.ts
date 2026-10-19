import { useEffect, useRef, useState } from 'react';
import { modelConfigService } from '@/services/model-config-service';
import { initialModelOptions } from '@/constants/model-options';
import type { ModelCapabilities, ModelCatalogEntry, ModelOptions } from '@/types/settings';

export type CatalogPrefillState = 'idle' | 'hit' | 'miss';

const DEBOUNCE_MS = 400;

/** Backend default for contextToken; a prefill clamps it under the catalog's window. */
const DEFAULT_CONTEXT_TOKEN = 204_800;

/**
 * Prefill a model form from the global model catalog.
 *
 * Watches the effective model id, debounces a lookup, and on a hit fills the capability
 * toggles and token limits exactly once per id — later manual edits stick. `thinking` is
 * deliberately never touched: the catalog's thinking flag is a selection hint, while the form's
 * switch changes runtime behavior and cost. `temperature` is only ever set by the form itself,
 * because an absent one now means "omit" rather than "backend default".
 */
export function useModelCatalogPrefill(modelId: string) {
  const [state, setState] = useState<CatalogPrefillState>('idle');
  const [entry, setEntry] = useState<ModelCatalogEntry | null>(null);
  const [options, setOptionsState] = useState<ModelOptions>(initialModelOptions);
  const [capabilities, setCapabilitiesState] = useState<ModelCapabilities>({});
  const filledForRef = useRef('');

  const setOptions = (next: ModelOptions | ((prev: ModelOptions) => ModelOptions)) =>
    setOptionsState(prev => (typeof next === 'function' ? next(prev) : next));
  const setCapabilities = (next: ModelCapabilities | ((prev: ModelCapabilities) => ModelCapabilities)) =>
    setCapabilitiesState(prev => (typeof next === 'function' ? next(prev) : next));

  useEffect(() => {
    const trimmed = modelId.trim();
    if (!trimmed) {
      setState('idle');
      setEntry(null);
      filledForRef.current = '';
      return;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      modelConfigService
        .lookupModelCatalog(trimmed)
        .then(hit => {
          if (cancelled) return;
          setEntry(hit);
          setState(hit ? 'hit' : 'miss');
          if (hit && filledForRef.current !== trimmed) {
            filledForRef.current = trimmed;
            applyPrefill(hit, setOptionsState, setCapabilitiesState);
          }
        })
        .catch(() => {
          if (!cancelled) setState('idle');
        });
    }, DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [modelId]);

  return { state, entry, options, setOptions, capabilities, setCapabilities };
}

function applyPrefill(
  hit: ModelCatalogEntry,
  setOptionsState: (updater: (prev: ModelOptions) => ModelOptions) => void,
  setCapabilitiesState: (updater: (prev: ModelCapabilities) => ModelCapabilities) => void
): void {
  const caps = hit.capabilities;
  const limits = hit.defaultOptions;
  if (caps) {
    setCapabilitiesState(prev => ({
      ...prev,
      vision: caps.vision ?? false,
      structuredOutput: caps.structuredOutput ?? undefined,
      supportsToolCalling: caps.supportsToolCalling ?? undefined,
    }));
  }
  setOptionsState(prev => {
    const next: ModelOptions = { ...prev };
    if (limits?.maxTokens != null) next.maxTokens = limits.maxTokens;
    if (limits?.maxContextTokens != null) {
      next.maxContextTokens = limits.maxContextTokens;
      next.contextToken = Math.min(prev.contextToken ?? DEFAULT_CONTEXT_TOKEN, limits.maxContextTokens);
    }
    return next;
  });
}
