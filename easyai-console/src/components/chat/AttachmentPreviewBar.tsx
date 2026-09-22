import React from 'react';
import type { Attachment } from '@/types/message';
import { formatFileSize, isImageAttachment } from '@/utils/attachment-utils';
import { AttachmentImage } from './AttachmentImage';

interface AttachmentPreviewBarProps {
  attachments: Attachment[];
  onRemove: (id: string) => void;
  disabled?: boolean;
  /** Show image thumbnails (InlineEditMessage style). Default: false (filename only). */
  showImageThumbnails?: boolean;
}

export const AttachmentPreviewBar: React.FC<AttachmentPreviewBarProps> = ({
  attachments,
  onRemove,
  disabled = false,
  showImageThumbnails = false,
}) => {
  if (attachments.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-2">
      {attachments.map((attachment) => (
        <div
          key={attachment.id}
          className="flex items-center gap-2 px-3 py-1.5 bg-muted rounded-md text-sm"
        >
          {showImageThumbnails && isImageAttachment(attachment) && (
            <AttachmentImage
              attachment={attachment}
              className="w-10 h-10 object-cover rounded cursor-pointer hover:opacity-80 transition-opacity"
            />
          )}
          <span className="truncate max-w-[150px]">{attachment.name}</span>
          <span className="text-xs text-muted-foreground">{formatFileSize(attachment.size)}</span>
          <button
            onClick={() => onRemove(attachment.id)}
            disabled={disabled}
            className="text-muted-foreground hover:text-foreground ml-1 disabled:opacity-50"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
};
