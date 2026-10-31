import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Pause, Play, Star, Trash2, X } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { ScriptParamsPanel } from '@/components/speech/ScriptParamsPanel';
import { useTtsPlayback } from '@/hooks/useTtsPlayback';
import { speechScriptService, detailErrorText } from '@/services/speech-script-service';
import type { ScriptDetail } from '@/types/speech-script';
import { i18n } from '@/utils/i18n';

type Tab = 'source' | 'spoken' | 'params';

const TABS: Array<{ id: Tab; label: string }> = [
  { id: 'source', label: 'Original text' },
  { id: 'spoken', label: 'What the voice says' },
  { id: 'params', label: 'Call parameters' },
];

interface ScriptDetailDrawerProps {
  /** The script to show; `null` closes the drawer without unmounting it. */
  scriptId: string | null;
  onClose: () => void;
  /** Rename / favourite landed, so the list row can update without a refetch. */
  onPatched?: (detail: ScriptDetail) => void;
  onDeleted?: (id: string) => void;
}

/**
 * One stored script, in a right-hand drawer.
 *
 * Three tabs, in the order the questions get asked: what was said (`source`), what the voice will
 * actually say (`spoken`), and what the model was asked for (`params`). The second tab is the whole
 * reason a script is worth storing — `sourceText` above a struck-through original makes a rewrite
 * visible at a glance instead of something the listener has to notice by ear.
 *
 * Everything here is read-only except rename, favourite and delete. Editing segments belongs to the
 * read-aloud panel, where the edit is re-validated against the plan the server is holding; doing it
 * here would need a second validation path (and a second way to get it wrong).
 */
