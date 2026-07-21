import React, { useState } from 'react';
import type { Attachment } from '@/types/message';
import { formatFileSize, isImageAttachment } from '@/utils/attachment-utils';
import { X } from 'lucide-react';

interface AttachmentPreviewBarProps {
  attachments: Attachment[];
  onRemove: (id: string) => void;
  /** Show image thumbnails (InlineEditMessage style). Default: false (filename only). */
  showImageThumbnails?: boolean;
}

function getSrc(att: Attachment): string {
  return att.filePath
    ? `/api/files/serve?path=${encodeURIComponent(att.filePath)}`
    : `data:${att.mimeType};base64,${att.data}`;
}

export const AttachmentPreviewBar: React.FC<AttachmentPreviewBarProps> = ({
  attachments,
  onRemove,
  showImageThumbnails = false,
}) => {
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);

  if (attachments.length === 0) return null;

  return (
    <>
      <div className="flex flex-wrap gap-2">
        {attachments.map((attachment) => {
          const isImg = isImageAttachment(attachment);
          const src = isImg ? getSrc(attachment) : '';
          return (
            <div
              key={attachment.id}
              className="flex items-center gap-2 px-3 py-1.5 bg-muted rounded-md text-sm"
            >
              {showImageThumbnails && isImg ? (
                <img
                  src={src}
                  alt={attachment.name}
                  className="w-10 h-10 object-cover rounded cursor-pointer hover:opacity-80 transition-opacity"
                  onClick={(e) => {
                    e.stopPropagation();
                    setLightboxSrc(src);
                  }}
                />
              ) : null}
              <span className="truncate max-w-[150px]">{attachment.name}</span>
              <span className="text-xs text-muted-foreground">{formatFileSize(attachment.size)}</span>
              <button
                onClick={() => onRemove(attachment.id)}
                className="text-muted-foreground hover:text-foreground ml-1"
              >
                ×
              </button>
            </div>
          );
        })}
      </div>
      {/* Lightbox overlay */}
      {lightboxSrc && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
          onClick={() => setLightboxSrc(null)}
        >
          <button
            className="absolute top-4 right-4 p-2 text-white hover:text-gray-300 transition-colors"
            onClick={() => setLightboxSrc(null)}
          >
            <X className="w-6 h-6" />
          </button>
          <img
            src={lightboxSrc}
            alt="Full size"
            className="max-w-[90vw] max-h-[90vh] object-contain rounded-lg"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}
    </>
  );
};
