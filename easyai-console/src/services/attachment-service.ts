import type { Attachment } from '@/types/message';
import { isImageAttachment, isTextAttachment } from '@/utils/attachment-utils';
import { authFetch } from './api-client';

export interface AttachmentUploadResult {
  filePath: string;
  name: string;
  mimeType: string;
  /** Temporary display URL, never sent as part of a chat attachment. */
  url?: string;
}

export async function uploadFile(file: File, sessionId: string): Promise<AttachmentUploadResult> {
  if (!sessionId) throw new Error('Create a session before uploading attachments');
  const formData = new FormData();
  formData.append('file', file);
  const response = await authFetch(`/api/files/upload?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST', body: formData });
  if (!response.ok) throw new Error(`Upload failed (${response.status})`);
  const result: AttachmentUploadResult = await response.json();
  if (!result || typeof result.filePath !== 'string' || !result.filePath.trim()
    || typeof result.name !== 'string' || typeof result.mimeType !== 'string'
    || (result.url !== undefined && typeof result.url !== 'string')) {
    throw new Error('Invalid attachment upload response');
  }
  return result;
}

export async function uploadBase64Attachment(attachment: Attachment, sessionId: string): Promise<AttachmentUploadResult> {
  const binary = atob(attachment.data);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return uploadFile(new File([bytes], attachment.name, { type: attachment.mimeType }), sessionId);
}

/** Validate the whole draft before uploading or performing a destructive edit. */
export function validateAttachments(attachments: Attachment[]): void {
  for (const attachment of attachments) {
    if (attachment.filePath) continue;
    if (!isImageAttachment(attachment) && !isTextAttachment(attachment)) {
      throw new Error(`Unsupported file type: ${attachment.name}. Only images and text files are supported.`);
    }
    if (!attachment.data && (isImageAttachment(attachment) || attachment.size > 0)) {
      throw new Error(`Missing attachment data: ${attachment.name}. Please attach the file again.`);
    }
  }
}

/** Save each success immediately so a failed batch can be retried without re-uploading it. */
export async function uploadAttachments(
  attachments: Attachment[],
  sessionId: string,
  onUploaded: (attachment: Attachment) => void,
): Promise<Attachment[]> {
  validateAttachments(attachments);
  const uploaded: Attachment[] = [];
  for (const attachment of attachments) {
    if (attachment.filePath) {
      const ready = { ...attachment, data: '' };
      uploaded.push(ready);
      if (attachment.data) onUploaded(ready);
      continue;
    }
    if (!isImageAttachment(attachment)) {
      // Text drafts retain their original inline-message behavior, including empty files.
      uploaded.push(attachment);
      continue;
    }
    let result: AttachmentUploadResult;
    try {
      result = await uploadBase64Attachment(attachment, sessionId);
    } catch (error) {
      const reason = error instanceof Error ? error.message : 'Upload failed';
      throw new Error(`Failed to upload ${attachment.name}: ${reason}. Draft kept; please retry.`);
    }
    const ready = { ...attachment, ...result, data: '' };
    onUploaded(ready);
    uploaded.push(ready);
  }
  return uploaded;
}
