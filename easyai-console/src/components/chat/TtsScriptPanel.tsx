import React, { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Bookmark, Loader2, Play } from 'lucide-react';
import { Dialog } from '@/components/ui/Dialog';
import { Button } from '@/components/ui/Button';
import { useTtsPlayback } from '@/hooks/useTtsPlayback';
import { MAX_PAUSE_MS, type TtsPlaybackState } from '@/services/tts-playback-controller';
import { speechScriptService } from '@/services/speech-script-service';
import {
  TtsError,
  ttsService,
  type SpeechScript,
  type SpeechSegment,
  type SpeechTargetRef,
} from '@/services/tts-service';
import { i18n } from '@/utils/i18n';

interface TtsScriptPanelProps {
  open: boolean;
  onClose: () => void;
  /** The cleaned reply text the plan is built from — the same string the speaker button sends. */
  text: string;
  /** Playback identity of the message this panel belongs to, so its progress can be highlighted. */
  speechKey: string;
  /**
   * Where the text came from. It decides which saved script is reused for this reply, and where a
   * pinned copy is filed; omitting it means "manual text", which no message ever shares.
   */
  target?: SpeechTargetRef;
}

type PanelStatus = 'loading' | 'ready' | 'failed';

/** The wire carries enum names; the UI shows words the reader understands. */
const STYLE_LABEL: Record<string, string> = {
  NARRATOR: 'Narrator',
  TEACHER: 'Teacher',
  PODCAST: 'Podcast',
  NEWS: 'News',
  STORYTELLER: 'Storyteller',
};

const styleLabel = (style: string): string => i18n(STYLE_LABEL[style] ?? style);

/** What a submitted plan is doing, as far as the shared player can tell. */
export type SubmitOutcome = 'waiting' | 'started' | 'refused' | 'cancelled';

/**
 * Decide what to do with the panel after "Read this plan".
 *
 * Closing on the click is the friendly thing right up until the server refuses the edit — the plan
 * is re-validated against the source it stored, so a rewrite can be rejected outright — and by then
 * the dialog is gone along with the sentences the user just typed. Waiting for the first clip costs
 * a beat and is the only signal that proves the edit was accepted.
 */
export function submitOutcome(
  snapshot: { state: TtsPlaybackState; activeKey: string | null; error: string | null },
  speechKey: string
): SubmitOutcome {
  // A failure keeps our key (see `TtsPlaybackSnapshot`), so this cannot be someone else's error.
  if (snapshot.activeKey === speechKey && snapshot.error) return 'refused';
  // The player no longer belongs to this message: stopped by hand, or taken over by another reply.
  // Either way there is nothing left to wait for, and the button must come back.
  if (snapshot.activeKey !== speechKey) return 'cancelled';
  return snapshot.state === 'playing' || snapshot.state === 'ready' ? 'started' : 'waiting';
}

/** How long a submitted plan may take to become audible before the panel gives the button back. */
const SUBMIT_TIMEOUT_MS = 20_000;

/**
 * Deadline for the planning call itself. A directed plan is one full Speech Direction Model
 * round-trip — the backend logs 15–20 s on a long reply — so this is deliberately generous; it only
 * exists to turn a truly stuck request into a retryable error.
 */
const PLAN_TIMEOUT_MS = 120_000;

/**
 * The plan behind a directed read-aloud: what the reply says, what the voice will say, and an edit
 * for each segment.
 *
 * Edits are a request, not a fact: the server re-validates the submitted segments against the source
 * it stored, repairs what it must and bumps the revision, so a hand-edited line can never smuggle
 * markup, an unsupported voice or an extra sentence past the fidelity gates.
 */
