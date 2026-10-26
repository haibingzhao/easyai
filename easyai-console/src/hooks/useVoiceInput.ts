import { useCallback, useEffect, useRef, useState } from 'react';
import { asrService } from '@/services/asr-service';
import { encodeWav, downmixTo16k } from '@/utils/wav-encoder';
import { readMessageEditorText } from '@/utils/attachment-utils';
import { i18n } from '@/utils/i18n';

export type VoicePhase = 'idle' | 'recording' | 'processing';

/** Draft flush cadence: a fresh chunk of this many 16k samples is transcribed right away. */
const DRAFT_SAMPLES = 32_000;
/** Anything shorter than 0.3s is noise, not speech. */
const MIN_CHUNK_SAMPLES = 4_800;
/** A sentence may not exceed 10s even without a pause. */
const SENTENCE_MAX_SAMPLES = 160_000;
/** Below 0.5s of audio a sentence close is skipped entirely. */
const MIN_SENTENCE_SAMPLES = 8_000;
const SILENCE_RMS = 0.01;
const SILENCE_MS = 600;
/** Refine is pointless (and rude to the model) for fragments. */
const REFINE_MIN_CHARS = 6;
const REFINE_TIMEOUT_MS = 20_000;
/**
 * Upper bound on waiting for the final-sentence retranscription at stop. The draft
 * text is already on screen, so this pass is only a correction — on a slow network
 * it must not hold the composer hostage for seconds. Late results still land via the
 * slot's conflict check.
 */
const FINALIZE_TIMEOUT_MS = 2_500;
/** Context handed to the refine model, both sides. */
const CONTEXT_WINDOW_CHARS = 2_000;
/** Shown when capture ends without the user pressing stop. */
const INTERRUPT_NOTICE = 'Voice input stopped (screen off or app switched away); captured audio was kept';

interface Slot {
  el: HTMLSpanElement;
  /** Draft fragments keyed by issue order; rendering joins them by key, immune to out-of-order responses. */
  fragments: Map<number, string>;
  lastWritten: string;
  finalized: boolean;
}

interface Session {
  ctx: AudioContext;
  stream: MediaStream;
  analyser: AnalyserNode;
  worklet: AudioWorkletNode;
  slots: Slot[];
  current: Slot;
  seq: number;
  draftBuf: Float32Array[];
  draftSamples: number;
  sentenceBuf: Float32Array[];
  sentenceSamples: number;
  silenceMs: number;
  inFlight: Set<Promise<unknown>>;
  stopped: boolean;
  draftFailureReported: boolean;
  wakeLock: WakeLockSentinel | null;
}

export interface UseVoiceInputOptions {
  editorRef: React.RefObject<HTMLDivElement>;
  /** Whether the dictation-refine task model is configured; read live at stop time to decide whether to offer polish. */
  isRefineEnabled: () => boolean;
  /** Re-sync the caller's editorValue state after every DOM write. */
  onEditorChanged: () => void;
  onError: (message: string) => void;
}

/**
 * Chat-composer dictation: near-real-time draft transcription plus per-pause
 * whole-sentence retranscription. The context-aware LLM rewrite is manual — after
 * a round ends the composer offers a polish action the user triggers on demand,
 * so a slow network never blocks stopping.
 *
 * Text lands in `<span data-dict-slot>` elements appended to the editor end —
 * one slot per sentence, never character offsets — so sentence replacement,
 * manual-edit conflict detection and multi-round append all stay DOM-local.
 */
