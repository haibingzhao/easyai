/**
 * render_visual card renderer.
 *
 * The fragment is the deliverable, so this card never collapses: the `compact`
 * prop is deliberately ignored and no collapsible wrapper is used — a folded
 * visual would hide exactly what the model asked the user to look at.
 *
 * The sandbox iframe stays mounted in code view (hidden, zero height) so the
 * live DOM survives a view toggle and "save as image" can rasterize what the
 * user actually saw, including anything scripts produced.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Code2, Copy, Download, Eye, ImageDown, MoreHorizontal, Shapes } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { CodeBlock } from '../CodeBlock';
import { useCopyToast } from './useCopyToast';
import { extractOutput } from './parsers';
import { i18n } from '@/utils/i18n';
import { MAX_FRAGMENT_BYTES, byteLength, extractPartialArgs, isSvgFragment, parseArgs } from './render-visual/params';
import { sanitizeFragment } from './render-visual/sanitize';
import { RV_MSG, buildShellSrcdoc, isRenderVisualSandboxMessage } from './render-visual/shell';
import { currentMode, subscribeTheme, tokensCssForMode } from './render-visual/theme';
import { clampHeight } from './render-visual/height';
import { downloadBlob, exportSvgFragmentAsImage, requestSandboxExport, safeFileName } from './render-visual/exportImage';

const ROTATE_INTERVAL_MS = 1400;
const ACTION_ERROR_TIMEOUT_MS = 4000;

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
  const toolFailed = result?.isError === true || status === 'FAILED';

  const [viewMode, setViewMode] = useState<ViewMode>('ui');
  const [menuOpen, setMenuOpen] = useState(false);
  const [frameHeight, setFrameHeight] = useState(0);
  const [frameError, setFrameError] = useState<string | null>(null);
  const [loadingIndex, setLoadingIndex] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  const toolbarRef = useRef<HTMLDivElement | null>(null);
  const { copyToClipboard, toast } = useCopyToast();

  const sandboxId = toolCall.id;
  /** Without a renderable fragment a sandbox is pointless — those cases stay in source view. */
  const sandboxUsable = args !== null && !tooLarge && !emptyFragment && !toolFailed;
  const srcdoc = useMemo(() => buildShellSrcdoc(sandboxId, tokensCssForMode(currentMode())), [sandboxId]);
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
    iframeRef.current?.contentWindow?.postMessage(
      { type: RV_MSG.THEME, id: sandboxId, css: tokensCssForMode(mode) },
      '*'
    );
  }), [sandboxId]);

  useEffect(() => {
    const listener = (event: MessageEvent) => {
      const data: unknown = event.data;
      if (!isRenderVisualSandboxMessage(data, sandboxId)) return;
      if (data.type === RV_MSG.HEIGHT) setFrameHeight(clampHeight(data.height));
      else if (data.type === RV_MSG.ERROR) {
        setFrameError(data.message);
        // Degrade to source view, but keep the sandbox mounted so the user can
        // toggle back and still export the partially rendered fragment.
        setViewMode('code');
      }
    };
    window.addEventListener('message', listener);
    return () => window.removeEventListener('message', listener);
  }, [sandboxId]);

  const handleFrameLoad = useCallback(() => {
    const frame = iframeRef.current?.contentWindow;
    if (!frame) return;
    frame.postMessage({ type: RV_MSG.MOUNT, id: sandboxId, html: sanitized }, '*');
    frame.postMessage({ type: RV_MSG.THEME, id: sandboxId, css: tokensCssForMode(currentMode()) }, '*');
  }, [sandboxId, sanitized]);

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

  const notice = noticeText({ tooLarge, emptyFragment, toolFailed, frameError, result, streamingOutput });

  return (
    <CardShell
      title={args?.title || i18n('Visual')}
      toolbar={
        args !== null && code.trim() ? (
          <div ref={toolbarRef} className="absolute right-2 top-1.5 flex items-center gap-1">
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
          {showCode && code.trim() !== '' && <CodeBlock className={`language-${language}`}>{code}</CodeBlock>}
        </div>
      )}

      {toast}
    </CardShell>
  );
}

function requestSandboxExportOrThrow(iframe: HTMLIFrameElement | null, id: string): Promise<Blob> {
  if (!iframe) return Promise.reject(new Error('preview is not mounted'));
  return requestSandboxExport(iframe, id);
}

interface NoticeInput {
  tooLarge: boolean;
  emptyFragment: boolean;
  toolFailed: boolean;
  frameError: string | null;
  result: ToolMessageProps['result'];
  streamingOutput: ToolMessageProps['streamingOutput'];
}

function noticeText(input: NoticeInput): string | null {
  if (input.tooLarge) return i18n('Fragment too large, shown as source');
  if (input.emptyFragment) return i18n('Empty fragment');
  if (input.frameError !== null) return i18n('Render failed, shown as source');
  if (input.toolFailed) {
    return extractOutput({ result: input.result, streamingOutput: input.streamingOutput }) || i18n('Render failed, shown as source');
  }
  return null;
}

function CardShell({ title, toolbar, children }: { title: string; toolbar?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="relative overflow-hidden rounded-xl border border-border bg-card">
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
