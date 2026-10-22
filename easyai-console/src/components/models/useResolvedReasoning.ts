import { useEffect, useState } from 'react';
import { modelConfigService } from '@/services/model-config-service';
import { UNKNOWN_REASONING_PROFILE } from '@/constants/reasoning';
import type { ReasoningProfile } from '@/types/settings';

const DEBOUNCE_MS = 350;

interface ResolvedProfile {
  /** The id this result was resolved for; a result for another id must never be returned. */
  modelId: string;
  profile: ReasoningProfile;
}

const UNRESOLVED: ResolvedProfile = { modelId: '', profile: UNKNOWN_REASONING_PROFILE };

/**
 * Profile for an existing model config: the row's own declaration first, the catalog/resolver
 * second. Used by the edit dialog, which must not prefill options — only gate the controls.
 *
 * [stored] belongs to [storedModelId]; once the form's model id changes it is no longer trusted,
 * and the displayed profile falls back to UNKNOWN until the new id resolves.
 */
export function useResolvedReasoning(
  modelId: string,
  stored?: ReasoningProfile,
  storedModelId?: string
): ReasoningProfile {
  const trimmed = modelId.trim();
  const storedApplies = stored != null && storedModelId != null && trimmed === storedModelId.trim();
  const [resolved, setResolved] = useState<ResolvedProfile>(UNRESOLVED);

  useEffect(() => {
    if (!trimmed) {
      setResolved(UNRESOLVED);
      return;
    }
    if (storedApplies && stored) {
      setResolved({ modelId: trimmed, profile: stored });
      return;
    }
    // Clear immediately: during the debounce window (and after a failure) the previous model's
    // matrix must not be mistaken for this one's.
    setResolved({ modelId: trimmed, profile: UNKNOWN_REASONING_PROFILE });
    let cancelled = false;
    const timer = setTimeout(() => {
      modelConfigService
        .lookupModelCatalog(trimmed)
        .then(result => {
          if (!cancelled) setResolved({ modelId: trimmed, profile: result.reasoning });
        })
        .catch(() => {
          if (!cancelled) setResolved({ modelId: trimmed, profile: UNKNOWN_REASONING_PROFILE });
        });
    }, DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [trimmed, stored, storedModelId, storedApplies]);

  if (storedApplies && stored) return stored;
  return resolved.modelId === trimmed ? resolved.profile : UNKNOWN_REASONING_PROFILE;
}
