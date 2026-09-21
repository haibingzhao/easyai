/**
 * Media generation tool renderer.
 *
 * Parses the small JSON contract the generate_image / generate_speech / generate_video tools place in
 * ToolResultContent.output and renders the produced artifact inline. Bytes are never inlined — the card
 * loads them from the stable /api/media/file endpoint, appending the JWT as a query token because
 * <img>/<audio>/<video> cannot send an Authorization header. A pending video shows its task id so the
 * model can re-query.
 */

import { Image as ImageIcon, AudioLines, Video, AlertCircle, Clock } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { extractOutput } from './parsers';
import { getAccessToken } from '@/services/api-client';
import { i18n } from '@/utils/i18n';

interface MediaItem {
  url: string;
  key: string;
  mimeType: string;
  sizeBytes: number;
  durationMs?: number | null;
}

interface MediaResult {
  kind: 'speech' | 'image' | 'video' | string;
  status: 'completed' | 'pending' | 'failed' | string;
  items?: MediaItem[];
  taskId?: string;
  error?: string;
}

/** Append the access token so a resource element (which can't set headers) authenticates. */
function authedUrl(url: string): string {
  const token = getAccessToken();
  if (!token) return url;
  const sep = url.includes('?') ? '&' : '?';
  return `${url}${sep}token=${encodeURIComponent(token)}`;
}

function parseMedia(output: string): MediaResult | null {
  if (!output) return null;
  const trimmed = output.trim();
  if (!trimmed.startsWith('{')) return null;
  try {
    return JSON.parse(trimmed) as MediaResult;
  } catch {
    return null;
  }
}

function formatBytes(n: number): string {
  if (!n) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

export function MediaResultCard({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const isError = (result?.isError ?? false) || status === 'FAILED';
  const media = parseMedia(extractOutput({ result, streamingOutput }));

  // Not a media payload we understand (e.g. a raw error string): show it plainly.
  if (!media) {
    const text = extractOutput({ result, streamingOutput });
    return (
      <ToolShell title={titleFor(toolCall.toolName)}>
        {isError ? (
          <div className="flex items-start gap-2 text-sm text-destructive">
            <AlertCircle className="size-4 mt-0.5 shrink-0" />
            <span className="break-all">{text || i18n('Generation failed')}</span>
          </div>
        ) : (
          <span className="text-sm text-muted-foreground">{text || i18n('Working…')}</span>
        )}
      </ToolShell>
    );
  }

  if (media.status === 'pending') {
    return (
      <ToolShell title={titleFor(toolCall.toolName)}>
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Clock className="size-4 shrink-0 animate-pulse" />
          <span>{i18n('Still rendering')} {media.taskId ? `(task ${media.taskId})` : ''}</span>
        </div>
      </ToolShell>
    );
  }

  if (media.status === 'failed' || isError) {
    return (
      <ToolShell title={titleFor(toolCall.toolName)}>
        <div className="flex items-start gap-2 text-sm text-destructive">
          <AlertCircle className="size-4 mt-0.5 shrink-0" />
          <span className="break-all">{media.error || i18n('Generation failed')}</span>
        </div>
      </ToolShell>
    );
  }

  const items = media.items ?? [];
  return (
    <ToolShell title={titleFor(toolCall.toolName)}>
      <div className="space-y-3">
        {items.map((item) => (
          <MediaPreview key={item.key} kind={media.kind} item={item} />
        ))}
      </div>
    </ToolShell>
  );
}

function MediaPreview({ kind, item }: { kind: string; item: MediaItem }) {
  const src = authedUrl(item.url);
  if (kind === 'image' || item.mimeType.startsWith('image/')) {
    return (
      <a href={src} target="_blank" rel="noreferrer">
        <img src={src} alt="" className="max-w-full rounded-lg border border-border" />
      </a>
    );
  }
  if (kind === 'speech' || item.mimeType.startsWith('audio/')) {
    return <audio src={src} controls className="w-full" />;
  }
  if (kind === 'video' || item.mimeType.startsWith('video/')) {
    return <video src={src} controls className="max-w-full rounded-lg border border-border" />;
  }
  return (
    <a href={src} target="_blank" rel="noreferrer" className="text-sm text-primary underline">
      {item.key} ({formatBytes(item.sizeBytes)})
    </a>
  );
}

function titleFor(toolName: string): string {
  switch (toolName) {
    case 'generate_image': return i18n('Image');
    case 'generate_speech': return i18n('Speech');
    case 'generate_video': return i18n('Video');
    default: return toolName;
  }
}

function ToolShell({ title, children }: { title: string; children: React.ReactNode }) {
  const Icon = title === i18n('Speech') ? AudioLines : title === i18n('Video') ? Video : ImageIcon;
  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      <div className="px-3 py-2 flex items-center gap-2 border-b border-border">
        <Icon className="size-4 shrink-0 text-muted-foreground" />
        <span className="text-sm font-medium">{title}</span>
      </div>
      <div className="p-3">{children}</div>
    </div>
  );
}
