import { useState, useRef, useCallback } from 'react';
import type { Attachment } from '@/types/message';
import { loadAttachment } from '@/utils/attachment-utils';
import { i18n } from '@/utils/i18n';
import { authFetch } from '@/services/api-client';

interface UseAttachmentManagerOptions {
  visionSupported: boolean;
  onError?: (message: string) => void;
  initialAttachments?: Attachment[];
  /** When provided, files are uploaded to the backend immediately (getting filePath). */
  sessionId?: string | null;
}

interface UseAttachmentManagerReturn {
  attachments: Attachment[];
  setAttachments: React.Dispatch<React.SetStateAction<Attachment[]>>;
  processingFiles: boolean;
  fileInputRef: React.RefObject<HTMLInputElement>;
  handleFiles: (files: File[]) => Promise<void>;
  removeAttachment: (id: string) => void;
  /** Extract image files from a paste event. Returns image files array, or empty if none. */
  getImageFilesFromPaste: (e: React.ClipboardEvent) => File[];
}

/** Upload a single file to the backend and return the response. */
async function uploadFile(file: File, sessionId: string): Promise<{ filePath: string; name: string; mimeType: string }> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('sessionId', sessionId);
  const response = await authFetch('/api/files/upload', { method: 'POST', body: formData });
  if (!response.ok) throw new Error(`Upload failed: ${response.statusText}`);
  return response.json();
}

export function useAttachmentManager({
  visionSupported,
  onError,
  initialAttachments = [],
  sessionId,
}: UseAttachmentManagerOptions): UseAttachmentManagerReturn {
  const [attachments, setAttachments] = useState<Attachment[]>(initialAttachments);
  const [processingFiles, setProcessingFiles] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFiles = useCallback(async (files: File[]) => {
    setProcessingFiles(true);
    const newAttachments: Attachment[] = [];

    for (const file of files) {
      if (file.type.startsWith('image/') && file.size > 6 * 1024 * 1024) {
        onError?.(`${file.name} exceeds maximum image size of 6MB`);
        continue;
      }
      if (file.size > 20 * 1024 * 1024) {
        alert(`${file.name} exceeds maximum size of 20MB`);
        continue;
      }
      if (file.type.startsWith('image/') && !visionSupported) {
        onError?.(i18n('Current model does not support image input'));
        continue;
      }
      try {
        const id = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        if (sessionId) {
          // Upload to backend to get filePath (preferred path — no base64 in memory)
          const result = await uploadFile(file, sessionId);
          newAttachments.push({
            id,
            name: result.name || file.name,
            mimeType: result.mimeType || file.type,
            data: '',
            size: file.size,
            filePath: result.filePath,
          });
        } else {
          // Fallback: read as base64 (will be uploaded at send time)
          const attachment = await loadAttachment(file);
          newAttachments.push(attachment);
        }
      } catch (err) {
        console.error(`Error processing ${file.name}:`, err);
        // Fallback to base64 on upload failure
        try {
          const attachment = await loadAttachment(file);
          newAttachments.push(attachment);
        } catch {
          console.error(`Fallback also failed for ${file.name}`);
        }
      }
    }

    // Use functional update to get latest attachments count, preventing stale closure issues
    setAttachments((prev) => {
      const allowed = Math.max(0, 10 - prev.length);
      if (allowed === 0) return prev;
      const toAdd = newAttachments.slice(0, allowed);
      if (newAttachments.length > allowed) {
        // Could alert, but the user already sees the limit in the UI
      }
      return [...prev, ...toAdd];
    });
    setProcessingFiles(false);
  }, [visionSupported, onError, sessionId]);

  const removeAttachment = useCallback((id: string) => {
    setAttachments((prev) => prev.filter((a) => a.id !== id));
  }, []);

  const getImageFilesFromPaste = useCallback((e: React.ClipboardEvent): File[] => {
    const items = e.clipboardData?.items;
    if (!items) return [];
    const imageFiles: File[] = [];
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (item.type.startsWith('image/')) {
        const file = item.getAsFile();
        if (file) imageFiles.push(file);
      }
    }
    return imageFiles;
  }, []);

  return {
    attachments,
    setAttachments,
    processingFiles,
    fileInputRef,
    handleFiles,
    removeAttachment,
    getImageFilesFromPaste,
  };
}
