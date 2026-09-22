import { describe, expect, it } from 'vitest';
import type { MessageSnapshot } from '../session-service';
import { convertSnapshot } from '../stores/chat/message-converter';
import { toChatAttachment, buildFileRef, buildFolderRef } from '@/utils/attachment-utils';
import { getAttachmentImageSource } from '@/hooks/useAttachmentImage';

const ref = 'storage://chat-images/user/session/image.png';
const signedUrl = 'https://objects.example/image.png?signature=fresh';
const snapshot = (fileUrls?: Record<string, string>): MessageSnapshot => ({
  id: 'message',
  role: 'USER',
  timestamp: 123,
  content: [
    { type: 'text', text: 'Describe this image' },
    { type: 'fileRef', filePath: ref, name: 'image.png', mimeType: 'image/png', displayOffset: 0 },
  ],
  fileUrls,
});

function attachmentsOf(message: MessageSnapshot) {
  const converted = convertSnapshot(message);
  if (converted.role !== 'user-with-attachments') throw new Error('Expected attachments');
  return converted.attachments!;
}

describe('attachment snapshot conversion', () => {
  it('maps fileUrls to presentation metadata without changing stable content', () => {
    const input = snapshot({ [ref]: signedUrl });
    const before = structuredClone(input);
    const [attachment] = attachmentsOf(input);
    expect(attachment).toMatchObject({ filePath: ref, url: signedUrl, data: '', name: 'image.png', mimeType: 'image/png' });
    expect(toChatAttachment(attachment)).toEqual({ filePath: ref, name: 'image.png', mimeType: 'image/png' });
    expect(input).toEqual(before);
  });

  it('uses fresh display URLs on subsequent conversions, not a cached signature', () => {
    const first = attachmentsOf(snapshot({ [ref]: signedUrl }))[0];
    const next = attachmentsOf(snapshot({ [ref]: `${signedUrl}-renewed` }))[0];
    expect(next.url).not.toBe(first.url);
    expect(next.id).toBe(first.id);
    expect(toChatAttachment(next)).toEqual(toChatAttachment(first));
  });

  it('preserves references without fileUrls for authenticated fallback or missing-image placeholders', () => {
    const [attachment] = attachmentsOf(snapshot());
    expect(attachment.filePath).toBe(ref);
    expect(attachment.url).toBeUndefined();
    expect(attachment.data).toBe('');
    expect(getAttachmentImageSource(attachment)).toMatchObject({ kind: 'proxy', src: `/api/files/serve?path=${encodeURIComponent(ref)}` });
  });

  it('keeps local references and legacy base64 history compatible', () => {
    const input = snapshot();
    input.content = [
      { type: 'fileRef', filePath: '/images/old.png', name: 'old.png', mimeType: 'image/png', displayOffset: 0 },
      { type: 'image', data: 'aGVsbG8=', mimeType: 'image/png' },
    ];
    const attachments = attachmentsOf(input);
    expect(attachments.find((a) => a.filePath)?.filePath).toBe('/images/old.png');
    const legacy = attachments.find((a) => !a.filePath)!;
    expect(legacy.data).toBe('aGVsbG8=');
    expect(getAttachmentImageSource(legacy)).toMatchObject({ kind: 'data', src: 'data:image/png;base64,aGVsbG8=' });
  });

  it('does not duplicate project file mentions or alter folder references', () => {
    const input = snapshot({ [ref]: signedUrl });
    input.content[0] = { type: 'text', text: `${buildFileRef('image.png', ref)} ${buildFolderRef('src', '/project/src')}` };
    const attachments = attachmentsOf(input);
    expect(attachments).toHaveLength(2);
    expect(attachments[0].url).toBe(signedUrl);
    expect(attachments[1]).toMatchObject({ filePath: '/project/src', mimeType: 'application/x-directory' });
  });
});

describe('attachment image source routing', () => {
  const attachment = { filePath: ref, mimeType: 'image/png', data: '' };

  it.each(['https://objects.example/image?signature=one', 'http://objects.example/image?signature=two'])('uses signed URLs directly, never as authenticated requests: %s', (url) => {
    expect(getAttachmentImageSource({ ...attachment, url })).toEqual({
      kind: 'remote', src: url, proxyUrl: `/api/files/serve?path=${encodeURIComponent(ref)}`,
    });
  });

  it('uses only the fixed internal endpoint for authenticated requests', () => {
    const url = `/api/files/serve?path=${encodeURIComponent(ref)}`;
    expect(getAttachmentImageSource({ ...attachment, url })).toEqual({ kind: 'proxy', src: url, proxyUrl: url });
  });

  it.each(['//external.example/api/files/serve', '/api/files/serve/../../external', '/api/files/serve-evil', 'file:///tmp/image.png', 'storage://chat-images/user/session/image.png'])('does not use untrusted display URLs as authenticated requests: %s', (url) => {
    const source = getAttachmentImageSource({ ...attachment, url });
    expect(source.kind).toBe('proxy');
    expect(source.src).toBe(`/api/files/serve?path=${encodeURIComponent(ref)}`);
  });

  it('never treats storage references or invalid bytes as base64 image data', () => {
    expect(getAttachmentImageSource({ mimeType: 'image/png', data: ref }).kind).toBe('missing');
    expect(getAttachmentImageSource({ mimeType: 'image/png', data: '', url: 'storage://invalid' }).kind).toBe('missing');
  });
});
