import { useCallback, useEffect, useRef, useState } from 'react';
import { asrService, asrUnavailableNotice, type AsrTransport } from '@/services/asr-service';
import { AsrBackpressureError, AsrRealtimeSession, type AsrRealtimeEvent } from '@/services/asr-realtime-service';
import { PcmStreamEncoder, downmixChannels } from '@/audio/pcm-stream';
import { SentenceMerger } from '@/audio/sentence-merge';
import { encodeWav, downmixTo16k } from '@/utils/wav-encoder';
import type { DictationSink, DictationSlotHandle } from '@/hooks/dictation-sink';
import { i18n } from '@/utils/i18n';

/** `connecting` belongs to the streaming channel: the socket must be ready before we invite speech. */
export type VoicePhase = 'idle' | 'connecting' | 'recording' | 'processing';

/** A failed correction pass degrades to "no better text" so the visible draft still gets committed. */
async function tryTranscribe(samples: Float32Array): Promise<string> {
  try {
    return await asrService.transcribeSegment(encodeWav(samples));
  } catch {
    return '';
  }
}

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
/**
 * Streaming rounds get a slightly longer tail budget: the service still has to flush its own
 * upstream task and return the last sentences, but it must not hold the composer either.
 */
const REALTIME_FINALIZE_TIMEOUT_MS = 3_000;
/** Shown when capture ends without the user pressing stop. */
const INTERRUPT_NOTICE = 'Voice input stopped (screen off or app switched away); captured audio was kept';

interface Slot {
  handle: DictationSlotHandle;
  /** Draft fragments keyed by issue order; rendering joins them by key, immune to out-of-order responses. */
  fragments: Map<number, string>;
  drafts: Promise<void>[];
  lastWritten: string;
  finalized: boolean;
}

/** State of the streaming channel for one round; unused when the round runs over HTTP. */
interface RealtimeRound {
  encoder: PcmStreamEncoder;
  merger: SentenceMerger;
  client: AsrRealtimeSession | null;
  /** Frames (and therefore speech) may only flow once the service confirmed `ready`. */
  ready: boolean;
  /** Resolves on the first terminal frame so stop can bound its wait. */
  settled: Promise<void>;
  settle: () => void;
}

interface Session {
  ctx: AudioContext;
  stream: MediaStream;
  analyser: AnalyserNode;
  worklet: AudioWorkletNode;
  channel: 'http' | 'ws';
  realtime: RealtimeRound | null;
  slots: Slot[];
  current: Slot;
  seq: number;
  draftBuf: Float32Array[];
  draftSamples: number;
  sentenceBuf: Float32Array[];
  sentenceSamples: number;
  silenceMs: number;
  inFlight: Set<Promise<unknown>>;
  /** Serializes final-sentence commits so editor sinks insert sentences in spoken order. */
  finalChain: Promise<unknown>;
  stopped: boolean;
  draftFailureReported: boolean;
  /** The round was finalised; further downstream frames must not write text or change phase. */
  closed: boolean;
  /** The round was abandoned (timeout, error); late results must not reappear in the editor. */
  abandoned: boolean;
  wakeLock: WakeLockSentinel | null;
}

export interface UseVoiceInputOptions {
  /** Where dictated text is rendered; owns editor-specific writes and the caller's state sync. */
  sink: DictationSink;
  /** Whether the dictation-refine task model is configured; read live at stop time to decide whether to offer polish. */
  isRefineEnabled: () => boolean;
  onError: (message: string) => void;
}

function deferred(): { promise: Promise<void>; settle: () => void } {
  let settle!: () => void;
  const promise = new Promise<void>((resolve) => {
    settle = resolve;
  });
  return { promise, settle };
}

/**
 * Dictation with two interchangeable channels behind one round lifecycle.
 *
 * The channel is chosen by the server (`GET /api/asr/availability`), never by looking at the model
 * id here: `WEBSOCKET` streams continuous PCM and receives sentence snapshots, `HTTP` keeps the
 * existing 2-second draft plus per-pause retranscription. Microphone permission, the AudioWorklet,
 * the wake lock, the round/epoch bookkeeping, stop/cancel and the manual-edit protection are
 * implemented once for both.
 *
 * Text rendering is delegated to a DictationSink: the chat composer writes one
 * `<span data-dict-slot>` per sentence, while the article editor commits finalized sentences into
 * Tiptap. Sentence replacement, manual-edit conflict detection and multi-round append all stay
 * sink-local.
 */