export const ScriptDetailDrawer: React.FC<ScriptDetailDrawerProps> = ({
  scriptId,
  onClose,
  onPatched,
  onDeleted,
}) => {
  const tts = useTtsPlayback();
  const [detail, setDetail] = useState<ScriptDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('source');
  const [titleDraft, setTitleDraft] = useState('');
  const [savingTitle, setSavingTitle] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  /** The script the drawer is showing *now*; a late response must not overwrite the next one. */
  const openIdRef = useRef(scriptId);
  openIdRef.current = scriptId;

  useEffect(() => {
    if (!scriptId) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    setError(null);
    setDetail(null);
    setTab('source');
    void (async () => {
      try {
        const loaded = await speechScriptService.detail(scriptId, controller.signal);
        if (controller.signal.aborted) return;
        setDetail(loaded);
        setTitleDraft(loaded.title);
      } catch (e) {
        if (controller.signal.aborted) return;
        setError(detailErrorText(e));
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [scriptId]);

  // Escape closes the drawer, but only when no dialog is stacked on top of it.
  useEffect(() => {
    if (!scriptId || confirmDelete) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [scriptId, confirmDelete, onClose]);

  const playKey = scriptId ? `library:${scriptId}` : '';
  const playing = !!scriptId && tts.activeKey === playKey;
  const playingNow = playing && tts.state === 'playing';

  const play = useCallback(() => {
    if (!detail || !scriptId) return;
    if (playing) {
      // `ready` means the clip is already loaded and autoplay was blocked: resume it instead of
      // stopping and paying for the same synthesis twice.
      if (tts.state === 'ready') {
        tts.resume();
        return;
      }
      if (tts.state === 'loading' || tts.state === 'playing') {
        tts.stop();
        return;
      }
    }
    // A replay sends only the id: this drawer is read-only, and sending the segments would make the
    // server treat the replay as an edit (revision bump, CAS against another tab's save).
    tts.speakScript(playKey, { scriptId, incremental: false });
  }, [detail, scriptId, playing, playKey, tts]);

  const commitTitle = useCallback(() => {
    if (!detail || savingTitle) return;
    const next = titleDraft.trim();
    if (!next || next === detail.title) {
      setTitleDraft(detail.title);
      return;
    }
    setSavingTitle(true);
    void (async () => {
      try {
        const updated = await speechScriptService.patch(detail.id, { title: next });
        // The drawer may have moved to another script while the rename was in flight.
        if (openIdRef.current === updated.id) {
          setDetail(updated);
          setTitleDraft(updated.title);
          setError(null);
        }
        onPatched?.(updated);
      } catch {
        // A failed rename is visible on the next read; keeping the draft would pretend it saved.
        if (openIdRef.current === detail.id) setTitleDraft(detail.title);
      } finally {
        setSavingTitle(false);
      }
    })();
  }, [detail, titleDraft, savingTitle, onPatched]);

  const toggleFavorite = useCallback(() => {
    if (!detail) return;
    const id = detail.id;
    const next = !detail.favorite;
    // Optimistic: a star that waits for a round trip feels broken, and a failure just flips it back.
    setDetail((current) => (current?.id === id ? { ...current, favorite: next } : current));
    void (async () => {
      try {
        const updated = await speechScriptService.patch(id, { favorite: next });
        if (openIdRef.current === updated.id) {
          setDetail(updated);
          setError(null);
        }
        onPatched?.(updated);
      } catch {
        setDetail((current) =>
          current?.id === id ? { ...current, favorite: !next } : current
        );
      }
    })();
  }, [detail, onPatched]);

  const remove = useCallback(() => {
    if (!detail) return;
    const id = detail.id;
    setConfirmDelete(false);
    void (async () => {
      try {
        await speechScriptService.remove(id);
        onDeleted?.(id);
        onClose();
      } catch (e) {
        setError(detailErrorText(e));
      }
    })();
  }, [detail, onDeleted, onClose]);

  if (!scriptId) return null;

  return (
    <>
      <div className="fixed inset-0 z-40 flex justify-end bg-black/40" onClick={onClose}>
        <aside
          data-script-drawer
          className="flex h-full w-full max-w-2xl flex-col border-l border-border bg-background shadow-lg"
          onClick={(event) => event.stopPropagation()}
          role="dialog"
          aria-label={i18n('Read-aloud script')}
        >
          <header className="flex items-start gap-2 border-b border-border p-4">
            <div className="min-w-0 flex-1">
              {detail ? (
                <input
                  value={titleDraft}
                  onChange={(event) => setTitleDraft(event.target.value)}
                  onBlur={commitTitle}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') event.currentTarget.blur();
                    if (event.key === 'Escape') {
                      // The window listener below would otherwise close the whole drawer.
                      event.stopPropagation();
                      setTitleDraft(detail.title);
                    }
                  }}
                  disabled={savingTitle}
                  className="w-full rounded border border-transparent bg-transparent px-1 py-0.5 text-lg font-semibold outline-none hover:border-border focus:border-primary"
                  aria-label={i18n('Title')}
                />
              ) : (
                <div className="h-6 w-48 animate-pulse rounded bg-muted" />
              )}
              {detail && (
                <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                  <span>{describeTarget(detail.targetType)}</span>
                  <span>·</span>
                  <span>{detail.mode === 'refine' ? i18n('Refine read-aloud') : i18n('Directed read-aloud')}</span>
                  <span>·</span>
                  <span>
                    {detail.sourceChars} → {detail.spokenChars} {i18n('chars')}
                  </span>
                  <span>·</span>
                  <span>{i18n('Played {count} times').replace('{count}', String(detail.playCount))}</span>
                  <span>·</span>
                  <span>{new Date(detail.updatedAt).toLocaleString()}</span>
                </div>
              )}
            </div>
            <div className="flex shrink-0 items-center gap-1">
              <button
                type="button"
                onClick={toggleFavorite}
                disabled={!detail}
                className={`rounded-md p-1.5 hover:bg-muted disabled:opacity-40 ${detail?.favorite ? 'text-amber-500' : 'text-muted-foreground'}`}
                title={detail?.favorite ? i18n('Remove from favourites') : i18n('Add to favourites')}
                aria-pressed={detail?.favorite ?? false}
              >
                <Star className={`size-4 ${detail?.favorite ? 'fill-current' : ''}`} />
              </button>
              <button
                type="button"
                onClick={() => setConfirmDelete(true)}
                disabled={!detail}
                className="rounded-md p-1.5 text-muted-foreground hover:bg-muted hover:text-destructive disabled:opacity-40"
                title={i18n('Delete this script')}
              >
                <Trash2 className="size-4" />
              </button>
              <button
                type="button"
                onClick={onClose}
                className="rounded-md p-1.5 text-muted-foreground hover:bg-muted"
                title={i18n('Close')}
              >
                <X className="size-4" />
              </button>
            </div>
          </header>

          <div className="flex items-center gap-1 border-b border-border px-3">
            {TABS.map((candidate) => (
              <button
                key={candidate.id}
                type="button"
                data-script-tab={candidate.id}
                onClick={() => setTab(candidate.id)}
                className={`-mb-px border-b-2 px-3 py-2 text-sm ${
                  tab === candidate.id
                    ? 'border-primary text-foreground'
                    : 'border-transparent text-muted-foreground hover:text-foreground'
                }`}
              >
                {i18n(candidate.label)}
              </button>
            ))}
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto p-4">
            {loading && (
              <p className="text-sm text-muted-foreground">{i18n('Loading…')}</p>
            )}

            {error && (
              <p role="status" className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
                {error}
              </p>
            )}

            {detail && !loading && tab === 'source' && (
              <div className="space-y-2">
                <p className="text-xs text-muted-foreground">
                  {i18n('The reply as it was written. Every edit is checked against this text.')}
                </p>
                <pre className="whitespace-pre-wrap break-words rounded-md border border-border bg-muted/30 p-3 text-sm">
                  {detail.sourceText}
                </pre>
              </div>
            )}

            {detail && !loading && tab === 'spoken' && (
              <ol className="space-y-3">
                {detail.warnings.length > 0 && (
                  <ul className="space-y-1 rounded-md bg-muted/50 p-2 text-xs text-muted-foreground">
                    {detail.warnings.map((warning, index) => (
                      <li key={`${warning.code}-${warning.segmentId ?? index}`}>
                        <span className="font-mono">{warning.code}</span> {warning.message}
                      </li>
                    ))}
                  </ul>
                )}
                {detail.segments.map((segment) => (
                  <li key={segment.id} data-script-segment={segment.id} className="rounded-md border border-border p-3">
                    <div className="mb-1 flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
                      <span className="font-mono">{segment.index + 1}</span>
                      <span>{segment.voice}</span>
                      <span>·</span>
                      <span>{segment.role}</span>
                      {segment.emotion && <span>· {segment.emotion}</span>}
                      {segment.rate !== null && segment.rate !== undefined && <span>· rate {segment.rate}</span>}
                      <span>· {segment.pauseAfterMs}ms</span>
                    </div>
                    <p className="text-xs text-muted-foreground line-through decoration-muted-foreground/40">
                      {segment.sourceText}
                    </p>
                    <p className="mt-1 whitespace-pre-wrap text-sm">{segment.spokenText}</p>
                    {segment.styleNote && (
                      <p className="mt-1 text-[11px] italic text-muted-foreground">{segment.styleNote}</p>
                    )}
                  </li>
                ))}
              </ol>
            )}

            {detail && !loading && tab === 'params' && (
              <ScriptParamsPanel
                ttsParams={detail.ttsParams}
                directionParams={detail.directionParams}
              />
            )}
          </div>

          <footer className="flex items-center gap-2 border-t border-border p-3">
            {playing && tts.error && (
              <span role="status" className="mr-auto text-xs text-red-500/80">{tts.error}</span>
            )}
            {playing && !tts.error && tts.notice && (
              <span role="status" className="mr-auto text-xs text-muted-foreground">{tts.notice}</span>
            )}
            {!playing && <span className="mr-auto" />}
            <Button type="button" variant="outline" size="sm" onClick={onClose}>
              {i18n('Close')}
            </Button>
            <Button
              type="button"
              size="sm"
              onClick={play}
              disabled={!detail || loading || (playing && tts.state === 'loading')}
            >
              {playingNow ? (
                <>
                  <Pause className="mr-1 size-3" />
                  {i18n('Stop reading')}
                </>
              ) : (
                <>
                  <Play className="mr-1 size-3" />
                  {i18n('Read this script')}
                </>
              )}
            </Button>
          </footer>
        </aside>
      </div>

      <ConfirmDialog
        open={confirmDelete}
        message={i18n('Delete this read-aloud script? The reply itself is not affected.')}
        confirmLabel={i18n('Delete')}
        danger
        onConfirm={remove}
        onCancel={() => setConfirmDelete(false)}
      />
    </>
  );
};

/** The wire keeps the key; the drawer shows what it means. */
export function describeTarget(targetType: string): string {
  switch (targetType) {
    case 'session_message':
      return i18n('From a chat reply');
    case 'knowledge_article':
      return i18n('From a knowledge article');
    case 'tool_call':
      return i18n('From a tool result');
    case 'manual_text':
      return i18n('Typed text');
    default:
      // An unknown key is data, not a failure: a newer server may store types this build predates.
      return targetType;
  }
}
