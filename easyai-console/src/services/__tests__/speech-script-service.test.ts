import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authFetch } from '@/services/api-client';
import {
  detailErrorText,
  isReused,
  listErrorText,
  ScriptLibraryError,
  speechScriptService,
  spokenCharsOf,
} from '../speech-script-service';

vi.mock('@/services/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/api-client')>()),
  authFetch: vi.fn(),
}));

beforeEach(() => vi.resetAllMocks());

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

/** The URL the service actually called, relative to the API base. */
function calledUrl(): string {
  return vi.mocked(authFetch).mock.calls[0][0] as string;
}

describe('speechScriptService.list', () => {
  it('asks for the bare endpoint when nothing is filtered', async () => {
    vi.mocked(authFetch).mockResolvedValue(json([]));

    await speechScriptService.list();

    expect(calledUrl()).toBe('/api/speech-scripts');
  });

  it('maps every filter onto its query parameter', async () => {
    vi.mocked(authFetch).mockResolvedValue(json([]));

    await speechScriptService.list({
      type: 'session_message',
      sessionId: 's-1',
      status: 'PINNED',
      mode: 'director',
      favorite: true,
      q: '西湖 游记',
      sort: 'PLAY_COUNT',
      limit: 20,
      offset: 40,
    });

    const url = new URL(calledUrl(), 'http://localhost');
    expect(Object.fromEntries(url.searchParams)).toEqual({
      type: 'session_message',
      sessionId: 's-1',
      status: 'PINNED',
      mode: 'director',
      favorite: 'true',
      q: '西湖 游记',
      sort: 'PLAY_COUNT',
      limit: '20',
      offset: '40',
    });
  });

  it('omits a filter that is false rather than sending "favorite=false"', async () => {
    // The server reads `favorite` as "favourites only"; a false would have to mean something else.
    vi.mocked(authFetch).mockResolvedValue(json([]));

    await speechScriptService.list({ favorite: false, q: '' });

    expect(calledUrl()).toBe('/api/speech-scripts');
  });

  it('reports the HTTP status so the page can tell "no database" from "empty"', async () => {
    vi.mocked(authFetch).mockResolvedValue(
      json({ detail: 'The read-aloud script library needs the database' }, 503)
    );

    const failure = await speechScriptService.list().catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ScriptLibraryError);
    expect((failure as ScriptLibraryError).status).toBe(503);
    expect((failure as ScriptLibraryError).message).toBe('The read-aloud script library needs the database');
  });
});

describe('speechScriptService.detail', () => {
  it('escapes the id into the path', async () => {
    vi.mocked(authFetch).mockResolvedValue(json({ id: 'a/b' }));

    await speechScriptService.detail('a/b');

    expect(calledUrl()).toBe('/api/speech-scripts/a%2Fb');
  });

  it('surfaces a 404 as a status, not a message to be parsed', async () => {
    vi.mocked(authFetch).mockResolvedValue(json({ detail: 'This read-aloud script does not exist' }, 404));

    const failure = await speechScriptService.detail('gone').catch((error: unknown) => error);

    expect((failure as ScriptLibraryError).status).toBe(404);
  });
});

describe('speechScriptService.pin', () => {
  it('flattens the target so the server can file the plan under its reply', async () => {
    vi.mocked(authFetch).mockResolvedValue(json({ id: 'p1' }));

    await speechScriptService.pin({
      scriptId: 's1',
      title: '西湖一日',
      target: { type: 'session_message', id: 'm1', sessionId: 's1' },
    });

    const [, options] = vi.mocked(authFetch).mock.calls[0];
    expect(options?.method).toBe('POST');
    expect(JSON.parse(options?.body as string)).toEqual({
      scriptId: 's1',
      title: '西湖一日',
      targetType: 'session_message',
      targetId: 'm1',
      sessionId: 's1',
    });
  });

  it('sends nulls for what it does not have, so the server sees "manual text"', async () => {
    vi.mocked(authFetch).mockResolvedValue(json({ id: 'p1' }));

    await speechScriptService.pin({ scriptId: 's1' });

    const [, options] = vi.mocked(authFetch).mock.calls[0];
    expect(JSON.parse(options?.body as string)).toEqual({ scriptId: 's1', title: null });
  });

  it('turns a 409 into a readable explanation', async () => {
    // The in-memory plan expired between opening the panel and pressing "Keep".
    vi.mocked(authFetch).mockResolvedValue(
      json({ detail: 'This script can no longer be saved; reopen the read-aloud script panel' }, 409)
    );

    const failure = await speechScriptService.pin({ scriptId: 's1' }).catch((error: unknown) => error);

    expect((failure as ScriptLibraryError).status).toBe(409);
    expect((failure as ScriptLibraryError).message).toContain('reopen the read-aloud script panel');
  });
});

