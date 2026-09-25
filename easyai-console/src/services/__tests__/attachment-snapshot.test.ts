import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import type { MessageSnapshot } from '../session-service';
import { convertSnapshot } from '../stores/chat/message-converter';
import { toChatAttachment, buildFileRef, buildFolderRef } from '@/utils/attachment-utils';
import { getAttachmentImageSource } from '@/hooks/useAttachmentImage';
import { UserMessage } from '@/components/chat/UserMessage';

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
    expect(convertSnapshot(input)).toMatchObject({ content: input.content[0].text });
  });

  it('restores image and folder positions across text blocks without changing the snapshot', () => {
    const input = snapshot({ [ref]: signedUrl });
    const prefix = '先看这张图：';
    const middle = '，再检查目录：';
    input.content = [
      { type: 'text', text: prefix },
      { type: 'folderRef', filePath: '/project/src', name: 'src', displayOffset: prefix.length + middle.length },
      { type: 'fileRef', filePath: ref, name: 'image.png', mimeType: 'image/png', displayOffset: prefix.length },
      { type: 'text', text: middle + '。' },
    ];
    const before = structuredClone(input);
    expect(convertSnapshot(input)).toMatchObject({
      content: prefix + buildFileRef('image.png', ref) + middle + buildFolderRef('src', '/project/src') + '。',
    });
    expect(input).toEqual(before);
  });

  it('preserves original block order for mixed references at the same offset', () => {
    const input = snapshot();
    input.content = [
      { type: 'text', text: 'Compare: ' },
      { type: 'fileRef', filePath: ref, name: 'image.png', mimeType: 'image/png', displayOffset: 9 },
      { type: 'folderRef', filePath: '/project/src', name: 'src', displayOffset: 9 },
      { type: 'fileRef', filePath: '/images/local.webp', name: 'local.webp', mimeType: 'image/webp', displayOffset: 9 },
      { type: 'folderRef', filePath: '/project/test', name: 'test', displayOffset: 9 },
    ];
    expect(convertSnapshot(input)).toMatchObject({
      content: 'Compare: ' + buildFileRef('image.png', ref) + buildFolderRef('src', '/project/src')
        + buildFileRef('local.webp', '/images/local.webp') + buildFolderRef('test', '/project/test'),
    });
  });

  it('preserves each occurrence of the same image in different sentences', () => {
    const input = snapshot();
    input.content = [
      { type: 'text', text: 'First: . Again: .' },
      { type: 'fileRef', filePath: ref, name: 'image.png', mimeType: 'image/png', displayOffset: 7 },
      { type: 'fileRef', filePath: ref, name: 'image.png', mimeType: 'image/png', displayOffset: 16 },
    ];
    const encoded = buildFileRef('image.png', ref);
    expect(convertSnapshot(input)).toMatchObject({ content: `First: ${encoded}. Again: ${encoded}.` });
  });

  it.each([
    ['', 0, '', ''],
    ['text', -10, '', 'text'],
    ['text', 100, 'text', ''],
  ])('restores boundary image positions for %j at %i', (text, displayOffset, before, after) => {
    const input = snapshot();
    input.content = [
      { type: 'text', text },
      { type: 'fileRef', filePath: ref, name: 'image.png', mimeType: 'image/png', displayOffset },
    ];
    expect(convertSnapshot(input)).toMatchObject({ content: before + buildFileRef('image.png', ref) + after });
  });

  it('leaves non-image file blocks unchanged', () => {
    const input = snapshot();
    input.content = [
      { type: 'text', text: 'Read this file' },
      { type: 'fileRef', filePath: '/project/readme.txt', name: 'readme.txt', mimeType: 'text/plain', displayOffset: 5 },
    ];
    expect(convertSnapshot(input)).toMatchObject({ content: 'Read this file' });
  });
});

describe('inline image chip rendering', () => {
  it('renders an image chip at its reference without a duplicate attachment thumbnail', () => {
    const message = convertSnapshot(snapshot());
    if (message.role !== 'user-with-attachments') throw new Error('Expected attachments');
    const html = renderToStaticMarkup(createElement(UserMessage, { message }));
    expect(html).toContain('aria-label="Preview image image.png"');
    expect(html).toContain(`data-path="${ref}" data-name="image.png" data-type="file"`);
    expect(html).not.toContain('w-20 h-20');
    expect(html.indexOf('aria-label="Preview image image.png"')).toBeLessThan(html.lastIndexOf('Describe this image'));
  });

  it('retains thumbnails for attachments without an inline reference', () => {
    const html = renderToStaticMarkup(createElement(UserMessage, { message: {
      role: 'user-with-attachments', content: 'Legacy image', timestamp: 123,
      attachments: [{ id: 'legacy', name: 'legacy.png', mimeType: 'image/png', data: 'aGVsbG8=', size: 5 }],
    } }));
    expect(html).toContain('w-20 h-20');
    expect(html).not.toContain('class="mention-chip');
  });

  it('preserves file and folder chips when no image attachment matches', () => {
    const html = renderToStaticMarkup(createElement(UserMessage, { message: {
      role: 'user', content: buildFileRef('readme.txt', '/project/readme.txt') + buildFolderRef('src', '/project/src'), timestamp: 123,
    } }));
    expect(html).toContain('mention-chip mention-file');
    expect(html).toContain('mention-chip mention-folder');
    expect(html).not.toContain('Preview image');
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
