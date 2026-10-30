import { useEffect, useSyncExternalStore } from 'react';
import { ttsService } from '@/services/tts-service';

export type TtsAvailabilityStatus = 'idle' | 'loading' | 'ready' | 'failed';

/** A transport failure is remembered this long before the next probe. */
const RETRY_MS = 15_000;

/** The probe is a plain GET; a hung one must not pin the hook in `loading` forever. */
const PROBE_TIMEOUT_MS = 10_000;

export interface TtsAvailabilityState {
  status: TtsAvailabilityStatus;
  /** True only when a usable TTS model is configured; otherwise the button stays disabled. */
  ready: boolean;
  /** Machine-readable code from the backend, for logs and future UI copy. */
  reason?: string;
}

/**
 * One shared read-aloud availability result for the whole app.
 *
 * The check is a real backend probe — the configured row may have been deleted, disabled or retyped
 * since it was chosen, which an aux id alone cannot tell — so it must not run per message bar.
 * A module-level store means the first subscriber triggers the only request; every other bar reads
 * the same snapshot, and [invalidateTtsAvailability] re-checks after the settings page changes.
 */
let state: TtsAvailabilityState = { status: 'idle', ready: false };
const listeners = new Set<() => void>();
let inFlight: Promise<void> | null = null;
/**
 * Bumped by every [load] and by [invalidateTtsAvailability]. A probe that resolves after its
 * generation has passed writes nothing: saving the TTS model while the previous probe is still in
 * flight must not let the older answer ("not configured") land on top of the newer one.
 */
let generation = 0;

function emit(next: TtsAvailabilityState): void {
  state = next;
  listeners.forEach((listener) => listener());
}

function load(): void {
  if (inFlight) return;
  emit({ status: 'loading', ready: false });
  const mine = ++generation;
  const request = ttsService
    .availability(AbortSignal.timeout(PROBE_TIMEOUT_MS))
    .then((result) => {
      if (mine !== generation) return;
      emit({ status: 'ready', ready: result.configured, reason: result.reason });
    })
    // Persistence off, network down or an unexpected status: read-aloud is unavailable for now,
    // but the answer is not authoritative — a startup blip must not hide the button for the life
    // of the tab, so this state is retried on a timer by the hook.
    .catch(() => {
      if (mine !== generation) return;
      emit({ status: 'failed', ready: false, reason: 'UNAVAILABLE' });
    })
    .finally(() => {
      // Only the current request owns the slot; a superseded one must not free it for the next call.
      if (inFlight === request) inFlight = null;
    });
  inFlight = request;
}

/** Force the next reader to re-check, e.g. after Task Models or a speech row was saved. */
export function invalidateTtsAvailability(): void {
  // Drop any probe still in flight without waiting for it: its answer predates the save.
  generation++;
  inFlight = null;
  emit({ status: 'idle', ready: false });
  if (listeners.size > 0) load();
}

/** Forget the probe result without scheduling a new one, e.g. when the signed-in account changes. */
export function resetTtsAvailability(): void {
  generation++;
  inFlight = null;
  emit({ status: 'idle', ready: false });
}

const subscribe = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

const getSnapshot = (): TtsAvailabilityState => state;

/** Whether the visible read-aloud button can start synthesis. */
export function useTtsAvailability(): TtsAvailabilityState {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);

  useEffect(() => {
    if (snapshot.status === 'idle') {
      load();
      return;
    }
    if (snapshot.status !== 'failed') return;
    // One timer per failure, not a loop: the probe retries while a consumer is mounted, and a
    // permanently absent backend costs one tiny GET every RETRY_MS instead of none forever.
    const timer = setTimeout(load, RETRY_MS);
    return () => clearTimeout(timer);
  }, [snapshot.status]);

  return snapshot;
}