describe('speechScriptService.patch', () => {
  it('sends only the field that changed as a value; the untouched one is null', async () => {
    vi.mocked(authFetch).mockResolvedValue(json({ id: 'p1' }));

    await speechScriptService.patch('p1', { favorite: true });

    const [url, options] = vi.mocked(authFetch).mock.calls[0];
    expect(url).toBe('/api/speech-scripts/p1');
    expect(options?.method).toBe('PATCH');
    expect(JSON.parse(options?.body as string)).toEqual({ title: null, favorite: true });
  });
});

describe('speechScriptService.remove', () => {
  it('deletes by id and resolves with nothing', async () => {
    vi.mocked(authFetch).mockResolvedValue(new Response(null, { status: 200 }));

    await expect(speechScriptService.remove('p1')).resolves.toBeUndefined();
    expect(calledUrl()).toBe('/api/speech-scripts/p1');
    expect(vi.mocked(authFetch).mock.calls[0][1]?.method).toBe('DELETE');
  });

  it('throws with the status when the row is already gone', async () => {
    vi.mocked(authFetch).mockResolvedValue(json({ detail: 'nope' }, 404));

    const failure = await speechScriptService.remove('p1').catch((error: unknown) => error);

    expect((failure as ScriptLibraryError).status).toBe(404);
  });
});

describe('isReused', () => {
  it('is true only when the server said so', () => {
    expect(isReused(null)).toBe(false);
    expect(isReused({ reused: undefined } as never)).toBe(false);
    expect(isReused({ reused: true } as never)).toBe(true);
  });
});

describe('spokenCharsOf', () => {
  it('counts what the voice says, not what the reply said', () => {
    expect(
      spokenCharsOf([
        { id: 'a', index: 0, sourceText: 'Hello world.', spokenText: 'Hi.' },
        { id: 'b', index: 1, sourceText: 'A long paragraph.', spokenText: 'Short.' },
      ] as never)
    ).toBe(9);
  });

  it('is zero for a plan with no segments', () => {
    expect(spokenCharsOf([])).toBe(0);
  });
});

/**
 * An empty library has three causes that look identical on screen. The status is the only thing
 * that tells them apart, so each one has to keep its own sentence.
 */
describe('listErrorText', () => {
  it.each([
    [503, 'The read-aloud library needs the database (easyai.r2dbc.enabled)'],
    [401, 'Sign in again to use read-aloud'],
  ])('maps status %i to its own explanation', (status, expected) => {
    expect(listErrorText(new ScriptLibraryError('refused', status))).toBe(expected);
  });

  it('falls back for a transport failure that never became an HTTP status', () => {
    expect(listErrorText(new TypeError('network down'))).toBe('Could not load the read-aloud library');
  });
});

describe('detailErrorText', () => {
  it.each([
    [404, 'This read-aloud script no longer exists'],
    [503, 'The read-aloud library needs the database'],
    [401, 'Sign in again to use read-aloud'],
  ])('maps status %i to its own explanation', (status, expected) => {
    expect(detailErrorText(new ScriptLibraryError('refused', status))).toBe(expected);
  });

  it('falls back for anything else', () => {
    expect(detailErrorText(new Error('boom'))).toBe('Could not load this read-aloud script');
  });
});
