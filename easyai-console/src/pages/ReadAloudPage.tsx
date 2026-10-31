import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AudioLines, Pause, Play, RefreshCw, Star } from 'lucide-react';
import { ScriptDetailDrawer, describeTarget } from '@/components/speech/ScriptDetailDrawer';
import { Input } from '@/components/ui/Input';
import { useTtsPlayback } from '@/hooks/useTtsPlayback';
import { speechScriptService, listErrorText } from '@/services/speech-script-service';
import { switchToSession } from '@/services/session-switch';
import type { ScriptListQuery, ScriptSummary } from '@/types/speech-script';
import { i18n } from '@/utils/i18n';

const PAGE_SIZE = 50;

const TYPE_FILTERS: Array<{ id: string | undefined; label: string }> = [
  { id: undefined, label: 'All' },
  { id: 'session_message', label: 'Chat replies' },
  { id: 'manual_text', label: 'Typed text' },
];

const MODE_FILTERS: Array<{ id: string | undefined; label: string }> = [
  { id: undefined, label: 'Any mode' },
  { id: 'refine', label: 'Refined' },
  { id: 'director', label: 'Directed' },
];

const SORTS: Array<{ id: NonNullable<ScriptListQuery['sort']>; label: string }> = [
  { id: 'UPDATED', label: 'Recently updated' },
  { id: 'LAST_PLAYED', label: 'Recently played' },
  { id: 'PLAY_COUNT', label: 'Most played' },
];

/** Status is a lifecycle, so it is a chip rather than a filter tab: "auto vs kept" is the question. */
const StatusChip: React.FC<{ status: string }> = ({ status }) => {
  const style =
    status === 'PINNED'
      ? 'bg-amber-500/15 text-amber-600 dark:text-amber-400'
      : status === 'ARCHIVED'
        ? 'bg-muted text-muted-foreground'
        : 'bg-primary/10 text-primary';
  const label =
    status === 'PINNED' ? 'Kept' : status === 'ARCHIVED' ? 'Deleted' : 'Auto-saved';
  return (
    <span data-script-status={status} className={`rounded-full px-2 py-0.5 text-[11px] ${style}`}>
      {i18n(label)}
    </span>
  );
};

interface RowProps {
  script: ScriptSummary;
  onOpen: () => void;
  onToggleFavorite: () => void;
  playing: boolean;
  loadingPlay: boolean;
  /** Playback failure for this row, so a failed click does not look like a no-op. */
  playError?: string | null;
  onPlay: () => void;
  onOpenSession: () => void;
}

const ScriptRow: React.FC<RowProps> = ({
  script,
  onOpen,
  onToggleFavorite,
  playing,
  loadingPlay,
  playError,
  onPlay,
  onOpenSession,
}) => (
  <div
    data-script-row={script.id}
    className="flex items-start gap-3 rounded-lg border border-border bg-card p-3 hover:bg-muted/30"
  >
    <button
      type="button"
      onClick={onPlay}
      disabled={script.status === 'ARCHIVED'}
      className="mt-0.5 shrink-0 rounded-md border border-border p-1.5 hover:bg-muted disabled:opacity-40"
      title={playing ? i18n('Stop reading') : i18n('Read this script')}
      aria-pressed={playing}
    >
      {playing && !loadingPlay ? (
        <Pause className="size-3.5" />
      ) : (
        <Play className={`size-3.5 ${loadingPlay ? 'animate-pulse' : ''}`} />
      )}
    </button>

    <div className="min-w-0 flex-1">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={onOpen}
          className="min-w-0 truncate text-left font-medium hover:underline"
          title={script.title}
        >
          {script.title || i18n('Untitled script')}
        </button>
        <StatusChip status={script.status} />
        <span className="rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground">
          {script.mode === 'refine' ? i18n('Refined') : i18n('Directed')}
        </span>
        <span className="rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground">
          {describeTarget(script.targetType)}
        </span>
      </div>

      <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
        <span>
          {script.sourceChars} → {script.spokenChars} {i18n('chars')}
        </span>
        <span>
          {script.segmentCount} {i18n('segments')}
        </span>
        <span>
          {i18n('Played {count} times').replace('{count}', String(script.playCount))}
        </span>
        <span>{new Date(script.updatedAt).toLocaleString()}</span>
        {script.sessionId && (
          <button
            type="button"
            onClick={onOpenSession}
            className="hover:text-foreground hover:underline"
            title={i18n('Open the session this came from')}
          >
            {i18n('Open session')}
          </button>
        )}
      </div>
      {playError && (
        <p role="status" className="mt-1 text-[11px] text-red-500/80">
          {playError}
        </p>
      )}
    </div>

    <button
      type="button"
      onClick={onToggleFavorite}
      className={`shrink-0 rounded-md p-1.5 hover:bg-muted ${script.favorite ? 'text-amber-500' : 'text-muted-foreground'}`}
      title={script.favorite ? i18n('Remove from favourites') : i18n('Add to favourites')}
      aria-pressed={script.favorite}
    >
      <Star className={`size-4 ${script.favorite ? 'fill-current' : ''}`} />
    </button>
  </div>
);