export const TtsScriptPanel: React.FC<TtsScriptPanelProps> = ({
  open,
  onClose,
  text,
  speechKey,
  target,
}) => {
  const tts = useTtsPlayback();
  const [status, setStatus] = useState<PanelStatus>('loading');
  const [error, setError] = useState<string | null>(null);
  const [script, setScript] = useState<SpeechScript | null>(null);
  const [drafts, setDrafts] = useState<SpeechSegment[]>([]);
  /**
   * Seconds spent planning so far. A directed plan is a full model call (the backend logs ~15–20 s
   * for a long reply), and a static line of grey text for that long reads as a hung dialog — the
   * counter is what proves the wait is progressing rather than stuck.
   */
  const [waitedSeconds, setWaitedSeconds] = useState(0);
  /** The submitted plan is on its way; the panel stays open until it is audible or refused. */
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  /** Id of the saved copy this panel is looking at; null until the user presses "Save". */
  const [savedId, setSavedId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setStatus('loading');
    setWaitedSeconds(0);
    setError(null);
    setScript(null);
    setDrafts([]);
    setSubmitting(false);
    setSubmitError(null);
    setSavedId(null);
    setSaving(false);
    setSaveError(null);
    // A hung request must not leave the dialog spinning forever: the deadline reports a failure the
    // reader can act on (retry), instead of an indefinite wait that looks like a broken panel.
    let timedOut = false;
    const deadline = setTimeout(() => {
      timedOut = true;
      controller.abort();
      setError(i18n('The read-aloud script took too long to plan; try again'));
      setStatus('failed');
    }, PLAN_TIMEOUT_MS);
    void (async () => {
      try {
        const planned = await ttsService.script(text, controller.signal, target);
        if (controller.signal.aborted) return;
        setScript(planned);
        setDrafts(planned.segments);
        setStatus('ready');
      } catch (e) {
        // The deadline above already reported this abort, and a real close needs no message.
        if (timedOut || controller.signal.aborted) return;
        setError(planErrorText(e));
        setStatus('failed');
      } finally {
        clearTimeout(deadline);
      }
    })();
    return () => {
      clearTimeout(deadline);
      controller.abort();
    };
    // `target` is an object literal at the call site: compare its parts, not its identity.
  }, [open, text, target?.type, target?.id, target?.sessionId]);

  const playing = tts.activeKey === speechKey ? tts.progress : undefined;

  // One tick per second, only while the panel is actually waiting for a plan.
  useEffect(() => {
    if (!open || status !== 'loading') return;
    const startedAt = Date.now();
    const timer = setInterval(() => {
      setWaitedSeconds(Math.floor((Date.now() - startedAt) / 1000));
    }, 1000);
    return () => clearInterval(timer);
  }, [open, status]);

  const update = useCallback((id: string, patch: Partial<SpeechSegment>) => {
    setDrafts((current) => current.map((segment) => (segment.id === id ? { ...segment, ...patch } : segment)));
  }, []);

  const read = useCallback(() => {
    if (!script || submitting) return;
    setSubmitError(null);
    setSubmitting(true);
    // Fire and follow: `speakScript` resolves when the whole plan has been spoken, which is far too
    // late to decide whether the edit was accepted, so the shared snapshot is what this watches.
    tts.speakScript(speechKey, { scriptId: script.scriptId, segments: drafts, incremental: false });
  }, [drafts, script, speechKey, submitting, tts]);

  const save = useCallback(() => {
    if (!script || saving || savedId) return;
    setSaveError(null);
    setSaving(true);
    void (async () => {
      try {
        const saved = await speechScriptService.pin({ scriptId: script.scriptId, target });
        setSavedId(saved.id);
      } catch (e) {
        setSaveError(e instanceof Error ? e.message : i18n('Could not save this script'));
      } finally {
        setSaving(false);
      }
    })();
  }, [script, saving, savedId, target]);

  useEffect(() => {
    if (!submitting) return;
    const outcome = submitOutcome(tts, speechKey);
    if (outcome === 'started') {
      setSubmitting(false);
      onClose();
      return;
    }
    if (outcome === 'refused') {
      setSubmitting(false);
      // The drafts stay in place: a refused edit is exactly when the user needs them back.
      setSubmitError(tts.error ?? i18n('Could not plan this reply'));
      return;
    }
    if (outcome === 'cancelled') {
      // Stopped, or another reply took the player. Not an error, but the button has to come back.
      setSubmitting(false);
    }
  }, [submitting, tts, speechKey, onClose]);

  // A stream that opens and then says nothing must not leave the button dead forever.
  useEffect(() => {
    if (!submitting) return;
    const timer = setTimeout(() => {
      // The request may still be running: stop it, or the audio starts after the panel already said
      // it did not, and a second attempt would collide with the server's single-flight guard.
      tts.stop();
      setSubmitting(false);
      setSubmitError(i18n('The read-aloud did not start'));
    }, SUBMIT_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [submitting, tts.stop]);

  const warnings = useMemo(() => script?.warnings ?? [], [script]);

  return (
    <Dialog open={open} onClose={onClose} title={i18n('Read-aloud script')}>
      {status === 'loading' && (
        <div className="space-y-4" data-script-planning>
          <div className="flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 shrink-0 animate-spin text-primary" aria-hidden="true" />
            <span role="status">{i18n('Planning how to read this reply…')}</span>
            <span className="ml-auto text-xs tabular-nums text-muted-foreground">{waitedSeconds}s</span>
          </div>
          <p className="text-xs text-muted-foreground">
            {waitedSeconds >= 25
              ? i18n('A long reply takes longer to divide into segments — still working.')
              : i18n('The Speech Direction Model is writing the script; this usually takes 10–20 seconds.')}
          </p>
          {/* Placeholder segments, so the dialog looks like it is filling in rather than frozen. */}
          <div className="space-y-2" aria-hidden="true">
            {[0, 1, 2].map((row) => (
              <div key={row} className="space-y-1.5 rounded-md border border-border p-2">
                <div className="h-2 w-24 animate-pulse rounded bg-muted" />
                <div className="h-2 w-full animate-pulse rounded bg-muted" />
                <div className="h-2 w-4/5 animate-pulse rounded bg-muted" />
              </div>
            ))}
          </div>
          <div className="flex justify-end">
            <Button type="button" variant="outline" size="sm" onClick={onClose}>
              {i18n('Cancel')}
            </Button>
          </div>
        </div>
      )}

      {status === 'failed' && (
        <div className="space-y-3">
          <p role="status" className="text-sm text-red-500/80">{error ?? i18n('Could not plan this reply')}</p>
          <div className="flex justify-end">
            <Button type="button" variant="outline" size="sm" onClick={onClose}>{i18n('Close')}</Button>
          </div>
        </div>
      )}

      {status === 'ready' && script && (
        <div className="space-y-3">
          {/* One line on desktop, chips on a phone. `flex` without wrap squeezed the mood into a
              two-line box and left the `·` separators stranded on the wrong row. */}
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
            {[
              styleLabel(script.style),
              script.mood,
              `${script.segments.length} ${i18n('segments')}`,
              `${i18n('revision')} ${script.revision}`,
            ].map((item, index) => (
              <Fragment key={`${item}-${index}`}>
                {index > 0 && <span className="hidden sm:inline" aria-hidden="true">·</span>}
                <span className="whitespace-nowrap rounded bg-muted/60 px-1.5 py-0.5 sm:bg-transparent sm:p-0">
                  {item}
                </span>
              </Fragment>
            ))}
          </div>

          {/* The one question this header has to answer: did my edit get kept? */}
          <div className="flex flex-wrap items-center gap-2 text-[11px]">
            {script.reused && (
              // This label is a full sentence: on a phone it takes two lines, and a two-line
              // stadium pill reads as a rendering bug, so the corners soften below `sm`.
              <span
                data-script-reused
                className="max-w-full rounded-md bg-primary/10 px-2 py-0.5 leading-snug text-primary sm:rounded-full"
              >
                {i18n('Saved earlier — reusing it instead of planning again')}
              </span>
            )}
            {savedId && (
              <span data-script-saved className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-emerald-600 dark:text-emerald-400">
                {i18n('Saved to your library')}
              </span>
            )}
            {!savedId && !script.reused && (
              <span className="text-muted-foreground">{i18n('Auto-saved · keep it to reuse later')}</span>
            )}
          </div>

          {warnings.length > 0 && (
            <ul className="space-y-1 rounded-md bg-muted/50 p-2 text-xs text-muted-foreground">
              {warnings.map((warning, index) => (
                <li key={`${warning.code}-${warning.segmentId ?? index}`}>
                  <span className="font-mono">{warning.code}</span> {warning.message}
                </li>
              ))}
            </ul>
          )}

          <ol className="space-y-3">
            {drafts.map((segment) => {
              const active = playing?.index === segment.index + 1;
              return (
                <li
                  key={segment.id}
                  data-script-segment={segment.id}
                  className={`rounded-md border p-2 ${active ? 'border-primary/60 bg-muted/40' : 'border-border'}`}
                >
                  <div className="mb-1 flex items-center gap-2 text-[11px] text-muted-foreground">
                    <span className="font-mono">{segment.index + 1}</span>
                    <span className="truncate">{segment.voice}</span>
                    {/* The plan's own count is the fallback: a read started from the bar is planned
                        incrementally and reports no total until it ends, but the panel already
                        knows how many segments it is editing. */}
                    {active && playing && (
                      <span>{i18n('Segment {index}/{total}')
                        .replace('{index}', String(playing.index))
                        .replace('{total}', String(playing.total || script.segments.length))}</span>
                    )}
                  </div>
                  {/* Source above, spoken below: the pair is the whole point of the panel — it makes
                      a rewrite visible instead of something the listener has to notice by ear. */}
                  <p className="mb-1 text-xs text-muted-foreground line-through decoration-muted-foreground/40">
                    {segment.sourceText}
                  </p>
                  <textarea
                    value={segment.spokenText}
                    onChange={(event) => update(segment.id, { spokenText: event.target.value })}
                    rows={Math.min(6, Math.max(2, Math.ceil(segment.spokenText.length / 48)))}
                    data-script-spoken={segment.id}
                    className="w-full resize-y rounded border border-border bg-background p-1 text-sm outline-none focus:border-primary"
                    aria-label={i18n('Spoken text')}
                  />
                  <label className="mt-1 flex items-center gap-2 text-[11px] text-muted-foreground">
                    {i18n('Pause after')}
                    <input
                      type="number"
                      min={0}
                      max={MAX_PAUSE_MS}
                      step={50}
                      value={segment.pauseAfterMs}
                      onChange={(event) =>
                        update(segment.id, {
                          // Clamped here, not just by the `max` attribute: a typed number ignores
                          // it, and the server would otherwise have to reject the whole edit.
                          pauseAfterMs: Math.min(MAX_PAUSE_MS, Math.max(0, Number(event.target.value) || 0)),
                        })
                      }
                      className="w-16 rounded border border-border bg-background px-1 text-[11px] outline-none focus:border-primary"
                      aria-label={i18n('Pause after')}
                    />
                    {i18n('ms')}
                  </label>
                  {/* Highlighting follows the clip's own timings; with none reported, nothing is
                      highlighted rather than guessed. */}
                  {active && playing && playing.wordIndex >= 0 && (
                    // Flex gap, not literal spaces: provider tokens carry no separators, and joining
                    // Latin words directly would read "Helloworld".
                    <p className="mt-1 flex flex-wrap gap-x-1 text-xs">
                      {playing.words.map((word, index) => (
                        <span
                          key={`${word.beginMs}-${index}`}
                          className={index === playing.wordIndex ? 'bg-primary/20 rounded-sm' : undefined}
                        >
                          {word.text}
                        </span>
                      ))}
                    </p>
                  )}
                </li>
              );
            })}
          </ol>

          {submitError && (
            <p role="status" className="text-xs text-red-500/80">{submitError}</p>
          )}

          {saveError && (
            <p role="status" className="text-xs text-red-500/80">{saveError}</p>
          )}

          {/* Three labels do not fit one row on a phone, and a squeezed `sm` button has a fixed
              height, so wrapped text spills out of its own box. Below `sm` the pin action takes the
              full row and the two remaining buttons share the next one, at the 44pt touch height
              the mobile layer asks for; labels never wrap. */}
          <div className="flex flex-wrap items-center justify-end gap-2 pt-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="h-11 w-full shrink-0 whitespace-nowrap sm:mr-auto sm:h-9 sm:w-auto"
              onClick={save}
              // A reused plan is still pin-able: the archive row it came from may be AUTO and can be
              // overwritten or expire, so "keep this" must remain available.
              disabled={saving || savedId !== null}
              title={i18n('Keep this script so it is not overwritten by the next plan')}
            >
              <Bookmark className="mr-1 h-3 w-3 shrink-0" />
              {saving ? i18n('Saving…') : savedId ? i18n('Saved') : i18n('Keep this script')}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="h-11 shrink-0 whitespace-nowrap sm:h-9"
              onClick={onClose}
            >
              {i18n('Cancel')}
            </Button>
            <Button
              type="button"
              size="sm"
              className="h-11 min-w-0 flex-1 whitespace-nowrap sm:h-9 sm:flex-none"
              onClick={read}
              disabled={submitting || drafts.every((s) => !s.spokenText.trim())}
            >
              <Play className="mr-1 h-3 w-3 shrink-0" />
              <span className="truncate">
                {submitting ? i18n('Starting the read-aloud…') : i18n('Read this plan')}
              </span>
            </Button>
          </div>
        </div>
      )}
    </Dialog>
  );
};

/** A plan failure is a configuration or limit problem, not something the panel can repair. */
export function planErrorText(error: unknown): string {
  if (error instanceof TtsError) {
    switch (error.status) {
      case 401:
        return i18n('Sign in again to use read-aloud');
      // 409 covers both halves of the configuration: the TTS row and the Speech Direction Model.
      case 409:
        return i18n('Read-aloud needs a TTS model and a Speech Direction Model (Settings → Task Models)');
      case 413:
        return i18n('This reply is too long to read aloud (max 20,000 characters)');
      // A plan that could not be produced: the director is unset, timed out, or returned an
      // unusable script. All three are fixed in Settings → Task Models or by trying again.
      case 422:
        return i18n('The read-aloud script could not be built; check the Speech Direction Model or try again');
      case 429:
        return i18n('The previous read-aloud is still running');
      default:
        break;
    }
  }
  return i18n('Could not plan this reply');
}
