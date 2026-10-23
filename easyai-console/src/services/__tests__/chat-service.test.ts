import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { abortAllActiveStreams, ChatService, resumeSession } from '../chat-service';

type Callbacks = ConstructorParameters<typeof ChatService>[0];

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn());
});

afterEach(() => {
  abortAllActiveStreams();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe.each(['send', 'resume'] as const)('%s chat error responses', (operation) => {
  async function start(callbacks: Callbacks): Promise<void> {
    if (operation === 'send') {
      await new ChatService(callbacks).sendMessage({
        sessionId: 'session-1',
        agentId: 'default',
        modelProviderConfigId: 'model-1',
        message: 'hi',
      });
    } else {
      resumeSession('session-1', undefined, callbacks);
    }
  }

  async function expectFailure(response: Response, errorMessage: string): Promise<void> {
    vi.mocked(fetch).mockResolvedValueOnce(response);
    const callbacks = { onEvent: vi.fn(), onDone: vi.fn(), onError: vi.fn() };

    await start(callbacks);

    await vi.waitFor(() => expect(callbacks.onError).toHaveBeenCalledTimes(1));
    expect(callbacks.onError).toHaveBeenCalledWith({
      type: 'error',
      reason: operation === 'send' ? 'send_failed' : 'resume_failed',
      errorMessage,
    });
    expect(callbacks.onEvent).not.toHaveBeenCalled();
    expect(callbacks.onDone).not.toHaveBeenCalled();
  }

  it.each([
    { status: 403, body: '{"error":"当前家庭与登录身份不匹配"}', message: '当前家庭与登录身份不匹配' },
    { status: 404, body: '{"error":"Session not found: session-1"}', message: 'Session not found: session-1' },
    { status: 409, body: '{"error":"Session is running; cannot compact"}', message: 'Session is running; cannot compact' },
    { status: 403, body: '{"detail":"Session not accessible"}', message: 'Session not accessible' },
    { status: 502, body: 'Bad gateway', message: 'Bad gateway' },
    { status: 500, body: '{invalid json', message: '{invalid json' },
    { status: 500, body: '', message: 'HTTP error! status: 500' },
  ])('reports $status with a readable message: $message', async ({ status, body, message }) => {
    await expectFailure(new Response(body, { status }), message);
  });

  it('extracts the readable field before capping an over-long error body', async () => {
    const detail = 'x'.repeat(500);
    const body = JSON.stringify({ type: 'about:blank', title: 'Bad Request', detail, trace: 'y'.repeat(200) });

    await expectFailure(new Response(body, { status: 400 }), `${detail.slice(0, 300)}…`);
  });

  it('caps a non-JSON error body', async () => {
    const html = `<html>${'z'.repeat(500)}</html>`;

    await expectFailure(new Response(html, { status: 502 }), `${html.slice(0, 300)}…`);
  });

  it('falls back to the HTTP status if reading the error body fails', async () => {
    const response = new Response(null, { status: 403 });
    vi.spyOn(response, 'text').mockRejectedValueOnce(new Error('Body unavailable'));

    await expectFailure(response, 'HTTP error! status: 403');
  });

  it('continues to parse successful SSE responses without reading them as API errors', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response(
      'event: done\ndata: {"type":"done","reason":"completed"}\n\n',
      { headers: { 'Content-Type': 'text/event-stream' } },
    ));
    const callbacks = { onEvent: vi.fn(), onDone: vi.fn(), onError: vi.fn() };

    await start(callbacks);

    await vi.waitFor(() => expect(callbacks.onDone).toHaveBeenCalledTimes(1));
    expect(callbacks.onDone).toHaveBeenCalledWith({ type: 'done', reason: 'completed' });
    expect(callbacks.onError).not.toHaveBeenCalled();
  });
});
