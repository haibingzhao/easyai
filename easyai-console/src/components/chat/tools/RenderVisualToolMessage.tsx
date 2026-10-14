/**
 * render_visual card renderer.
 *
 * The fragment is the deliverable, so a card with one never collapses: the `compact`
 * prop is deliberately ignored and no collapsible wrapper is used — a folded
 * visual would hide exactly what the model asked the user to look at.
 *
 * A call that carried no fragment at all is a different story: it has nothing to
 * show, so it degrades to a plain tool row whose body is the reason it failed.
 *
 * The sandbox iframe stays mounted in code view (hidden, zero height) so the
 * live DOM survives a view toggle and "save as image" can rasterize what the
 * user actually saw, including anything scripts produced.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Code2, Copy, Download, Eye, ImageDown, Maximize2, MoreHorizontal, Scan, Shapes, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { CodeBlock } from '../CodeBlock';
import { useCopyToast } from './useCopyToast';
import { extractOutput } from './parsers';
import { ToolRowHeader } from './ToolRowHeader';
import { ToolSection } from './ToolSection';
import { useStreamingRowExpand } from './useStreamingRowExpand';
import { i18n } from '@/utils/i18n';
import { MAX_FRAGMENT_BYTES, byteLength, extractPartialArgs, isSvgFragment, parseArgs } from './render-visual/params';
import { sanitizeFragment } from './render-visual/sanitize';
import { RV_MSG, buildShellSrcdoc, isRenderVisualSandboxMessage } from './render-visual/shell';
import { currentMode, subscribeTheme, tokensCssForMode } from './render-visual/theme';
import { clampHeight } from './render-visual/height';
import { downloadBlob, exportSvgFragmentAsImage, requestSandboxExport, safeFileName } from './render-visual/exportImage';

const ROTATE_INTERVAL_MS = 1400;
const ACTION_ERROR_TIMEOUT_MS = 4000;
const MIN_SCALE = 0.2;
const MAX_SCALE = 4;

function clampScale(value: number): number {
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, value));
}

/** Prefix of the prose placeholder an older backend persisted in place of an elided fragment. */
const ELIDED_PREFIX = '[fragment elided from context:';

type ViewMode = 'ui' | 'code';

