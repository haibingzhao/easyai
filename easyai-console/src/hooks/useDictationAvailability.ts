import { useCallback, useEffect, useRef, useState } from 'react';
import { auxModelConfigService } from '@/services/aux-model-config-service';

/**
 * Whether the user configured the dictation models: `asr` gates the mic, `dictation_refine` only
 * decides whether the stop-time polish is offered.
 */
export function useDictationAvailability() {
  const [asrReady, setAsrReady] = useState(false);
  const refineReadyRef = useRef(false);

  useEffect(() => {
    auxModelConfigService.list().then((configs) => {
      setAsrReady(configs.some((c) => c.taskKey === 'asr' && c.modelConfigId.trim() !== ''));
      refineReadyRef.current = configs.some((c) => c.taskKey === 'dictation_refine' && c.modelConfigId.trim() !== '');
    }).catch(() => { /* persistence off: voice input stays unavailable */ });
  }, []);

  const isRefineEnabled = useCallback(() => refineReadyRef.current, []);

  return { asrReady, isRefineEnabled };
}
