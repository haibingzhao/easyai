import { useCallback, useMemo, useSyncExternalStore } from 'react';
import { ttsPlayback, type TtsPlaybackSnapshot } from '@/services/tts-playback-controller';

export interface TtsPlaybackControls extends TtsPlaybackSnapshot {
  /**
   * One click handler for every read-aloud button: starts the clip, stops it when the same message
   * is playing or loading, and resumes it when the browser blocked autoplay.
   */
  toggle(key: string, text: string): void;
  stop(): void;
}

/** Subscribe to the shared player; safe to call from every message's meta bar. */
export function useTtsPlayback(): TtsPlaybackControls {
  const snapshot = useSyncExternalStore(
    ttsPlayback.subscribe,
    ttsPlayback.getSnapshot,
    ttsPlayback.getSnapshot
  );

  const toggle = useCallback((key: string, text: string) => {
    const current = ttsPlayback.getSnapshot();
    if (current.activeKey === key) {
      if (current.state === 'ready') {
        void ttsPlayback.resume();
        return;
      }
      if (current.state === 'loading' || current.state === 'playing') {
        ttsPlayback.stop();
        return;
      }
    }
    void ttsPlayback.speak(key, text);
  }, []);

  const stop = useCallback(() => ttsPlayback.stop(), []);

  // Every meta bar calls this hook; a fresh object each render would churn their props for nothing.
  return useMemo(() => ({ ...snapshot, toggle, stop }), [snapshot, toggle, stop]);
}