/**
 * The read-aloud script library.
 *
 * Every directed plan and successful quick refinement is filed here automatically, so this page is
 * a history first and a collection second: the interesting action is re-reading something you already paid for, not
 * curating a list. That is why "play" is a row-level button and "kept" is a chip rather than a
 * separate tab — a user should not have to understand the lifecycle to use the page.
 */
export const ReadAloudPage: React.FC = () => {
  const navigate = useNavigate();
  const tts = useTtsPlayback();
  const [rows, setRows] = useState<ScriptSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [type, setType] = useState<string | undefined>(undefined);
  const [mode, setMode] = useState<string | undefined>(undefined);
  const [favoriteOnly, setFavoriteOnly] = useState(false);
  const [sort, setSort] = useState<NonNullable<ScriptListQuery['sort']>>('UPDATED');
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [openId, setOpenId] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  // A filter change must invalidate an in-flight "load more": its page belongs to the old query and
  // appending it would mix two result sets. The count is kept separately so a locally deleted row
  // does not shift the offset.
  const requestEpoch = useRef(0);
  const loadedCount = useRef(0);

  // Typing must not fire a request per keystroke; 300ms is below the point where the list feels laggy.
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search.trim()), 300);
    return () => clearTimeout(timer);
  }, [search]);

  const query = useMemo<ScriptListQuery>(
    () => ({
      type,
      mode,
      favorite: favoriteOnly || undefined,
      q: debouncedSearch || undefined,
      sort,
      limit: PAGE_SIZE,
      offset: 0,
    }),
    [type, mode, favoriteOnly, debouncedSearch, sort]
  );

  const load = useCallback(
    async (options: { silent?: boolean } = {}) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      const epoch = ++requestEpoch.current;
      if (!options.silent) setLoading(true);
      setError(null);
      try {
        const page = await speechScriptService.list(query, controller.signal);
        if (controller.signal.aborted || epoch !== requestEpoch.current) return;
        setRows(page);
        loadedCount.current = page.length;
        setHasMore(page.length >= PAGE_SIZE);
      } catch (e) {
        if (controller.signal.aborted || epoch !== requestEpoch.current) return;
        setError(listErrorText(e));
        setRows([]);
        loadedCount.current = 0;
        setHasMore(false);
      } finally {
        if (!controller.signal.aborted && epoch === requestEpoch.current) setLoading(false);
      }
    },
    [query]
  );

  useEffect(() => {
    void load();
    return () => abortRef.current?.abort();
  }, [load]);

  // Leaving the page must end the read: this page owns the only controls for a `library:` clip, and
  // a shared player with no visible stop button would keep talking over every other route.
  useEffect(() => () => tts.stop(), [tts.stop]);

  const loadMore = useCallback(() => {
    const epoch = requestEpoch.current;
    const offset = loadedCount.current;
    void (async () => {
      setLoading(true);
      // A retry after a failed page must clear the banner once it succeeds.
      setError(null);
      try {
        const page = await speechScriptService.list({ ...query, offset });
        if (epoch !== requestEpoch.current) return;
        setRows((current) => {
          const seen = new Set(current.map((row) => row.id));
          return [...current, ...page.filter((row) => !seen.has(row.id))];
        });
        loadedCount.current = offset + page.length;
        setHasMore(page.length >= PAGE_SIZE);
      } catch (e) {
        if (epoch !== requestEpoch.current) return;
        setError(listErrorText(e));
      } finally {
        if (epoch === requestEpoch.current) setLoading(false);
      }
    })();
  }, [query]);

  const toggleFavorite = useCallback((script: ScriptSummary) => {
    const next = !script.favorite;
    setRows((current) =>
      current.map((row) => (row.id === script.id ? { ...row, favorite: next } : row))
    );
    void (async () => {
      try {
        await speechScriptService.patch(script.id, { favorite: next });
        // Under the favourites filter, an unstarred row no longer belongs to the result set.
        if (favoriteOnly && !next) {
          setRows((current) => current.filter((row) => row.id !== script.id));
        }
      } catch {
        setRows((current) =>
          current.map((row) => (row.id === script.id ? { ...row, favorite: !next } : row))
        );
      }
    })();
  }, [favoriteOnly]);

  const play = useCallback(
    (script: ScriptSummary) => {
      const key = `library:${script.id}`;
      if (tts.activeKey === key) {
        // `ready` is the parked-clip state: the audio is loaded and only the autoplay policy blocked
        // it, so resume rather than stop and pay for the same synthesis again.
        if (tts.state === 'ready') {
          tts.resume();
          return;
        }
        if (tts.state === 'loading' || tts.state === 'playing') {
          tts.stop();
          return;
        }
      }
      // No segments are sent: the server validates its own stored copy, which is the only version
      // that cannot have drifted from what was planned.
      tts.speakScript(key, { scriptId: script.id, incremental: false });
    },
    [tts]
  );

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-4xl p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="flex items-center gap-2 text-2xl font-semibold">
              <AudioLines className="size-6" />
              {i18n('Read-aloud library')}
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              {i18n('Every directed read-aloud is saved here, so it can be played again without planning it twice.')}
            </p>
          </div>
          <button
            type="button"
            onClick={() => void load()}
            className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
          >
            <RefreshCw className={`size-4 ${loading ? 'animate-spin' : ''}`} />
            {i18n('Refresh')}
          </button>
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-2">
          <div className="min-w-48 flex-1">
            <Input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={i18n('Search titles and text…')}
              aria-label={i18n('Search titles and text…')}
            />
          </div>
          <select
            value={type ?? ''}
            onChange={(event) => setType(event.target.value || undefined)}
            className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
            aria-label={i18n('Source')}
          >
            {TYPE_FILTERS.map((candidate) => (
              <option key={candidate.id ?? 'all'} value={candidate.id ?? ''}>
                {i18n(candidate.label)}
              </option>
            ))}
          </select>
          <select
            value={mode ?? ''}
            onChange={(event) => setMode(event.target.value || undefined)}
            className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
            aria-label={i18n('Mode')}
          >
            {MODE_FILTERS.map((candidate) => (
              <option key={candidate.id ?? 'any'} value={candidate.id ?? ''}>
                {i18n(candidate.label)}
              </option>
            ))}
          </select>
          <select
            value={sort}
            onChange={(event) => setSort(event.target.value as NonNullable<ScriptListQuery['sort']>)}
            className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
            aria-label={i18n('Sort')}
          >
            {SORTS.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {i18n(candidate.label)}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => setFavoriteOnly((current) => !current)}
            aria-pressed={favoriteOnly}
            className={`inline-flex h-9 items-center gap-1.5 rounded-md border px-3 text-sm ${
              favoriteOnly
                ? 'border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400'
                : 'border-border text-muted-foreground hover:bg-muted'
            }`}
          >
            <Star className={`size-3.5 ${favoriteOnly ? 'fill-current' : ''}`} />
            {i18n('Favourites')}
          </button>
        </div>

        {error && (
          <div className="mt-4 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
            {error}
          </div>
        )}

        <div className="mt-4 space-y-2">
          {rows.map((script) => {
            const key = `library:${script.id}`;
            const active = tts.activeKey === key;
            return (
              <ScriptRow
                key={script.id}
                script={script}
                onOpen={() => setOpenId(script.id)}
                onToggleFavorite={() => toggleFavorite(script)}
                playing={active && tts.state === 'playing'}
                loadingPlay={active && tts.state === 'loading'}
                playError={active ? tts.error : null}
                onPlay={() => play(script)}
                onOpenSession={() => {
                  const sessionId = script.sessionId;
                  if (!sessionId) return;
                  // Switching sessions is a store operation, not a route: the chat page reads the
                  // active session from the store, so navigating first would paint the old one.
                  void (async () => {
                    try {
                      await switchToSession(sessionId);
                      navigate('/');
                    } catch (e) {
                      setError(e instanceof Error ? e.message : String(e));
                    }
                  })();
                }}
              />
            );
          })}

          {!loading && rows.length === 0 && !error && (
            <div className="rounded-lg border border-dashed border-border p-10 text-center">
              <AudioLines className="mx-auto size-8 text-muted-foreground" />
              <div className="mt-3 font-medium">{i18n('No read-aloud scripts yet')}</div>
              <p className="mt-1 text-sm text-muted-foreground">
                {i18n('Read a reply aloud in directed or refined mode and it will show up here.')}
              </p>
            </div>
          )}

          {hasMore && (
            <div className="pt-2 text-center">
              <button
                type="button"
                onClick={loadMore}
                disabled={loading}
                className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted disabled:opacity-50"
              >
                {i18n('Load more')}
              </button>
            </div>
          )}
        </div>
      </div>

      <ScriptDetailDrawer
        scriptId={openId}
        onClose={() => setOpenId(null)}
        onPatched={(detail) =>
          setRows((current) => {
            if (favoriteOnly && !detail.favorite) {
              return current.filter((row) => row.id !== detail.id);
            }
            return current.map((row) =>
              row.id === detail.id
                ? { ...row, title: detail.title, favorite: detail.favorite, updatedAt: detail.updatedAt }
                : row
            );
          })
        }
        onDeleted={(id) => {
          setRows((current) => current.filter((row) => row.id !== id));
          // The row is gone server-side too, so the next page starts one position earlier.
          loadedCount.current = Math.max(0, loadedCount.current - 1);
        }}
      />
    </div>
  );
};
