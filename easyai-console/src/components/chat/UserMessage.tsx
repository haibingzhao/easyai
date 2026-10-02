import React from 'react';
import type { Message, Attachment } from '../../types/message';
import { getAttachmentIcon, isImageAttachment, parseFileRefs, splitByFileRefs, copyMessageSelection } from '../../utils/attachment-utils';
import { commandLabel, commandTooltip, parseCommand, serializeCommand } from '@/utils/command-utils';
import { Bell, Pencil, RotateCw } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { AttachmentImage } from './AttachmentImage';

/** Parse message content and render command prefix (e.g. /goal) as a styled chip, plus file/folder references */
export function UserMessageContent({ content, attachments = [] }: { content: string; attachments?: Attachment[] }) {
  const parsed = parseCommand(content);
  const cmdChip = parsed ? (
    <span className="command-chip" title={commandTooltip(parsed.command)} data-command-token={serializeCommand(parsed.command, '')}>
      {commandLabel(parsed.command)}
    </span>
  ) : null;
  const textContent = parsed ? parsed.args : content;

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
          const image = attachments.find((attachment) => attachment.filePath === seg.path && attachment.mimeType.startsWith('image/'));
          if (image) {
            return <AttachmentImage key={i} attachment={{ ...image, name: seg.name }} inline className="w-4 h-4 shrink-0 rounded-sm object-cover" />;
          }
          return (
            <span key={i} className="mention-chip mention-file" title={seg.path} data-path={seg.path} data-name={seg.name} data-type="file">
              📄 {seg.name}
            </span>
          );
        }
        if (seg.type === 'folderRef') {
          return (
            <span key={i} className="mention-chip mention-folder" title={seg.path} data-path={seg.path} data-name={seg.name} data-type="directory">
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
  /** Called when user clicks the edit icon in the hover time bar */
  onEditClick?: () => void;
  /** Called when user double-clicks the message container */
  onDoubleClick?: (e: React.MouseEvent) => void;
}

/** Render image thumbnails and file chips for attachments */
function AttachmentPreview({ attachments }: { attachments: Attachment[] }) {
  const images = attachments.filter(isImageAttachment);
  const files = attachments.filter((a) => !isImageAttachment(a));

  return (
    <>
      {/* Image thumbnails */}
      {images.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {images.map((img) => (
            <AttachmentImage
              key={img.id}
              attachment={img}
              className="w-20 h-20 object-cover rounded-md border border-border cursor-pointer hover:opacity-80 transition-opacity"
            />
          ))}
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
    </>
  );
}

export const UserMessage: React.FC<UserMessageProps> = ({ message, isEditable, onEditClick, onDoubleClick }) => {
  // Check if this is a system-injected completion check message
  const isCompletionCheck = message.metadata?.source === 'completion_check';
  // System-steered messages (background task results, resume guidance) — never user-typed
  const systemOrigin = message.metadata?.systemOrigin;
  const isSystemSteer = !!systemOrigin;
  const attachments = message.role === 'user-with-attachments' ? message.attachments ?? [] : [];
  const inlineRefPaths = new Set(parseFileRefs(message.content).map((ref) => ref.path));
  const previewAttachments = attachments.filter((attachment) =>
    !attachment.mimeType.startsWith('image/') || !attachment.filePath || !inlineRefPaths.has(attachment.filePath),
  );

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
            : isSystemSteer
            ? 'bg-gray-100 dark:bg-gray-800/70 border border-dashed border-gray-300 dark:border-gray-600'
            : 'bg-blue-50 dark:bg-blue-950/25'
        }`}
      >
        {isCompletionCheck && (
          <div className="flex items-center gap-1.5 text-xs text-amber-600 dark:text-amber-400 mb-1">
            <RotateCw className="w-3 h-3" />
            <span>Auto-continue</span>
          </div>
        )}
        {isSystemSteer && (
          <div className="flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400 mb-1">
            <Bell className="w-3 h-3" />
            <span>{systemOrigin === 'background_task' ? 'Background task' : 'System'}</span>
          </div>
        )}
        <div
          className={`whitespace-pre-wrap text-sm ${isSystemSteer ? 'text-gray-600 dark:text-gray-300' : 'text-gray-800 dark:text-blue-100'}`}
          data-message-content={message.content}
          onCopy={(e) => { if (copyMessageSelection(e.currentTarget, e.clipboardData)) e.preventDefault(); }}
        >
          <UserMessageContent content={message.content} attachments={attachments} />
        </div>
        
        {previewAttachments.length > 0 && (
          <AttachmentPreview attachments={previewAttachments} />
        )}

        {/* Inline time bar: shown on hover, with the edit entry */}
        <div className="opacity-0 group-hover:opacity-100 transition-opacity duration-150 pt-1">
          <div className="flex items-center justify-end gap-1 text-[11px] text-muted-foreground/60 tabular-nums">
            <span>{new Date(message.timestamp).toLocaleTimeString()}</span>
            {isEditable && (
              <button
                onClick={onEditClick}
                className="p-1 -my-1 rounded hover:bg-muted hover:text-foreground transition-colors"
                title={i18n('Edit')}
              >
                <Pencil className="w-3 h-3" />
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
