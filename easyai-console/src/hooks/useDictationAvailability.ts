import { useCallback, useEffect, useRef, useState } from 'react';
import { asrService, asrUnavailableNotice, type AsrCapability, type AsrTransport, type AsrUnavailableReason } from '@/services/asr-service';
import { i18n } from '@/utils/i18n';

/**
 * Which voice-input channel the next round will use, and whether it exists at all.
 *
 * The backend answers this (`GET /api/asr/availability`); the console never decides from the model
 * id. `asrReady` stays the single boolean the Chat composer and the article editor already consume,
 * while `transport`/`reason` drive the copy. A failed read counts as unavailable — a stale "maybe it
 * works" would light up the microphone with a configuration the server has already rejected.
 */
export function useDictationAvailability() {
  const [asrReady, setAsrReady] = useState(false);
  const [transport, setTransport] = useState<AsrTransport>('NONE');
  const [capability, setCapability] = useState<AsrCapability>('NONE');
  const [reason, setReason] = useState<AsrUnavailableReason | null>(null);
  /** Ready-to-show reason for the disabled microphone; empty while a channel is available. */
  const [notice, setNotice] = useState('');
  const refineReadyRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    asrService.availability().then((availability) => {
      if (cancelled) return;
      setTransport(availability.transport);
      setCapability(availability.capability);
      setReason(availability.reason);
      setAsrReady(availability.transport !== 'NONE');
      setNotice(availability.transport === 'NONE' ? i18n(asrUnavailableNotice(availability)) : '');
      refineReadyRef.current = availability.refineReady;
    }).catch(() => {
      // Persistence off, older server, network down: no channel is a safe answer, not a guess.
      if (cancelled) return;
      setAsrReady(false);
      setTransport('NONE');
      setCapability('NONE');
      setReason(null);
      setNotice(i18n(asrUnavailableNotice(null)));
    });
    return () => { cancelled = true; };
  }, []);

  const isRefineEnabled = useCallback(() => refineReadyRef.current, []);

  return { asrReady, transport, capability, reason, notice, isRefineEnabled };
}
