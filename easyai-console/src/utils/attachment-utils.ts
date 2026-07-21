import type { Attachment } from '../types/message';
import type { ChatAttachment } from '../types/socket-request';

/** Invisible character used to wrap file references in message text. */
export const FILE_REF_CHAR = '\u201b';

/** Emoji prefix used in folder reference names to distinguish from file references. */
export const FOLDER_PREFIX = '\u{1F4C1}'; // 📁

/** Create a fresh regex for matching file/folder refs (must be new instance each time due to /g stateful lastIndex) */
function createRefRegex(): RegExp {
  return new RegExp(`${FILE_REF_CHAR}\\[([^\\]]+)\\]\\(([\\s\\S]+?)\\)${FILE_REF_CHAR}`, 'g');
}

/** Parse file references from message text. Returns array of { name, path }. */
export function parseFileRefs(text: string): { name: string; path: string }[] {
  const re = createRefRegex();
  const refs: { name: string; path: string }[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m[1].startsWith(FOLDER_PREFIX)) continue; // skip folder refs
    refs.push({ name: m[1], path: m[2] });
  }
  return refs;
}

/** Build a file reference string to embed in message text. */
export function buildFileRef(name: string, path: string): string {
  return `${FILE_REF_CHAR}[${name}](${path})${FILE_REF_CHAR}`;
}

export async function loadAttachment(file: File): Promise<Attachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      resolve({
        id: `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
        name: file.name,
        mimeType: file.type,
        data: result.split(',')[1],
        size: file.size,
      });
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

export function getAttachmentIcon(mimeType: string): string {
  if (mimeType.startsWith('image/')) return '🖼️';
  if (mimeType.includes('text')) return '📃';
  return '📎';
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Supported image MIME types for vision-capable models */
const IMAGE_MIMES = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp']);

/** Text-based MIME types that can be inlined into the message */
const TEXT_MIMES = new Set([
  'text/plain', 'text/markdown', 'text/csv', 'text/html', 'text/css',
  'text/xml', 'text/yaml', 'application/json', 'application/xml',
  'application/yaml', 'application/x-yaml',
]);

/** Text-like file extensions for MIME types that may not be recognized */
const TEXT_EXTENSIONS = new Set([
  '.txt', '.md', '.json', '.xml', '.yml', '.yaml', '.csv',
  '.html', '.css', '.js', '.ts', '.jsx', '.tsx', '.py', '.java',
  '.kt', '.go', '.rs', '.rb', '.sh', '.sql', '.toml', '.ini', '.cfg',
]);

export function isImageAttachment(a: Attachment): boolean {
  return IMAGE_MIMES.has(a.mimeType);
}

export function isTextAttachment(a: Attachment): boolean {
  if (TEXT_MIMES.has(a.mimeType)) return true;
  if (a.mimeType.startsWith('text/')) return true;
  const ext = a.name.lastIndexOf('.');
  return ext >= 0 && TEXT_EXTENSIONS.has(a.name.slice(ext).toLowerCase());
}

/** Convert image Attachment to ChatAttachment for backend */
export function toChatAttachment(a: Attachment): ChatAttachment {
  const result: ChatAttachment = { name: a.name, mimeType: a.mimeType };
  if (a.filePath) {
    result.filePath = a.filePath;
  } else {
    result.data = a.data;
  }
  return result;
}

/** Decode base64 text attachment content using UTF-8 (truncated to maxBytes) */
export function decodeTextAttachment(a: Attachment, maxBytes = 100 * 1024): string {
  try {
    const binaryStr = atob(a.data);
    const bytes = new Uint8Array(binaryStr.length);
    for (let i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i);
    const truncated = bytes.length > maxBytes ? bytes.slice(0, maxBytes) : bytes;
    return new TextDecoder('utf-8', { fatal: false }).decode(truncated);
  } catch {
    return '[Failed to decode file content]';
  }
}

/** Escape special characters for safe use in XML/HTML attributes */
function escapeXmlAttr(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Build message text with text-file attachments prepended (inline decoded content for base64-only files) */
export function buildMessageWithTextAttachments(message: string, textAttachments: Attachment[]): string {
  if (textAttachments.length === 0) return message;
  // All textAttachments here lack filePath — inline their decoded content
  const parts = textAttachments.map((a) => {
    const content = decodeTextAttachment(a);
    return `<file name="${escapeXmlAttr(a.name)}">\n${content}\n</file>`;
  });
  return parts.join('\n\n') + '\n\n' + message;
}

/** Build a folder reference string to embed in message text. */
export function buildFolderRef(name: string, path: string): string {
  return `${FILE_REF_CHAR}[${FOLDER_PREFIX}${name}](${path})${FILE_REF_CHAR}`;
}

/** Parse folder references from message text. Returns array of { name, path }. */
export function parseFolderRefs(text: string): { name: string; path: string }[] {
  const re = createRefRegex();
  const refs: { name: string; path: string }[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m[1].startsWith(FOLDER_PREFIX)) {
      refs.push({ name: m[1].slice(FOLDER_PREFIX.length), path: m[2] });
    }
  }
  return refs;
}

/** A parsed reference (file or folder) from message text. */
export interface RefItem {
  name: string;
  path: string;
  type: 'file' | 'folder';
}

/** Parse all references (files and folders) from message text. */
export function parseAllRefs(text: string): RefItem[] {
  const re = createRefRegex();
  const refs: RefItem[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m[1].startsWith(FOLDER_PREFIX)) {
      refs.push({ name: m[1].slice(FOLDER_PREFIX.length), path: m[2], type: 'folder' });
    } else {
      refs.push({ name: m[1], path: m[2], type: 'file' });
    }
  }
  return refs;
}

/** A segment of message text split by file/folder references. */
export type ContentSegment =
  | { type: 'text'; text: string }
  | { type: 'fileRef'; name: string; path: string }
  | { type: 'folderRef'; name: string; path: string };

/** Split message text into segments of plain text and file/folder references. */
export function splitByFileRefs(text: string): ContentSegment[] {
  const re = createRefRegex();
  const segments: ContentSegment[] = [];
  let lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m.index > lastIndex) {
      segments.push({ type: 'text', text: text.slice(lastIndex, m.index) });
    }
    const rawName = m[1];
    const path = m[2];
    if (rawName.startsWith(FOLDER_PREFIX)) {
      segments.push({ type: 'folderRef', name: rawName.slice(FOLDER_PREFIX.length), path });
    } else {
      segments.push({ type: 'fileRef', name: rawName, path });
    }
    lastIndex = m.index + m[0].length;
  }
  if (lastIndex < text.length) {
    segments.push({ type: 'text', text: text.slice(lastIndex) });
  }
  return segments.length > 0 ? segments : [{ type: 'text', text }];
}
