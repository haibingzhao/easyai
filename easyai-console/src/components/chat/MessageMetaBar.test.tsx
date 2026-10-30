import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AssistantMessage } from '../../types/message';
import { MessageMetaBar } from './MessageMetaBar';

const mocks = vi.hoisted(() => ({
  availability: {
    status: 'ready',
    ready: false,
    reason: 'NOT_CONFIGURED' as string | undefined,
  },
  playback: {
    activeKey: null as string | null,
    state: 'idle',
    error: null as string | null,
  },
}));

vi.mock('@/services/stores/chat-store', () => ({
  useChatStore: (selector: (state: unknown) => unknown) => selector({
    sessionId: 'session-1',
    transcriptMode: false,
  }),
}));
vi.mock('@/hooks/useTtsAvailability', () => ({
  useTtsAvailability: () => mocks.availability,
}));
vi.mock('@/hooks/useTtsPlayback', () => ({
  useTtsPlayback: () => ({ ...mocks.playback, toggle: vi.fn(), stop: vi.fn() }),
}));
vi.mock('@/components/knowledge/SaveToKnowledgeDialog', () => ({
  SaveToKnowledgeDialog: () => null,
}));
vi.mock('../ui/ConfirmDialog', () => ({
  ConfirmDialog: () => null,
}));

const message: AssistantMessage = {
  role: 'assistant',
  messageId: 'message-1',
  content: 'A reply that can be read aloud.',
  timestamp: 1,
};

function renderBar(): string {
  return renderToStaticMarkup(<MessageMetaBar messages={[message]} />);
}

function speakerButton(markup: string): string {
  return markup.match(/<button[^>]*data-meta-tts[^>]*>/)?.[0] ?? '';
}

describe('MessageMetaBar read-aloud availability', () => {
  beforeEach(() => {
    mocks.availability.status = 'ready';
    mocks.availability.ready = false;
    mocks.availability.reason = 'NOT_CONFIGURED';
    mocks.playback.activeKey = null;
    mocks.playback.state = 'idle';
    mocks.playback.error = null;
  });

  it('keeps the speaker visible and explains missing configuration', () => {
    const markup = renderBar();
    const button = speakerButton(markup);

    expect(button).not.toBe('');
    expect(button).toContain('disabled=""');
    expect(button).toContain(
      'title="No usable speech model configured — check Settings → Task Models"'
    );
  });

  it('enables the speaker when a usable model is configured', () => {
    mocks.availability.ready = true;
    mocks.availability.reason = undefined;

    const button = speakerButton(renderBar());

    expect(button).not.toBe('');
    expect(button).not.toContain('disabled=');
    expect(button).toContain('title="Read aloud"');
  });

  it('does not render a speaker while the reply is streaming', () => {
    const markup = renderToStaticMarkup(<MessageMetaBar messages={[message]} isStreaming />);

    expect(speakerButton(markup)).toBe('');
  });

  it.each(['loading', 'playing'])('shows a red filled stop control while %s', (state) => {
    mocks.availability.ready = true;
    mocks.availability.reason = undefined;
    mocks.playback.activeKey = 'message-1';
    mocks.playback.state = state;

    const markup = renderBar();
    const button = speakerButton(markup);

    expect(button).toContain('bg-red-500');
    expect(button).toContain('text-white');
    expect(button).toContain('title="Stop reading"');
    expect(button).not.toContain('disabled=');
    expect(markup).toContain('fill-current');
  });
});