export function useVoiceInput({ editorRef, isRefineEnabled, onEditorChanged, onError }: UseVoiceInputOptions) {
  const [phase, setPhase] = useState<VoicePhase>('idle');
  const sessionRef = useRef<Session | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  // start() awaits getUserMedia/addModule before sessionRef exists — without this
  // guard a second click during that window creates a second, unstoppable session.
  const startingRef = useRef(false);
  /** An interrupt fired while the page was hidden; the notice waits for the user to return. */
  const pendingInterruptNoticeRef = useRef(false);
  /** Manual polish in flight; drives the spinner on the polish action. */
  const [refining, setRefining] = useState(false);
  /** Whether to offer the manual polish action for the last completed round. */
  const [canRefine, setCanRefine] = useState(false);
  /** Slots of the last completed round — the region the manual polish rewrites. */
  const lastRoundRef = useRef<Slot[]>([]);

  const teardown = useCallback((session: Session) => {
    session.stopped = true;
    session.ctx.onstatechange = null;
    if (session.wakeLock) {
      void session.wakeLock.release().catch(() => undefined);
      session.wakeLock = null;
    }
    session.worklet.port.onmessage = null;
    session.stream.getTracks().forEach((t) => t.stop());
    void session.ctx.close().catch(() => undefined);
    if (sessionRef.current === session) sessionRef.current = null;
    if (analyserRef.current === session.analyser) analyserRef.current = null;
  }, []);

  /**
   * Phones drop microphone capture the moment the screen turns off, so a display
   * that auto-locks mid-dictation would silently end the recording. Failure to
   * acquire (unsupported browser, battery saver, iframe policy) is not fatal —
   * the visibility handling below still finalizes the captured audio cleanly.
   */
  const acquireWakeLock = useCallback((session: Session) => {
    if (!('wakeLock' in navigator)) return;
    void navigator.wakeLock.request('screen')
      .then((sentinel) => {
        if (session.stopped) {
          void sentinel.release().catch(() => undefined);
          return;
        }
        session.wakeLock = sentinel;
      })
      .catch(() => undefined);
  }, []);

  /** A backend transcription error is not transient here — close the mic instead of retrying. */
  const failSession = useCallback((session: Session, cause?: unknown) => {
    if (session.stopped) return;
    teardown(session);
    if (!session.draftFailureReported) {
      session.draftFailureReported = true;
      // The status code is the only diagnosis the backend exposes (Spring keeps
      // ResponseStatusException reasons out of the body), so never drop it.
      const detail = cause instanceof Error ? cause.message : '';
      const headline = i18n('Transcription failed; voice input stopped');
      onError(detail ? `${headline} (${detail})` : headline);
    }
    setPhase('idle');
  }, [onError, teardown]);

  const renderSlot = useCallback((slot: Slot) => {
    const editor = editorRef.current;
    if (!editor) return;
    if (!slot.el.isConnected) editor.appendChild(slot.el);
    const text = [...slot.fragments.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([, t]) => t)
      .join('');
    slot.el.textContent = text;
    slot.lastWritten = text;
    onEditorChanged();
  }, [editorRef, onEditorChanged]);

  const openSlot = useCallback((session: Session): Slot => {
    const editor = editorRef.current!;
    const el = document.createElement('span');
    el.dataset.dictSlot = String(session.slots.length);
    const slot: Slot = { el, fragments: new Map(), lastWritten: '', finalized: false };
    editor.appendChild(el);
    session.slots.push(slot);
    return slot;
  }, [editorRef]);

  const track = useCallback((session: Session, promise: Promise<unknown>) => {
    session.inFlight.add(promise);
    void promise.finally(() => session.inFlight.delete(promise));
  }, []);

  const flushDraft = useCallback((session: Session) => {
    const samples = new Float32Array(session.draftSamples);
    let off = 0;
    for (const b of session.draftBuf) { samples.set(b, off); off += b.length; }
    session.draftBuf = [];
    session.draftSamples = 0;
    if (samples.length < MIN_CHUNK_SAMPLES) return;
    // A draft window that was silent throughout has nothing to transcribe —
    // skip the paid request (silenceMs covers this window and then some).
    if (session.silenceMs >= samples.length / 16) return;
    const slot = session.current;
    const seq = session.seq++;
    track(session, asrService.transcribeSegment(encodeWav(samples))
      .then((text) => {
        if (slot.finalized || !text) return;
        slot.fragments.set(seq, text);
        renderSlot(slot);
      })
      .catch((e: unknown) => {
        failSession(session, e);
      }));
  }, [failSession, onError, renderSlot, track]);

  /** Close the open sentence: finalize the slot, retranscribe the whole sentence, open the next. */
  const closeSentence = useCallback((session: Session, openNext: boolean) => {
    const slot = session.current;
    const samples = new Float32Array(session.sentenceSamples);
    let off = 0;
    for (const b of session.sentenceBuf) { samples.set(b, off); off += b.length; }
    session.sentenceBuf = [];
    session.sentenceSamples = 0;
    // The draft window overlaps the sentence just captured; drop it so its
    // fragments cannot land in the next slot and duplicate the sentence text.
    session.draftBuf = [];
    session.draftSamples = 0;
    session.silenceMs = 0;

    if (samples.length >= MIN_SENTENCE_SAMPLES) {
      slot.finalized = true;
      track(session, asrService.transcribeSegment(encodeWav(samples)).then((text) => {
        if (!text) return;
        // Manual-edit conflict: only replace a slot the user has not touched.
        if (slot.el.textContent !== slot.lastWritten) return;
        slot.fragments.clear();
        slot.fragments.set(0, text);
        renderSlot(slot);
      }).catch(() => { /* the draft text stands; a failed correction pass must not end dictation */ }));
    }

    if (openNext) {
      session.current = openSlot(session);
    } else if (slot.fragments.size === 0 && slot.el.textContent === '') {
      slot.el.remove();
      session.slots.pop();
    }
  }, [openSlot, renderSlot, track]);

  const onFrame = useCallback((session: Session, frame: Float32Array) => {
    if (session.stopped) return;
    const mono = downmixTo16k(frame, session.ctx.sampleRate);
    session.draftBuf.push(mono);
    session.draftSamples += mono.length;
    session.sentenceBuf.push(mono);
    session.sentenceSamples += mono.length;

    let sum = 0;
    for (let i = 0; i < mono.length; i++) sum += mono[i] * mono[i];
    const rms = Math.sqrt(sum / mono.length);
    session.silenceMs = rms < SILENCE_RMS ? session.silenceMs + (mono.length / 16) : 0;

    if (session.draftSamples >= DRAFT_SAMPLES) flushDraft(session);
    const pauseHit = session.silenceMs >= SILENCE_MS && session.sentenceSamples >= MIN_SENTENCE_SAMPLES;
    if (pauseHit || session.sentenceSamples >= SENTENCE_MAX_SAMPLES) closeSentence(session, true);
  }, [closeSentence, flushDraft]);

  /** Editor text strictly outside the given slot range, via scratch clones. */
  const contextAround = useCallback((slots: Slot[]): { before: string; after: string } => {
    const editor = editorRef.current;
    const first = slots[0]?.el;
    const last = slots[slots.length - 1]?.el;
    if (!editor || !first || !last) return { before: '', after: '' };
    const head = document.createElement('div');
    const tail = document.createElement('div');
    let walk: 'head' | 'skip' | 'tail' = 'head';
    for (const node of Array.from(editor.childNodes)) {
      if (node === first) {
        // A single-slot round ends at `first`; there is no middle to skip.
        walk = slots.length === 1 ? 'tail' : 'skip';
        continue;
      }
      if (node === last) { walk = 'tail'; continue; }
      if (walk === 'head') head.appendChild(node.cloneNode(true));
      else if (walk === 'tail') tail.appendChild(node.cloneNode(true));
    }
    return {
      before: readMessageEditorText(head).slice(-CONTEXT_WINDOW_CHARS),
      after: readMessageEditorText(tail).slice(0, CONTEXT_WINDOW_CHARS),
    };
  }, [editorRef]);

  const stopAndFinish = useCallback(async () => {
    const session = sessionRef.current;
    if (!session || session.stopped) return;
    // Flip to the processing state the instant stop is pressed. The final-sentence
    // transcription below is network-bound; leaving the UI in `recording` through it
    // kept the dead stop button + waveform on screen with zero feedback, which read
    // as a frozen tab.
    setPhase('processing');
    // Release the mic first: every sample is already captured in buffers, and
    // awaiting in-flight transcriptions before teardown kept the tab in
    // recording state (and the worklet feeding frames) for no reason.
    teardown(session);
    // closeSentence covers all audio since the last sentence boundary,
    // including whatever the draft window had not flushed yet.
    closeSentence(session, false);
    // Bound the wait: the last-sentence correction is nice-to-have, the draft is
    // already visible. On a bad network this would otherwise lock the composer for
    // seconds; after the budget we proceed with the draft and let any late
    // correction land in the background (its conflict check protects current text).
    let finalizeTimer: ReturnType<typeof setTimeout> | undefined;
    await Promise.race([
      Promise.allSettled([...session.inFlight]),
      new Promise<void>((resolve) => { finalizeTimer = setTimeout(resolve, FINALIZE_TIMEOUT_MS); }),
    ]);
    if (finalizeTimer) clearTimeout(finalizeTimer);

    // Offer a manual polish of this round instead of blocking on an LLM rewrite —
    // the composer is usable immediately, and the user decides whether to spend it.
    lastRoundRef.current = session.slots;
    const roundText = session.slots.map((s) => s.el.textContent ?? '').join('');
    if (isRefineEnabled() && roundText.trim().length >= REFINE_MIN_CHARS) setCanRefine(true);
    setPhase('idle');
  }, [closeSentence, isRefineEnabled, teardown]);

  /**
   * Capture dies when the page is hidden (screen lock, app switch) or the system
   * interrupts the audio session. Finalize what was captured instead of leaving a
   * dead recorder on screen; while hidden the notice is deferred to the return to
   * foreground, otherwise the user never sees it.
   */
  const interrupt = useCallback((session: Session) => {
    if (sessionRef.current !== session || session.stopped) return;
    if (document.visibilityState === 'hidden') pendingInterruptNoticeRef.current = true;
    else onError(i18n(INTERRUPT_NOTICE));
    void stopAndFinish();
  }, [onError, stopAndFinish]);

  const start = useCallback(async () => {
    if (sessionRef.current || startingRef.current) return;
    const editor = editorRef.current;
    if (!editor) return;
    // A new round supersedes the previous one's polish offer.
    setCanRefine(false);
    startingRef.current = true;
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1 } });
    } catch {
      startingRef.current = false;
      onError(i18n('Microphone unavailable or permission denied'));
      return;
    }
    try {
      const ctx = new AudioContext({ sampleRate: 16_000 });
      await ctx.audioWorklet.addModule(new URL('../audio/capture-processor.js', import.meta.url).toString());
      const source = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 256;
      const worklet = new AudioWorkletNode(ctx, 'capture-processor');
      source.connect(analyser);
      analyser.connect(worklet);
      worklet.connect(ctx.destination);

      const session: Session = {
        ctx, stream, analyser, worklet,
        slots: [], current: null as unknown as Slot, seq: 0,
        draftBuf: [], draftSamples: 0,
        sentenceBuf: [], sentenceSamples: 0,
        silenceMs: 0, inFlight: new Set(), stopped: false, draftFailureReported: false,
        wakeLock: null,
      };
      // The page can go away while permissions and the worklet load; starting
      // capture is impossible then, so fail cleanly instead of showing a live
      // recorder that is not receiving audio.
      if (document.visibilityState === 'hidden') {
        teardown(session);
        pendingInterruptNoticeRef.current = true;
        return;
      }
      session.current = openSlot(session);
      sessionRef.current = session;
      analyserRef.current = analyser;
      ctx.onstatechange = () => {
        // iOS reports lock-screen as "interrupted"; any non-running state means
        // capture is dead, so finalize instead of leaving a fake recorder open.
        if (ctx.state !== 'running') interrupt(session);
      };
      worklet.port.onmessage = (ev: MessageEvent<Float32Array>) => onFrame(session, ev.data);
      setPhase('recording');
      acquireWakeLock(session);
    } catch (e) {
      stream.getTracks().forEach((t) => t.stop());
      onError(e instanceof Error ? e.message : i18n('Microphone unavailable or permission denied'));
    } finally {
      startingRef.current = false;
    }
  }, [acquireWakeLock, editorRef, interrupt, onError, onFrame, openSlot, teardown]);

  /**
   * Manual polish: LLM-rewrite the last dictation round in place, using surrounding
   * editor text as context. Reads the slots' current text so edits made after stopping
   * are honored; if the region changes during the call, the user's text wins.
   */
  const refine = useCallback(async () => {
    if (refining) return;
    const editor = editorRef.current;
    const slots = lastRoundRef.current.filter((s) => s.el.isConnected);
    if (!editor || slots.length === 0) { setCanRefine(false); return; }
    const roundText = slots.map((s) => s.el.textContent ?? '').join('');
    if (roundText.trim().length < REFINE_MIN_CHARS) return;
    setRefining(true);
    const { before, after } = contextAround(slots);
    try {
      const refined = await Promise.race([
        asrService.refineText(roundText, before, after),
        new Promise<never>((_, reject) => setTimeout(() => reject(new Error('refine timeout')), REFINE_TIMEOUT_MS)),
      ]);
      const current = slots.map((s) => s.el.textContent ?? '').join('');
      if (refined && current === roundText) {
        slots.forEach((s, i) => { s.el.textContent = i === 0 ? refined : ''; });
        onEditorChanged();
      }
    } catch {
      // raw transcription stands
    } finally {
      setRefining(false);
      setCanRefine(false);
    }
  }, [contextAround, editorRef, onEditorChanged, refining]);

  /** Drop the polish offer — used when the editor is cleared (sent or deleted). */
  const dismissRefine = useCallback(() => {
    setCanRefine(false);
    lastRoundRef.current = [];
  }, []);

  // Hidden pages lose the microphone, so close the round the moment it happens;
  // on return, surface the deferred notice and re-arm the wake lock if the
  // browser dropped it with the screen.
  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        const session = sessionRef.current;
        if (session) interrupt(session);
        return;
      }
      if (pendingInterruptNoticeRef.current) {
        pendingInterruptNoticeRef.current = false;
        onError(i18n(INTERRUPT_NOTICE));
      }
      const session = sessionRef.current;
      if (session && (!session.wakeLock || session.wakeLock.released)) acquireWakeLock(session);
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, [acquireWakeLock, interrupt, onError]);

  // Release the microphone if the composer unmounts mid-recording.
  useEffect(() => () => {
    const session = sessionRef.current;
    if (session) teardown(session);
  }, [teardown]);

  return { phase, refining, canRefine, start, stopAndFinish, refine, dismissRefine, analyserRef };
}
