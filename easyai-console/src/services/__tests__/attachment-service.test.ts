import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Attachment } from '@/types/message';
import { buildFileRef, buildMessageWithTextAttachments, isTextAttachment, toChatAttachment } from '@/utils/attachment-utils';
import { authFetch } from '../api-client';
import { uploadAttachments, uploadBase64Attachment, uploadFile } from '../attachment-service';

vi.mock('../api-client', () => ({ authFetch: vi.fn() }));

const ref = 'storage://chat-images/user/session/image.png';
const result = { filePath: ref, name: 'saved.png', mimeType: 'image/png', url: 'https://objects.example/image?signature=temporary' };
const draft = (id: string): Attachment => ({ id, name: `${id}.png`, mimeType: 'image/png', data: 'aGVsbG8=', size: 5 });
const success = (body: object = result) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });

beforeEach(() => vi.resetAllMocks());

describe('attachment uploads', () => {
  it('uploads multipart to the internal endpoint and preserves the complete response', async () => {
    vi.mocked(authFetch).mockResolvedValue(success());
    const file = new File(['hello'], 'paste.png', { type: 'image/png' });
    expect(await uploadFile(file, 'session /?&=')).toEqual(result);
    const [url, options] = vi.mocked(authFetch).mock.calls[0];
    expect(url).toBe('/api/files/upload?sessionId=session%20%2F%3F%26%3D');
    expect(options?.method).toBe('POST');
    expect(options?.headers).toBeUndefined();
    const form = options?.body as FormData;
    expect(form.has('sessionId')).toBe(false);
    expect((form.get('file') as File).name).toBe('paste.png');
  });

  it('decodes clipboard bytes before upload', async () => {
    vi.mocked(authFetch).mockResolvedValue(success());
    expect(await uploadBase64Attachment(draft('one'), 'session')).toEqual(result);
    const form = vi.mocked(authFetch).mock.calls[0][1]?.body as FormData;
    expect(await (form.get('file') as File).text()).toBe('hello');
  });

  it('requires a session ID before any upload', async () => {
    await expect(uploadFile(new File(['hello'], 'one.png'), '')).rejects.toThrow('Create a session');
    expect(authFetch).not.toHaveBeenCalled();
  });

  it('supports local paths and optional internal display URLs', async () => {
    const local = { ...result, filePath: '/images/session/one.png', url: '/api/files/serve?path=%2Fimages%2Fsession%2Fone.png' };
    vi.mocked(authFetch).mockResolvedValueOnce(success(local)).mockResolvedValueOnce(success({ filePath: '/images/two.png', name: 'two.png', mimeType: 'image/png' }));
    expect(await uploadBase64Attachment(draft('one'), 'session')).toEqual(local);
    expect(await uploadBase64Attachment(draft('two'), 'session')).not.toHaveProperty('url');
  });

  it.each([{}, { ...result, filePath: '' }, { ...result, url: 42 }])('rejects an invalid upload response: %j', async (body) => {
    vi.mocked(authFetch).mockResolvedValue(success(body));
    await expect(uploadBase64Attachment(draft('one'), 'session')).rejects.toThrow('Invalid attachment upload response');
  });

  it('retains successful progress and failed drafts, then retries only unfinished uploads', async () => {
    const original = [draft('one'), draft('two'), draft('three')];
    let saved = [...original];
    const save = (uploaded: Attachment) => { saved = saved.map((a) => a.id === uploaded.id ? uploaded : a); };
    vi.mocked(authFetch).mockResolvedValueOnce(success()).mockResolvedValueOnce(new Response('unavailable', { status: 503 }));
    await expect(uploadAttachments(saved, 'session', save)).rejects.toThrow('Failed to upload two.png');
    expect(authFetch).toHaveBeenCalledTimes(2);
    expect(saved[0]).toEqual({ ...original[0], ...result, data: '' });
    expect(saved.slice(1)).toEqual(original.slice(1));
    expect(original[0].data).toBe('aGVsbG8=');

    vi.mocked(authFetch).mockClear().mockResolvedValueOnce(success({ ...result, filePath: `${ref}-two` })).mockResolvedValueOnce(success({ ...result, filePath: `${ref}-three` }));
    const ready = await uploadAttachments(saved, 'session', save);
    expect(authFetch).toHaveBeenCalledTimes(2);
    expect(ready).toEqual(saved);
    expect(ready.every((a) => a.data === '' && a.filePath)).toBe(true);
    expect(ready.map(toChatAttachment)).toEqual(ready.map((a) => ({ filePath: a.filePath, name: a.name, mimeType: a.mimeType })));
  });

  it('validates the entire draft before uploads or subsequent edit side effects', async () => {
    const invalid = { ...draft('unsupported'), name: 'archive.zip', mimeType: 'application/zip' };
    const save = vi.fn();
    const edit = vi.fn();
    await expect(uploadAttachments([draft('one'), invalid], 'session', save).then(edit)).rejects.toThrow('Unsupported file type');
    expect(authFetch).not.toHaveBeenCalled();
    expect(save).not.toHaveBeenCalled();
    expect(edit).not.toHaveBeenCalled();
  });

  it('never proceeds to edit/send after an upload fails', async () => {
    vi.mocked(authFetch).mockRejectedValue(new Error('offline'));
    const edit = vi.fn();
    await expect(uploadAttachments([draft('one')], 'session', vi.fn()).then(edit)).rejects.toThrow('Draft kept; please retry');
    expect(edit).not.toHaveBeenCalled();
  });

  it('keeps stable references and strips stale data without re-uploading', async () => {
    const attachment = { ...draft('one'), ...result };
    const save = vi.fn();
    const [ready] = await uploadAttachments([attachment], 'session', save);
    expect(ready).toEqual({ ...attachment, data: '' });
    expect(save).toHaveBeenCalledWith(ready);
    expect(authFetch).not.toHaveBeenCalled();
    expect(toChatAttachment(attachment)).toEqual({ filePath: ref, name: result.name, mimeType: result.mimeType });
    expect(() => toChatAttachment(draft('one'))).toThrow('must be uploaded');
  });

  it('keeps text drafts inline without uploading or changing the original message', async () => {
    const text = { ...draft('text'), name: 'note.txt', mimeType: 'text/plain' };
    const save = vi.fn();
    const ready = await uploadAttachments([text], 'session', save);
    expect(ready[0]).toBe(text);
    expect(ready[0].data).toBe('aGVsbG8=');
    expect(authFetch).not.toHaveBeenCalled();
    expect(save).not.toHaveBeenCalled();
    const message = `Review ${buildFileRef('note.txt', '/project/note.txt')}\nKeep this body.`;
    const textDrafts = ready.filter((a) => !a.filePath && isTextAttachment(a));
    expect(buildMessageWithTextAttachments(message, textDrafts)).toBe(`<file name="note.txt">\nhello\n</file>\n\n${message}`);
    expect(ready.filter((a) => a.filePath).map(toChatAttachment)).toEqual([]);
  });

  it('inlines an empty text file without uploading or losing the message body', async () => {
    const empty = { ...draft('empty'), name: 'empty.txt', mimeType: 'text/plain', data: '', size: 0 };
    const save = vi.fn();
    const ready = await uploadAttachments([empty], 'session', save);
    expect(ready[0]).toBe(empty);
    expect(authFetch).not.toHaveBeenCalled();
    expect(save).not.toHaveBeenCalled();
    const textDrafts = ready.filter((a) => !a.filePath && isTextAttachment(a));
    expect(buildMessageWithTextAttachments('Original body', textDrafts)).toBe('<file name="empty.txt">\n\n</file>\n\nOriginal body');
    expect(ready.filter((a) => a.filePath).map(toChatAttachment)).toEqual([]);
  });

  it('does not silently skip an image with missing or invalid draft bytes', async () => {
    for (const data of ['', 'storage://chat-images/user/session/image.png']) {
      await expect(uploadAttachments([{ ...draft('one'), data }], 'session', vi.fn())).rejects.toThrow();
    }
    expect(authFetch).not.toHaveBeenCalled();
  });
});
