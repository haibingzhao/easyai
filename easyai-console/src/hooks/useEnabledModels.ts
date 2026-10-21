import { useEffect, useState } from 'react';
import { modelConfigService } from '@/services/model-config-service';
import { readErrorMessage } from '@/services/api-client';
import { i18n } from '@/utils/i18n';
import type { ModelProviderConfig } from '@/types/settings';

/**
 * Enabled chat model configurations, loaded only while `active` is true.
 * Surfaces load failures so callers can show them instead of rendering an empty picker.
 */
export function useEnabledModels(active = true) {
  const [models, setModels] = useState<ModelProviderConfig[]>([]);
  const [selectedModelId, setSelectedModelId] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!active) return;
    let cancelled = false;
    setError(null);
    modelConfigService.getUserConfigurations()
      .then((configs) => {
        if (cancelled) return;
        const enabled = configs.filter((c) => c.enabled);
        setModels(enabled);
        setSelectedModelId((prev) => prev || enabled[0]?.id || '');
      })
      .catch((e) => {
        if (!cancelled) setError(readErrorMessage(e, i18n('Failed to load models')));
      });
    return () => { cancelled = true; };
  }, [active]);

  return { models, selectedModelId, setSelectedModelId, error };
}
