import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { ImageOff, Loader2, X } from 'lucide-react';
import type { Attachment } from '@/types/message';
import { useAttachmentImage } from '@/hooks/useAttachmentImage';

/** One loader owns both the thumbnail and the lightbox, including authenticated Blob URLs. */
export function AttachmentImage({ attachment, className }: { attachment: Attachment; className: string }) {
  const { src, error, onError, retry } = useAttachmentImage(attachment);
  const [lightboxOpen, setLightboxOpen] = useState(false);

  useEffect(() => {
    if (!lightboxOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        setLightboxOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [lightboxOpen]);

  const placeholder = (
    <button
      type="button"
      className={`${className} flex flex-col items-center justify-center gap-1 bg-muted text-muted-foreground text-xs`}
      title={error ? `Image unavailable: ${attachment.name}. Click to retry.` : `Loading ${attachment.name}`}
      aria-label={error ? `Retry image ${attachment.name}` : `Loading image ${attachment.name}`}
      disabled={!error}
      onClick={(event) => { event.stopPropagation(); retry(); }}
    >
      {error ? <><ImageOff className="w-4 h-4" /><span>Retry</span></> : <Loader2 className="w-4 h-4 animate-spin" />}
    </button>
  );

  return (
    <>
      {src ? (
        <img
          src={src}
          alt={attachment.name}
          className={className}
          onError={(event) => onError(event.currentTarget.getAttribute('src') ?? '')}
          onClick={(event) => { event.stopPropagation(); setLightboxOpen(true); }}
        />
      ) : placeholder}
      {lightboxOpen && createPortal(
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
          role="dialog"
          aria-label={attachment.name}
          onClick={(event) => { event.stopPropagation(); setLightboxOpen(false); }}
        >
          <button
            type="button"
            aria-label="Close image"
            className="absolute top-4 right-4 p-2 text-white hover:text-gray-300 transition-colors"
            onClick={(event) => { event.stopPropagation(); setLightboxOpen(false); }}
          >
            <X className="w-6 h-6" />
          </button>
          {src ? (
            <img
              src={src}
              alt={attachment.name}
              className="max-w-[90vw] max-h-[90vh] object-contain rounded-lg"
              onError={(event) => onError(event.currentTarget.getAttribute('src') ?? '')}
              onClick={(event) => event.stopPropagation()}
            />
          ) : placeholder}
        </div>,
        document.body,
      )}
    </>
  );
}