export function RenderVisualToolMessage({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const args = useMemo(() => parseArgs(toolCall.args), [toolCall.args]);
  const isStreaming = args === null && (status === 'PENDING' || status === 'RUNNING');
  const partial = useMemo(
    () => (isStreaming ? extractPartialArgs(toolCall.args) : null),
    [isStreaming, toolCall.args]
  );

  const code = args?.code ?? '';
  const tooLarge = args !== null && byteLength(code) > MAX_FRAGMENT_BYTES;
  const emptyFragment = args !== null && code.trim() === '';
  /** Calls where the model wrote the elision placeholder instead of a fragment. */
  const elidedPlaceholder = args !== null && code.trimStart().startsWith(ELIDED_PREFIX);
  const toolFailed = result?.isError === true || status === 'FAILED';
  /** No markup was ever delivered, so there is nothing a card could show. */
  const noFragment = args !== null && (emptyFragment || elidedPlaceholder);

  const [viewMode, setViewMode] = useState<ViewMode>('ui');
  const [menuOpen, setMenuOpen] = useState(false);
  const [frameHeight, setFrameHeight] = useState(0);
  const [frameError, setFrameError] = useState<string | null>(null);
  const [loadingIndex, setLoadingIndex] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);
  const [rowExpanded, setRowExpanded] = useState(false);
  const [fullscreenOpen, setFullscreenOpen] = useState(false);
  const [fullscreenSize, setFullscreenSize] = useState<{ width: number; height: number } | null>(null);
  const [view, setView] = useState({ scale: 1, tx: 0, ty: 0 });
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  const fullscreenFrameRef = useRef<HTMLIFrameElement | null>(null);
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const pointersRef = useRef<Map<number, { x: number; y: number }>>(new Map());
  const pinchRef = useRef<{ distance: number; scale: number } | null>(null);
  const toolbarRef = useRef<HTMLDivElement | null>(null);
  const { copyToClipboard, toast } = useCopyToast();
  const markRowTouched = useStreamingRowExpand(status, setRowExpanded, result !== undefined);

  const sandboxId = toolCall.id;
  /** Without a renderable fragment a sandbox is pointless — those cases stay in source view. */
  const sandboxUsable = args !== null && !tooLarge && !toolFailed && !noFragment;
  const srcdoc = useMemo(() => buildShellSrcdoc(sandboxId, tokensCssForMode(currentMode())), [sandboxId]);
  // The fullscreen view is a second, independent sandbox: a distinct id keeps its height/error
  // messages from colliding with the inline card's listener (it fills the viewport, so it reports
  // no height we care about).
  const fullscreenId = `${sandboxId}:fullscreen`;
  const fullscreenSrcdoc = useMemo(() => buildShellSrcdoc(fullscreenId, tokensCssForMode(currentMode())), [fullscreenId]);
  const sanitized = useMemo(
    () => (sandboxUsable ? sanitizeFragment(code) : ''),
    [sandboxUsable, code]
  );

  const showCode = viewMode === 'code' || !sandboxUsable;
  const language = isSvgFragment(code) ? 'svg' : 'html';

  const loadingMessages = partial?.loadingMessages ?? [];
  useEffect(() => {
    if (loadingMessages.length < 2) return;
    const timer = setInterval(() => setLoadingIndex((i) => (i + 1) % loadingMessages.length), ROTATE_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [loadingMessages.length]);

  useEffect(() => {
    setFrameError(null);
    setFrameHeight(0);
  }, [sanitized]);

  useEffect(() => {
    if (!menuOpen) return;
    const onMouseDown = (event: MouseEvent) => {
      if (toolbarRef.current && !toolbarRef.current.contains(event.target as Node)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, [menuOpen]);

  useEffect(() => {
    if (actionError === null) return;
    const timer = setTimeout(() => setActionError(null), ACTION_ERROR_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [actionError]);

  useEffect(() => subscribeTheme((mode) => {
    const css = tokensCssForMode(mode);
    iframeRef.current?.contentWindow?.postMessage({ type: RV_MSG.THEME, id: sandboxId, css }, '*');
    fullscreenFrameRef.current?.contentWindow?.postMessage({ type: RV_MSG.THEME, id: fullscreenId, css }, '*');
  }), [sandboxId, fullscreenId]);

  useEffect(() => {
    if (!fullscreenOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        setFullscreenOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [fullscreenOpen]);

  useEffect(() => {
    const listener = (event: MessageEvent) => {
      const data: unknown = event.data;
      if (isRenderVisualSandboxMessage(data, sandboxId)) {
        if (data.type === RV_MSG.HEIGHT) setFrameHeight(clampHeight(data.height));
        else if (data.type === RV_MSG.ERROR) {
          setFrameError(data.message);
          // Degrade to source view, but keep the sandbox mounted so the user can
          // toggle back and still export the partially rendered fragment.
          setViewMode('code');
        }
        return;
      }
      // The fullscreen sandbox reports its true content box so the viewer can scale-to-fit.
      if (isRenderVisualSandboxMessage(data, fullscreenId) && data.type === RV_MSG.HEIGHT) {
        setFullscreenSize({ width: data.width, height: data.height });
      }
    };
    window.addEventListener('message', listener);
    return () => window.removeEventListener('message', listener);
  }, [sandboxId, fullscreenId]);

  const handleFrameLoad = useCallback(() => {
    const frame = iframeRef.current?.contentWindow;
    if (!frame) return;
    frame.postMessage({ type: RV_MSG.MOUNT, id: sandboxId, html: sanitized }, '*');
    frame.postMessage({ type: RV_MSG.THEME, id: sandboxId, css: tokensCssForMode(currentMode()) }, '*');
  }, [sandboxId, sanitized]);

  const handleFullscreenLoad = useCallback(() => {
    const frame = fullscreenFrameRef.current?.contentWindow;
    if (!frame) return;
    frame.postMessage({ type: RV_MSG.MOUNT, id: fullscreenId, html: sanitized }, '*');
    frame.postMessage({ type: RV_MSG.THEME, id: fullscreenId, css: tokensCssForMode(currentMode()) }, '*');
  }, [fullscreenId, sanitized]);

  // Scale the fragment to the viewport width on open so wide desktop-sized visuals are never
  // clipped; pinch then zooms around the gesture midpoint and a single finger pans.
  const fittedRef = useRef(false);

  const resetView = useCallback(() => {
    const viewport = viewportRef.current;
    if (!viewport || !fullscreenSize || fullscreenSize.width <= 0) return;
    const scale = Math.min(1, viewport.clientWidth / fullscreenSize.width);
    const scaledHeight = fullscreenSize.height * scale;
    setView({
      scale,
      tx: (viewport.clientWidth - fullscreenSize.width * scale) / 2,
      // Center when it fits; top-align when taller than the viewport so nothing above is cut off.
      ty: scaledHeight <= viewport.clientHeight ? (viewport.clientHeight - scaledHeight) / 2 : 0,
    });
  }, [fullscreenSize]);

  useEffect(() => {
    if (!fullscreenOpen) return;
    fittedRef.current = false;
    setFullscreenSize(null);
  }, [fullscreenOpen]);

  useEffect(() => {
    if (!fullscreenOpen || !fullscreenSize || fittedRef.current) return;
    fittedRef.current = true;
    resetView();
  }, [fullscreenOpen, fullscreenSize, resetView]);

  const onPointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    pointersRef.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pointersRef.current.size === 2) {
      const [a, b] = [...pointersRef.current.values()];
      pinchRef.current = { distance: Math.hypot(a.x - b.x, a.y - b.y), scale: view.scale };
    }
  }, [view.scale]);

  const onPointerMove = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const prev = pointersRef.current.get(event.pointerId);
    if (!prev) return;
    pointersRef.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pointersRef.current.size >= 2 && pinchRef.current && pinchRef.current.distance > 0) {
      const [a, b] = [...pointersRef.current.values()];
      const viewport = viewportRef.current;
      if (!viewport) return;
      const rect = viewport.getBoundingClientRect();
      const cx = (a.x + b.x) / 2 - rect.left;
      const cy = (a.y + b.y) / 2 - rect.top;
      const next = clampScale(pinchRef.current.scale * (Math.hypot(a.x - b.x, a.y - b.y) / pinchRef.current.distance));
      setView((current) => {
        const ratio = next / current.scale;
        return { scale: next, tx: cx - (cx - current.tx) * ratio, ty: cy - (cy - current.ty) * ratio };
      });
    } else if (pointersRef.current.size === 1) {
      const dx = event.clientX - prev.x;
      const dy = event.clientY - prev.y;
      setView((current) => ({ ...current, tx: current.tx + dx, ty: current.ty + dy }));
    }
  }, []);

  const onPointerEnd = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    pointersRef.current.delete(event.pointerId);
    if (pointersRef.current.size < 2) pinchRef.current = null;
  }, []);

  const handleDownload = useCallback(() => {
    setMenuOpen(false);
    const isSvg = isSvgFragment(code);
    downloadBlob(
      new Blob([code], { type: isSvg ? 'image/svg+xml;charset=utf-8' : 'text/html;charset=utf-8' }),
      safeFileName(args?.title ?? 'visual', isSvg ? 'svg' : 'html')
    );
  }, [code, args?.title]);

  const handleSaveAsImage = useCallback(async () => {
    setMenuOpen(false);
    if (args === null || !code.trim()) return;
    try {
      const blob = isSvgFragment(code)
        ? await exportSvgFragmentAsImage(code)
        : await requestSandboxExportOrThrow(iframeRef.current, sandboxId);
      downloadBlob(blob, safeFileName(args.title, 'png'));
    } catch (error) {
      setActionError(`${i18n('Image export failed')}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }, [args, code, sandboxId]);

  const handleCopyCode = useCallback((event: React.MouseEvent) => {
    setMenuOpen(false);
    void copyToClipboard(code, event);
  }, [code, copyToClipboard]);

  const handleToggleView = useCallback(() => {
    setMenuOpen(false);
    setViewMode((mode) => (mode === 'ui' ? 'code' : 'ui'));
  }, []);

  if (isStreaming) {
    return (
      <CardShell title={partial?.title ?? i18n('Rendering…')}>
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Shapes className="size-4 shrink-0 animate-pulse" />
          <span>{loadingMessages[loadingIndex] ?? i18n('Rendering…')}</span>
        </div>
        <div className="mt-3 space-y-2">
          <div className="h-3 w-2/3 animate-pulse rounded bg-muted" />
          <div className="h-3 w-1/2 animate-pulse rounded bg-muted" />
          <div className="h-24 w-full animate-pulse rounded-lg bg-muted" />
        </div>
      </CardShell>
    );
  }

  const notice = noticeText({ tooLarge, emptyFragment, elidedPlaceholder, toolFailed, frameError, result, streamingOutput });

  if (noFragment) {
    return (
      <div>
        <ToolRowHeader
          toolName={toolCall.toolName}
          status={status ?? 'PENDING'}
          summary={args?.title.trim() || i18n('Visual')}
          expanded={rowExpanded}
          expandable={!!notice}
          onToggle={() => { markRowTouched(); setRowExpanded(prev => !prev); }}
        />
        {rowExpanded && notice && (
          <div className="px-3 pb-2">
            <ToolSection
              title={i18n('Result')}
              text={notice}
              tone={toolFailed ? 'error' : 'default'}
              maxHeightClass="max-h-[15em]"
            />
          </div>
        )}
        {toast}
      </div>
    );
  }

  return (
    <>
    <CardShell
      title={args?.title || i18n('Visual')}
      toolbar={
        args !== null ? (
          <div ref={toolbarRef} className="absolute right-2 top-1.5 flex items-center gap-1">
            <button
              type="button"
              onClick={() => setFullscreenOpen(true)}
              disabled={!sandboxUsable}
              aria-label={i18n('Fullscreen')}
              title={i18n('Fullscreen')}
              className="rounded-md border border-border bg-background/80 p-1 text-muted-foreground backdrop-blur transition-colors hover:bg-muted hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Maximize2 className="size-4" />
            </button>
            <button
              type="button"
              onClick={handleDownload}
              className="flex items-center gap-1 rounded-md border border-border bg-background/80 px-2 py-1 text-xs text-muted-foreground backdrop-blur transition-colors hover:bg-muted hover:text-foreground"
            >
              <Download className="size-3.5" />
              <span>{i18n('Download')}</span>
            </button>
            <button
              type="button"
              onClick={() => setMenuOpen((open) => !open)}
              aria-label={i18n('More')}
              className="rounded-md border border-border bg-background/80 p-1 text-muted-foreground backdrop-blur transition-colors hover:bg-muted hover:text-foreground"
            >
              <MoreHorizontal className="size-4" />
            </button>
            {menuOpen && (
              <div className="absolute right-0 top-9 z-20 w-44 rounded-md border border-border bg-popover p-1 shadow-md">
                <MenuItem
                  icon={ImageDown}
                  label={i18n('Save as Image')}
                  onClick={() => void handleSaveAsImage()}
                  disabled={!sandboxUsable && !isSvgFragment(code)}
                />
                <MenuItem icon={Copy} label={i18n('Copy Code')} onClick={handleCopyCode} />
                <MenuItem
                  icon={showCode ? Eye : Code2}
                  label={showCode ? i18n('Show UI') : i18n('View Code')}
                  onClick={handleToggleView}
                  disabled={!sandboxUsable}
                />
              </div>
            )}
          </div>
        ) : undefined
      }
    >
      {notice && (
        <div className="mb-2 flex items-start gap-1.5 text-xs text-muted-foreground">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0 text-amber-500" />
          <span className="break-all">{notice}</span>
        </div>
      )}
      {actionError && (
        <div className="mb-2 flex items-start gap-1.5 text-xs text-destructive">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
          <span className="break-all">{actionError}</span>
        </div>
      )}

      {args !== null && (
        <div className="relative">
          {sandboxUsable && (
            <div className={showCode ? 'invisible absolute inset-x-0 top-0 h-0 overflow-hidden' : undefined}>
              <iframe
                ref={iframeRef}
                title={args.title || 'render_visual'}
                srcDoc={srcdoc}
                sandbox="allow-scripts"
                onLoad={handleFrameLoad}
                className="w-full border-0 bg-transparent transition-[height] duration-300"
                style={{ height: frameHeight }}
              />
            </div>
          )}
          {showCode && <CodeBlock className={`language-${language}`}>{code}</CodeBlock>}
        </div>
      )}

      {toast}
    </CardShell>
    {fullscreenOpen && sandboxUsable && createPortal(
      <div
        className="fixed inset-0 z-50 flex flex-col bg-background"
        role="dialog"
        aria-label={args?.title || i18n('Visual')}
      >
        <div
          className="flex shrink-0 items-center justify-between gap-2 border-b border-border px-3 pb-2"
          style={{ paddingTop: 'calc(env(safe-area-inset-top, 0px) + 0.5rem)' }}
        >
          <span className="truncate text-sm font-medium">{args?.title || i18n('Visual')}</span>
          <div className="flex shrink-0 items-center gap-1">
            <button
              type="button"
              onClick={resetView}
              aria-label={i18n('Fit')}
              title={i18n('Fit')}
              className="mobile-hit rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <Scan className="size-5" />
            </button>
            <button
              type="button"
              onClick={() => setFullscreenOpen(false)}
              aria-label={i18n('Close')}
              className="mobile-hit rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <X className="size-5" />
            </button>
          </div>
        </div>
        <div
          ref={viewportRef}
          className="relative min-h-0 flex-1 overflow-hidden"
          style={{ touchAction: 'none' }}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerEnd}
          onPointerCancel={onPointerEnd}
        >
          <div
            className="absolute left-0 top-0"
            style={{
              width: fullscreenSize?.width ?? '100%',
              height: fullscreenSize?.height ?? '100%',
              transform: `translate(${view.tx}px, ${view.ty}px) scale(${view.scale})`,
              transformOrigin: '0 0',
            }}
          >
            <iframe
              ref={fullscreenFrameRef}
              title={args?.title || 'render_visual'}
              srcDoc={fullscreenSrcdoc}
              sandbox="allow-scripts"
              onLoad={handleFullscreenLoad}
              className="h-full w-full border-0 bg-transparent"
            />
          </div>
          <div
            className="pointer-events-none absolute inset-x-0 bottom-0 text-center text-xs text-muted-foreground"
            style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 0.5rem)' }}
          >
            {i18n('Pinch to zoom, drag to pan')}
          </div>
        </div>
      </div>,
      document.body
    )}
    </>
  );
}

function requestSandboxExportOrThrow(iframe: HTMLIFrameElement | null, id: string): Promise<Blob> {
  if (!iframe) return Promise.reject(new Error('preview is not mounted'));
  return requestSandboxExport(iframe, id);
}

interface NoticeInput {
  tooLarge: boolean;
  emptyFragment: boolean;
  elidedPlaceholder: boolean;
  toolFailed: boolean;
  frameError: string | null;
  result: ToolMessageProps['result'];
  streamingOutput: ToolMessageProps['streamingOutput'];
}

function noticeText(input: NoticeInput): string | null {
  if (input.tooLarge) return i18n('Fragment too large, shown as source');
  if (input.frameError !== null) return i18n('Render failed, shown as source');
  // The tool's own rejection says what the model did wrong, so it beats generic wording.
  if (input.toolFailed) {
    return extractOutput({ result: input.result, streamingOutput: input.streamingOutput }) || i18n('Render failed, shown as source');
  }
  if (input.elidedPlaceholder) return i18n('No fragment: the elision placeholder was sent as the code');
  if (input.emptyFragment) return i18n('Empty fragment');
  return null;
}

function CardShell({ title, toolbar, children }: { title: string; toolbar?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="relative rounded-xl border border-border bg-card">
      <div className="flex items-center gap-2 border-b border-border px-3 py-2 pr-36">
        <Shapes className="size-4 shrink-0 text-muted-foreground" />
        <span className="truncate text-sm font-medium">{title}</span>
      </div>
      {toolbar}
      <div className="p-3">{children}</div>
    </div>
  );
}

function MenuItem({ icon: Icon, label, onClick, disabled }: {
  icon: LucideIcon;
  label: string;
  onClick: (event: React.MouseEvent) => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs text-popover-foreground transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
    >
      <Icon className="size-3.5 shrink-0" />
      <span>{label}</span>
    </button>
  );
}
