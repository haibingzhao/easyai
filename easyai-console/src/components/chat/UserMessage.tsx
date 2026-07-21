import React, { useState } from 'react';
import type { Message, Attachment } from '../../types/message';
import { getAttachmentIcon, isImageAttachment, splitByFileRefs } from '../../utils/attachment-utils';
import { RotateCw, X } from 'lucide-react';

/** Parse message content and render command prefix (e.g. /goal) as a styled chip, plus file/folder references */
function renderContent(content: string) {
  // Step 1: Extract command prefix if present
  const cmdMatch = content.match(/^(\/[a-zA-Z_]\w*)([\s\S]*)$/);
  let cmdChip = null;
  let textContent = content;
  if (cmdMatch) {
    cmdChip = <span className="command-chip">{cmdMatch[1]}</span>;
    textContent = cmdMatch[2];
  }

  // Step 2: Split remaining text by file/folder references
  const segments = splitByFileRefs(textContent);
  
  // Step 3: If no refs and no command, return plain text (fast path)
  if (!cmdChip && segments.length === 1 && segments[0].type === 'text') {
    return content;
  }

  // Step 4: Render segments
  return (
    <>
      {cmdChip}
      {segments.map((seg, i) => {
        if (seg.type === 'fileRef') {
          return (
            <span key={i} className="mention-chip mention-file" title={seg.path}>
              📄 {seg.name}
            </span>
          );
        }
        if (seg.type === 'folderRef') {
          return (
            <span key={i} className="mention-chip mention-folder" title={seg.path}>
              📁 {seg.name}
            </span>
          );
        }
        return <React.Fragment key={i}>{seg.text}</React.Fragment>;
      })}
    </>
  );
}

interface UserMessageProps {
  message: Message & { role: 'user' | 'user-with-attachments' };
  /** Whether this message can be edited (has messageId, not streaming, not system) */
  isEditable?: boolean;
  /** Called when user clicks the message to edit */
  onEditClick?: () => void;
  /** Called when user double-clicks the message container */
  onDoubleClick?: (e: React.MouseEvent) => void;
}

/** Get the image src for an attachment (filePath URL or base64 data URL) */
function getAttachmentSrc(att: { mimeType: string; data: string; filePath?: string }): string {
  if (att.filePath) {
    return `/api/files/serve?path=${encodeURIComponent(att.filePath)}`;
  }
  return `data:${att.mimeType};base64,${att.data}`;
}

/** Render image thumbnails and file chips for attachments */
function AttachmentPreview({ attachments }: { attachments: Attachment[] }) {
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const images = attachments.filter(isImageAttachment);
  const files = attachments.filter((a) => !isImageAttachment(a));

  return (
    <>
      {/* Image thumbnails */}
      {images.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {images.map((img) => {
            const src = getAttachmentSrc(img);
            return (
              <img
                key={img.id}
                src={src}
                alt={img.name}
                className="w-20 h-20 object-cover rounded-md border border-border cursor-pointer hover:opacity-80 transition-opacity"
                onClick={(e) => {
                  e.stopPropagation();
                  setLightboxSrc(src);
                }}
              />
            );
          })}
        </div>
      )}
      {/* File chips */}
      {files.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {files.map((attachment) => (
            <div
              key={attachment.id}
              className="flex items-center gap-2 px-3 py-1.5 bg-background rounded-md text-sm border border-border"
            >
              <span>{getAttachmentIcon(attachment.mimeType)}</span>
              <span className="truncate max-w-[150px]">{attachment.name}</span>
            </div>
          ))}
        </div>
      )}
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
}

export const UserMessage: React.FC<UserMessageProps> = ({ message, isEditable, onEditClick, onDoubleClick }) => {
  // Check if this is a system-injected completion check message
  const isCompletionCheck = message.metadata?.source === 'completion_check';
  const isFollowUp = message.metadata?.source === 'follow_up';
  const isSteering = message.metadata?.source === 'steering';
  const isSystemMessage = isCompletionCheck || isFollowUp || isSteering;

  return (
    <div
      className="flex justify-end mx-4"
      data-user-message={message.role === 'user' || message.role === 'user-with-attachments' ? 'true' : undefined}
      onDoubleClick={onDoubleClick}
    >
      <div
        className={`user-message-container py-2 px-4 rounded-xl max-w-[80%] ${
          isCompletionCheck 
            ? 'bg-amber-50 dark:bg-amber-950/20 border border-amber-200 dark:border-amber-800' 
            : 'bg-blue-50 dark:bg-blue-950/25'
        } ${isEditable && !isSystemMessage ? 'cursor-pointer hover:bg-blue-100 dark:hover:bg-blue-900/30 transition-colors' : ''}`}
        onClick={isEditable && !isSystemMessage ? onEditClick : undefined}
        title={isEditable && !isSystemMessage ? 'Click to edit' : undefined}
      >
        {isCompletionCheck && (
          <div className="flex items-center gap-1.5 text-xs text-amber-600 dark:text-amber-400 mb-1">
            <RotateCw className="w-3 h-3" />
            <span>Auto-continue</span>
          </div>
        )}
        <div className="whitespace-pre-wrap text-sm">{renderContent(message.content)}</div>
        
        {message.role === 'user-with-attachments' && message.attachments && message.attachments.length > 0 && (
          <AttachmentPreview attachments={message.attachments} />
        )}
      </div>
    </div>
  );
};
