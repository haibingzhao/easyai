import { useCallback, useEffect, useRef, useState } from 'react';
import { asrService } from '@/services/asr-service';
import { encodeWav, downmixTo16k } from '@/utils/wav-encoder';
import { readMessageEditorText } from '@/utils/attachment-utils';
import { i18n } from '@/utils/i18n';

export type VoicePhase = 'idle' | 'recording' | 'refining';

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
/** Context handed to the refine model, both sides. */
const CONTEXT_WINDOW_CHARS = 2_000;

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
}

interface UseVoiceInputOptions {
  editorRef: React.RefObject<HTMLDivElement>;
  /** Whether the dictation-refine task model is configured; read live at stop time. */
  isRefineEnabled: () => boolean;
  /** Re-sync the caller's editorValue state after every DOM write. */
  onEditorChanged: () => void;
  onError: (message: string) => void;
}

/**
 * Chat-composer dictation: near-real-time draft transcription plus per-pause
 * whole-sentence retranscription, and one context-aware LLM rewrite at stop.
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

  const teardown = useCallback((session: Session) => {
    session.stopped = true;
    session.worklet.port.onmessage = null;
    session.stream.getTracks().forEach((t) => t.stop());
    void session.ctx.close().catch(() => undefined);
    if (sessionRef.current === session) sessionRef.current = null;
    if (analyserRef.current === session.analyser) analyserRef.current = null;
  }, []);

  /** A backend transcription error is not transient here — close the mic instead of retrying. */
  const failSession = useCallback((session: Session) => {
    if (session.stopped) return;
    teardown(session);
    if (!session.draftFailureReported) {
      session.draftFailureReported = true;
      onError(i18n('Transcription failed; voice input stopped'));
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
      .catch(() => {
        failSession(session);
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

  const start = useCallback(async () => {
    if (sessionRef.current || startingRef.current) return;
    const editor = editorRef.current;
    if (!editor) return;
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
      };
      session.current = openSlot(session);
      sessionRef.current = session;
      analyserRef.current = analyser;
      worklet.port.onmessage = (ev: MessageEvent<Float32Array>) => onFrame(session, ev.data);
      setPhase('recording');
    } catch (e) {
      stream.getTracks().forEach((t) => t.stop());
      onError(e instanceof Error ? e.message : i18n('Microphone unavailable or permission denied'));
    } finally {
      startingRef.current = false;
    }
  }, [editorRef, onError, onFrame, openSlot]);

  /** Editor text strictly outside the current round's slot range, via scratch clones. */
  const contextAround = useCallback((session: Session): { before: string; after: string } => {
    const editor = editorRef.current;
    const first = session.slots[0]?.el;
    const last = session.slots[session.slots.length - 1]?.el;
    if (!editor || !first || !last) return { before: '', after: '' };
    const head = document.createElement('div');
    const tail = document.createElement('div');
    let walk: 'head' | 'skip' | 'tail' = 'head';
    for (const node of Array.from(editor.childNodes)) {
      if (node === first) {
        // A single-slot round ends at `first`; there is no middle to skip.
        walk = session.slots.length === 1 ? 'tail' : 'skip';
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
    // Release the mic first: every sample is already captured in buffers, and
    // awaiting in-flight transcriptions before teardown kept the tab in
    // recording state (and the worklet feeding frames) for no reason.
    teardown(session);
    // closeSentence covers all audio since the last sentence boundary,
    // including whatever the draft window had not flushed yet.
    closeSentence(session, false);
    await Promise.allSettled([...session.inFlight]);

    const roundText = session.slots.map((s) => s.el.textContent ?? '').join('');
    if (isRefineEnabled() && roundText.trim().length >= REFINE_MIN_CHARS) {
      setPhase('refining');
      const { before, after } = contextAround(session);
      try {
        const refined = await Promise.race([
          asrService.refineText(roundText, before, after),
          new Promise<never>((_, reject) => setTimeout(() => reject(new Error('refine timeout')), REFINE_TIMEOUT_MS)),
        ]);
        // If the user edited or deleted the region meanwhile, keep their text.
        const current = session.slots.map((s) => s.el.textContent ?? '').join('');
        if (refined && current === roundText) {
          session.slots.forEach((s, i) => {
            if (!s.el.isConnected) editorRef.current?.appendChild(s.el);
            s.el.textContent = i === 0 ? refined : '';
          });
          onEditorChanged();
        }
      } catch {
        // raw transcription stands
      }
    }
    setPhase('idle');
  }, [closeSentence, contextAround, editorRef, isRefineEnabled, onEditorChanged, teardown]);

  // Release the microphone if the composer unmounts mid-recording.
  useEffect(() => () => {
    const session = sessionRef.current;
    if (session) teardown(session);
  }, [teardown]);

  return { phase, start, stopAndFinish, analyserRef };
}