export function useVoiceInput({ sink, isRefineEnabled, onError }: UseVoiceInputOptions) {
  const [phase, setPhase] = useState<VoicePhase>('idle');
  const sessionRef = useRef<Session | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  // start() awaits getUserMedia/addModule before sessionRef exists — without this
  // guard a second click during that window creates a second, unstoppable session.
  const startingRef = useRef(false);
  /**
   * False once the composer is gone. The awaits in start() (permission prompt, worklet load,
   * handshake) can outlive the component, and releasing the microphone only works if the session
   * exists — so each await has to check this instead of assuming the UI is still there.
   */
  const aliveRef = useRef(true);
  /** An interrupt fired while the page was hidden; the notice waits for the user to return. */
  const pendingInterruptNoticeRef = useRef(false);
  /** Manual polish in flight; drives the spinner on the polish action. */
  const [refining, setRefining] = useState(false);
  /** Whether to offer the manual polish action for the last completed round. */
  const [canRefine, setCanRefine] = useState(false);
  /** Slots of the last completed round — the region the manual polish rewrites. */
  const lastRoundRef = useRef<DictationSlotHandle[]>([]);

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
   * End the round exactly once: close unfinished sentences, drop the socket, release the media and
   * restore `idle`. Every exit path goes through here, so a late `done` cannot reopen a round whose
   * text was already committed as partial.
   */
  const closeRound = useCallback((session: Session) => {
    if (session.closed) return;
    session.closed = true;
    const realtime = session.realtime;
    if (realtime) {
      // Sentences the service never finalised still hold visible text: keep it, close it, and let the
      // editor commit what was spoken instead of throwing the round away.
      realtime.merger.finalizeRemaining();
      realtime.client?.dispose();
      realtime.settle();
      teardown(session);
      lastRoundRef.current = realtime.merger.slots();
      if (isRefineEnabled() && realtime.merger.text().trim().length >= REFINE_MIN_CHARS) setCanRefine(true);
    }
    setPhase('idle');
  }, [isRefineEnabled, teardown]);

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
    if (session.closed) {
      teardown(session);
      return;
    }
    session.abandoned = true;
    session.realtime?.merger.finalizeRemaining();
    session.realtime?.client?.dispose();
    session.realtime?.settle();
    session.closed = true;
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

  /** Live frames from the streaming service: one slot per sentence, snapshot-replaced. */
  const onRealtimeEvent = useCallback((session: Session, event: AsrRealtimeEvent) => {
    const realtime = session.realtime;
    if (!realtime) return;
    if (event.type === 'ready') {
      realtime.ready = true;
      // The worklet is only allowed to produce audio once the service is listening; its credit
      // window turns "main thread stalled" into a bounded, reported failure instead of a backlog.
      session.worklet.port.postMessage({ type: 'flow-control' });
      // A stop pressed while `connecting` wins: it must not bring the waveform back for a session
      // whose microphone is already released.
      if (!session.closed && !session.stopped) setPhase('recording');
      return;
    }
    if (event.type === 'sentence') {
      if (session.abandoned || session.closed) return;
      // Writes go through the sink, so a manual edit sticks and a late draft cannot reorder slots.
      realtime.merger.apply(event.sentenceId, event.text, event.isFinal);
      return;
    }
    if (session.closed) return;
    if (event.type === 'done') {
      if (event.notice === 'ROUND_LIMIT') onError(i18n('Voice input reached its 10 minute limit'));
      else if (event.notice === 'TEXT_LIMIT') onError(i18n('Voice input reached its length limit for this recording'));
      // `done` should have closed every sentence. Anything still open (or a sentence-id gap) means the
      // transcript is partial: keep the text, but say so instead of presenting it as complete.
      else if (realtime.merger.isPartial) onError(i18n('Some sentences were kept without a final correction'));
      closeRound(session);
      return;
    }
    if (event.type === 'error') {
      // Keep whatever was already transcribed, and say so: a silent truncation reads as a bad model.
      session.abandoned = true;
      onError(`${i18n('Transcription failed; voice input stopped')} (${event.code})`);
      closeRound(session);
      return;
    }
    if (event.type === 'cancelled') {
      closeRound(session);
      return;
    }
    // `closed`: the socket went away without a terminal frame, so the round is incomplete.
    session.abandoned = true;
    onError(i18n('The recognition connection closed unexpectedly; captured text was kept'));
    closeRound(session);
  }, [closeRound, onError]);

  const renderSlot = useCallback((slot: Slot, final: boolean) => {
    const text = [...slot.fragments.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([, t]) => t)
      .join('');
    slot.handle.setText(text, final);
    slot.lastWritten = slot.handle.getText();
  }, []);

  const openSlot = useCallback((session: Session): Slot => {
    const handle = sink.openSlot();
    const slot: Slot = { handle, fragments: new Map(), drafts: [], lastWritten: '', finalized: false };
    session.slots.push(slot);
    return slot;
  }, [sink]);

  const track = useCallback((session: Session, promise: Promise<unknown>) => {
    session.inFlight.add(promise);
    // `finally` returns a new promise that keeps the rejection; without the catch a failed
    // transcription surfaces as an unhandled rejection on top of the error already shown.
    void promise.catch(() => undefined).finally(() => session.inFlight.delete(promise));
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
    const draft = asrService.transcribeSegment(encodeWav(samples))
      .then((text) => {
        if (slot.finalized || !text || slot.handle.getText() !== slot.lastWritten) return;
        slot.fragments.set(seq, text);
        renderSlot(slot, false);
      })
      .catch((e: unknown) => {
        if (!slot.finalized) failSession(session, e);
      });
    slot.drafts.push(draft);
    track(session, draft);
  }, [failSession, renderSlot, track]);

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

    if (samples.length >= MIN_SENTENCE_SAMPLES || slot.drafts.length > 0 || slot.fragments.size > 0) {
      const transcription = samples.length >= MIN_SENTENCE_SAMPLES
        ? tryTranscribe(samples)
        : Promise.resolve('');
      const commit = async () => {
        const text = await transcription;
        // A failed correction must not discard drafts that are still in flight.
        if (!text) await Promise.allSettled(slot.drafts);
        slot.finalized = true;
        if (slot.handle.getText() !== slot.lastWritten) return;
        if (text) {
          slot.fragments.clear();
          slot.fragments.set(0, text);
        }
        renderSlot(slot, true);
      };
      // Only single-anchor sinks need ordered commits; recognition itself can run concurrently.
      const finalPass = sink.orderedCommits ? session.finalChain.then(commit) : commit();
      if (sink.orderedCommits) session.finalChain = finalPass;
      track(session, finalPass);
    } else if (!openNext && slot.handle.getText() === '') {
      slot.finalized = true;
      slot.handle.remove();
      session.slots.pop();
    }

    if (openNext) session.current = openSlot(session);
  }, [openSlot, renderSlot, sink, track]);

  /** Streaming branch: encode continuously and forward whole 100 ms frames, silence included. */
  const onRealtimeFrame = useCallback((session: Session, frame: Float32Array) => {
    const realtime = session.realtime;
    if (!realtime || !realtime.ready || !realtime.client) return;
    const client = realtime.client;
    // Multi-channel capture is mixed down before resampling; the encoder keeps its phase across
    // blocks, so long rounds cannot drift away from the service's VAD timeline.
    for (const pcm of realtime.encoder.push(downmixChannels([frame]))) {
      try {
        client.sendAudio(pcm);
      } catch (e) {
        failSession(session, e instanceof AsrBackpressureError
          ? new Error(i18n('The network is too slow for realtime recognition'))
          : e);
        return;
      }
    }
    // One credit per rendered block: the worklet may keep producing while this thread keeps up.
    session.worklet.port.postMessage({ type: 'credits', count: 1 });
  }, [failSession]);

  const onFrame = useCallback((session: Session, frame: Float32Array) => {
    if (session.stopped) return;
    if (session.channel === 'ws') {
      onRealtimeFrame(session, frame);
      return;
    }
    const mono = downmixTo16k(frame, session.ctx.sampleRate);
    let sum = 0;
    for (let i = 0; i < mono.length; i++) sum += mono[i] * mono[i];
    const rms = Math.sqrt(sum / mono.length);
    if (rms < SILENCE_RMS && session.sentenceSamples === 0) return;

    session.draftBuf.push(mono);
    session.draftSamples += mono.length;
    session.sentenceBuf.push(mono);
    session.sentenceSamples += mono.length;
    session.silenceMs = rms < SILENCE_RMS ? session.silenceMs + (mono.length / 16) : 0;

    if (session.draftSamples >= DRAFT_SAMPLES) flushDraft(session);
    const pauseHit = session.silenceMs >= SILENCE_MS && session.sentenceSamples >= MIN_SENTENCE_SAMPLES;
    if (pauseHit || session.sentenceSamples >= SENTENCE_MAX_SAMPLES) closeSentence(session, true);
  }, [closeSentence, flushDraft, onRealtimeFrame]);

  /** Worklet control frames (credit accounting, overflow) arrive on the same port as audio. */
  const onWorkletMessage = useCallback((session: Session, data: unknown) => {
    if (data instanceof Float32Array) {
      onFrame(session, data);
      return;
    }
    if (!data || typeof data !== 'object') return;
    if ((data as { type?: string }).type === 'overflow') {
      // The main thread stopped draining: report it instead of silently dropping speech.
      failSession(session, new Error(i18n('The network is too slow for realtime recognition')));
    }
  }, [failSession, onFrame]);

  /**
   * Streaming stop barrier: send the encoder's tail, then `finish`, then close the microphone.
   * Closing capture first would lose the final partial frame, which is usually the last syllable.
   */
  const finishRealtimeRound = useCallback(async (session: Session) => {
    const realtime = session.realtime;
    if (!realtime) return;
    const client = realtime.client;
    if (client && !client.isTerminated) {
      const tail = realtime.encoder.flush();
      if (tail) {
        try {
          client.sendAudio(tail);
        } catch {
          // Budget exhausted at the very end: finish anyway, the service reports the shortened round.
        }
      }
      client.finish();
    }
    teardown(session);

    let timer: ReturnType<typeof setTimeout> | undefined;
    const completed = await Promise.race([
      realtime.settled.then(() => true),
      new Promise<boolean>((resolve) => { timer = setTimeout(() => resolve(false), REALTIME_FINALIZE_TIMEOUT_MS); }),
    ]);
    if (timer) clearTimeout(timer);
    if (!completed) {
      // Past the budget the round is no longer trustworthy: cancel upstream, keep the text that is
      // already visible, and refuse any late result so it cannot rewrite what the user sees.
      session.abandoned = true;
      client?.cancel();
    }
    closeRound(session);
  }, [closeRound, teardown]);

  const stopAndFinish = useCallback(async () => {
    const session = sessionRef.current;
    if (!session || session.stopped) return;
    // Flip to the processing state the instant stop is pressed. The final-sentence
    // transcription below is network-bound; leaving the UI in `recording` through it
    // kept the dead stop button + waveform on screen with zero feedback, which read
    // as a frozen tab.
    setPhase('processing');
    if (session.channel === 'ws') {
      await finishRealtimeRound(session);
      return;
    }
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
    lastRoundRef.current = session.slots.map((s) => s.handle);
    const roundText = sink.roundText(lastRoundRef.current);
    if (isRefineEnabled() && roundText.trim().length >= REFINE_MIN_CHARS) setCanRefine(true);
    setPhase('idle');
  }, [closeSentence, finishRealtimeRound, isRefineEnabled, sink, teardown]);

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
    // A new round supersedes the previous one's polish offer.
    setCanRefine(false);
    startingRef.current = true;
    try {
      // Read the channel decision once per round and snapshot it: switching models mid-round would
      // move the audio to a different endpoint while the user is already speaking.
      const availability = await asrService.availability().catch(() => null);
      if (!aliveRef.current) return;
      const transport: AsrTransport = availability?.transport ?? 'NONE';
      if (transport === 'NONE') {
        onError(i18n(asrUnavailableNotice(availability)));
        return;
      }
      let stream: MediaStream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1 } });
      } catch {
        onError(i18n('Microphone unavailable or permission denied'));
        return;
      }
      // The permission prompt can sit open for seconds; a composer that went away meanwhile must
      // not leave the microphone live with nothing holding a reference to it.
      if (!aliveRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      try {
        const ctx = new AudioContext({ sampleRate: 16_000 });
        await ctx.audioWorklet.addModule(new URL('../audio/capture-processor.js', import.meta.url).toString());
        if (!aliveRef.current) {
          stream.getTracks().forEach((t) => t.stop());
          void ctx.close().catch(() => undefined);
          return;
        }
        const source = ctx.createMediaStreamSource(stream);
        const analyser = ctx.createAnalyser();
        analyser.fftSize = 256;
        const worklet = new AudioWorkletNode(ctx, 'capture-processor');
        source.connect(analyser);
        analyser.connect(worklet);
        worklet.connect(ctx.destination);

        const session: Session = {
          ctx, stream, analyser, worklet,
          channel: transport === 'WEBSOCKET' ? 'ws' : 'http',
          realtime: null,
          slots: [], current: null as unknown as Slot, seq: 0,
          draftBuf: [], draftSamples: 0,
          sentenceBuf: [], sentenceSamples: 0,
          silenceMs: 0, inFlight: new Set(), finalChain: Promise.resolve(),
          stopped: false, draftFailureReported: false, closed: false, abandoned: false,
          wakeLock: null,
        };
        if (session.channel === 'ws') {
          const settled = deferred();
          session.realtime = {
            // The browser may ignore the requested 16 kHz context, so the encoder works from the
            // rate the context actually reports instead of assuming one.
            encoder: new PcmStreamEncoder(ctx.sampleRate),
            merger: new SentenceMerger(sink),
            client: null,
            ready: false,
            settled: settled.promise,
            settle: settled.settle,
          };
        }
        // The page can go away while permissions and the worklet load; starting
        // capture is impossible then, so fail cleanly instead of showing a live
        // recorder that is not receiving audio.
        if (document.visibilityState === 'hidden') {
          teardown(session);
          pendingInterruptNoticeRef.current = true;
          return;
        }
        if (!aliveRef.current) {
          teardown(session);
          return;
        }
        sink.beginRound();
        if (session.channel === 'http') session.current = openSlot(session);
        sessionRef.current = session;
        analyserRef.current = analyser;
        ctx.onstatechange = () => {
          // iOS reports lock-screen as "interrupted"; any non-running state means
          // capture is dead, so finalize instead of leaving a fake recorder open.
          if (ctx.state !== 'running') interrupt(session);
        };
        worklet.port.onmessage = (ev: MessageEvent<unknown>) => onWorkletMessage(session, ev.data);
        // A revoked permission or an unplugged headset ends the track while the graph keeps
        // producing silence, so the level meter never fires and nothing else notices. Without this
        // the UI shows a live recorder that is transcribing an empty room.
        const [micTrack] = stream.getAudioTracks();
        if (micTrack) {
          micTrack.addEventListener('ended', () => {
            if (sessionRef.current !== session || session.stopped) return;
            onError(i18n('The microphone was disconnected or its permission was revoked'));
            void stopAndFinish();
          }, { once: true });
        }

        if (session.channel === 'ws') {
          // The microphone is open but silenced: the worklet only starts producing once `ready`
          // grants credits, so nothing is spoken into a socket the service is not reading yet.
          setPhase('connecting');
          try {
            const client = await AsrRealtimeSession.open({
              onEvent: (event) => onRealtimeEvent(session, event),
            });
            // A stop or an unmount can win the race against the handshake: the round is already
            // closed by then, so nothing else would ever release this socket, its heartbeat or the
            // service-side round.
            if (session.closed || session.stopped || !aliveRef.current) {
              client.dispose();
              return;
            }
            session.realtime!.client = client;
          } catch (e) {
            const detail = e instanceof Error ? e.message : '';
            const headline = i18n('Could not open the realtime recognition channel');
            session.closed = true;
            teardown(session);
            onError(detail ? `${headline} (${detail})` : headline);
            setPhase('idle');
            return;
          }
          // A cancel during the handshake already closed the round and disposed the socket.
          if (session.closed || session.stopped) return;
          if (session.realtime!.ready) setPhase('recording');
        } else {
          setPhase('recording');
        }
        acquireWakeLock(session);
      } catch (e) {
        stream.getTracks().forEach((t) => t.stop());
        onError(e instanceof Error ? e.message : i18n('Microphone unavailable or permission denied'));
      }
    } finally {
      startingRef.current = false;
    }
  }, [acquireWakeLock, interrupt, onError, onRealtimeEvent, onWorkletMessage, openSlot, sink, stopAndFinish, teardown]);

  /**
   * Manual polish: LLM-rewrite the last dictation round in place, using surrounding
   * editor text as context. Reads the slots' current text so edits made after stopping
   * are honored; if the region changes during the call, the user's text wins.
   */
  const refine = useCallback(async () => {
    if (refining) return;
    const handles = lastRoundRef.current.filter((handle) => handle.isConnected());
    if (handles.length === 0) { setCanRefine(false); return; }
    const roundText = sink.roundText(handles);
    if (roundText.trim().length < REFINE_MIN_CHARS) return;
    setRefining(true);
    const { before, after } = sink.contextAround(handles);
    try {
      const refined = await Promise.race([
        asrService.refineText(roundText, before, after),
        new Promise<never>((_, reject) => setTimeout(() => reject(new Error('refine timeout')), REFINE_TIMEOUT_MS)),
      ]);
      const current = sink.roundText(handles);
      if (refined && current === roundText) {
        sink.replaceRound(handles, refined);
      }
    } catch {
      // raw transcription stands
    } finally {
      setRefining(false);
      setCanRefine(false);
    }
  }, [refining, sink]);

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
    aliveRef.current = false;
    const session = sessionRef.current;
    if (!session) return;
    // Closing the round here is what keeps an in-flight stop() from calling setState on a component
    // that is gone: closeRound returns immediately for a session already marked closed.
    session.closed = true;
    session.realtime?.client?.dispose();
    session.realtime?.settle();
    teardown(session);
  }, [teardown]);

  return { phase, refining, canRefine, start, stopAndFinish, refine, dismissRefine, analyserRef };
}
