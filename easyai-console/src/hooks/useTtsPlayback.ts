import { useCallback, useMemo, useSyncExternalStore } from 'react';
import { ttsPlayback, type TtsPlaybackSnapshot } from '@/services/tts-playback-controller';
import type { RenderSpeechRequest, SpeechTargetRef } from '@/services/tts-service';
import type { ReadAloudMode, ReadAloudTone } from '@/types/settings';

export interface TtsPlaybackControls extends TtsPlaybackSnapshot {
  /**
   * One click handler for every read-aloud button: starts the clip, stops it when the same message
   * is playing or loading, and resumes it when the browser blocked autoplay.
   */
  toggle(
    key: string,
    text: string,
    mode?: ReadAloudMode,
    target?: SpeechTargetRef,
    tone?: ReadAloudTone
  ): void;
  /** Play a specific plan — the script panel uses this to render what the user just edited. */
  speakScript(key: string, request: RenderSpeechRequest): void;
  /** Play the parked clip again without paying for a second synthesis (autoplay was blocked). */
  resume(): void;
  stop(): void;
}

/** Subscribe to the shared player; safe to call from every message's meta bar. */
export function useTtsPlayback(): TtsPlaybackControls {
  const snapshot = useSyncExternalStore(
    ttsPlayback.subscribe,
    ttsPlayback.getSnapshot,
    ttsPlayback.getSnapshot
  );

  const toggle = useCallback((
    key: string,
    text: string,
    mode: ReadAloudMode = 'direct',
    target?: SpeechTargetRef,
    tone: ReadAloudTone = 'default'
  ) => {
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
    void ttsPlayback.speak(key, text, mode, target, tone);
  }, []);

  const speakScript = useCallback((key: string, request: RenderSpeechRequest) => {
    void ttsPlayback.speakScript(key, request);
  }, []);

  const resume = useCallback(() => {
    void ttsPlayback.resume();
  }, []);

  const stop = useCallback(() => ttsPlayback.stop(), []);

  // Every meta bar calls this hook; a fresh object each render would churn their props for nothing.
  return useMemo(
    () => ({ ...snapshot, toggle, speakScript, resume, stop }),
    [snapshot, toggle, speakScript, resume, stop]
  );
}
