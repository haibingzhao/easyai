import { useEffect, useRef, useState } from 'react';
import { modelConfigService } from '@/services/model-config-service';
import { initialModelOptions } from '@/constants/model-options';
import { UNKNOWN_REASONING_PROFILE } from '@/constants/reasoning';
import { resolveReasoningControls } from './reasoning-controls';
import type { ModelCapabilities, ModelCatalogEntry, ModelOptions, Protocol, ReasoningProfile } from '@/types/settings';

export type CatalogPrefillState = 'idle' | 'hit' | 'miss';

const DEBOUNCE_MS = 400;

/** Backend default for contextToken; a prefill clamps it under the catalog's window. */
const DEFAULT_CONTEXT_TOKEN = 204_800;

/**
 * Prefill a model form from the global model catalog and the resolved reasoning matrix.
 *
 * Watches the effective model id, debounces a lookup, and on a hit fills the capability
 * toggles, token limits and the thinking profile exactly once per id — later manual edits stick.
 * The reasoning matrix also arrives for ids the catalog lacks (e.g. deepseek-v4-pro), because the
 * backend resolves it from the family prefix. `temperature` is only ever set by the form itself.
 */
export function useModelCatalogPrefill(modelId: string, protocol: Protocol | '' | undefined) {
  const [state, setState] = useState<CatalogPrefillState>('idle');
  const [entry, setEntry] = useState<ModelCatalogEntry | null>(null);
  const [reasoning, setReasoning] = useState<ReasoningProfile>(UNKNOWN_REASONING_PROFILE);
  const [options, setOptionsState] = useState<ModelOptions>(initialModelOptions);
  const [capabilities, setCapabilitiesState] = useState<ModelCapabilities>({});
  const filledForRef = useRef('');

  const setOptions = (next: ModelOptions | ((prev: ModelOptions) => ModelOptions)) =>
    setOptionsState(prev => (typeof next === 'function' ? next(prev) : next));
  const setCapabilities = (next: ModelCapabilities | ((prev: ModelCapabilities) => ModelCapabilities)) =>
    setCapabilitiesState(prev => (typeof next === 'function' ? next(prev) : next));

  useEffect(() => {
    const trimmed = modelId.trim();
    // A profile belongs to one model id: never let the previous model's matrix survive an id or
    // protocol change, a debounce window, or a failed lookup. The runtime switches are part of that
    // matrix, so they reset here and are re-seeded from the new profile below.
    setReasoning(UNKNOWN_REASONING_PROFILE);
    clearReasoning(setCapabilitiesState);
    setOptionsState(prev => (prev.thinking === undefined && prev.effort === undefined
      ? prev
      : { ...prev, thinking: undefined, effort: undefined }));
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
        .then(result => {
          if (cancelled) return;
          setEntry(result.entry);
          setReasoning(result.reasoning);
          setState(result.entry ? 'hit' : 'miss');
          applyReasoningProfile(result.reasoning, setCapabilitiesState);
          if (filledForRef.current !== trimmed) {
            filledForRef.current = trimmed;
            if (result.entry) {
              applyPrefill(result.entry, setOptionsState, setCapabilitiesState);
            }
            applyReasoningDefaults(result.reasoning, protocol, setOptionsState);
          }
        })
        .catch(() => {
          if (cancelled) return;
          setState('idle');
          setEntry(null);
          setReasoning(UNKNOWN_REASONING_PROFILE);
          clearReasoning(setCapabilitiesState);
        });
    }, DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [modelId, protocol]);

  return { state, entry, reasoning, options, setOptions, capabilities, setCapabilities };
}

function clearReasoning(
  setCapabilitiesState: (updater: (prev: ModelCapabilities) => ModelCapabilities) => void
): void {
  setCapabilitiesState(prev => (prev.reasoning === undefined ? prev : { ...prev, reasoning: undefined }));
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

/**
 * Record which matrix the form belongs to. `UNKNOWN` is deliberately not written: doing so would
 * turn a legacy row into an explicit declaration and could change its runtime behavior.
 */
function applyReasoningProfile(
  profile: ReasoningProfile,
  setCapabilitiesState: (updater: (prev: ModelCapabilities) => ModelCapabilities) => void
): void {
  if (profile.mode === 'UNKNOWN') {
    clearReasoning(setCapabilitiesState);
    return;
  }
  setCapabilitiesState(prev => ({ ...prev, reasoning: profile }));
}

/**
 * Seed the thinking switches from the resolved profile, but only when the protocol gate actually
 * allows effort. Anthropic's budget and effort are mutually exclusive, so a thinking-on default
 * must not also seed an effort the save endpoint would reject.
 */
function applyReasoningDefaults(
  profile: ReasoningProfile,
  protocol: Protocol | '' | undefined,
  setOptionsState: (updater: (prev: ModelOptions) => ModelOptions) => void
): void {
  if (profile.mode === 'UNKNOWN') return;
  const controls = resolveReasoningControls(profile, protocol, profile.defaultThinking);
  const effort = controls.strength === 'editable' &&
    profile.defaultEffort != null &&
    controls.effortLevels.includes(profile.defaultEffort)
    ? profile.defaultEffort
    : undefined;
  setOptionsState(prev => ({ ...prev, thinking: profile.defaultThinking, effort }));
}
