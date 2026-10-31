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
  mode: 'direct' as 'direct' | 'refine' | 'director',
  playback: {
    activeKey: null as string | null,
    state: 'idle',
    error: null as string | null,
    notice: null as string | null,
    progress: undefined as
      | { index: number; total: number; played: number; spokenText: string; words: unknown[]; wordIndex: number }
      | undefined,
  },
}));

vi.mock('@/services/stores/chat-store', () => ({
  useChatStore: (selector: (state: unknown) => unknown) => selector({
    sessionId: 'session-1',
    transcriptMode: false,
  }),
}));
vi.mock('@/services/stores/settings-store', () => ({
  useSettingsStore: (selector: (state: unknown) => unknown) => selector({
    readAloudMode: mocks.mode,
    setReadAloudMode: vi.fn(),
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

/** The read-aloud mode picker, which belongs to the conversation rather than to this message. */
function modeSelect(markup: string): string {
  return markup.match(/<select[^>]*data-meta-tts-mode[^>]*>/)?.[0] ?? '';
}

/** The svg inside the speaker button; the bar's other icons carry circles of their own. */
function speakerIcon(markup: string): string {
  return markup.match(/<button[^>]*data-meta-tts[^>]*>([\s\S]*?)<\/button>/)?.[1] ?? '';
}

describe('MessageMetaBar read-aloud availability', () => {
  beforeEach(() => {
    mocks.availability.status = 'ready';
    mocks.availability.ready = false;
    mocks.availability.reason = 'NOT_CONFIGURED';
    mocks.mode = 'direct';
    mocks.playback.activeKey = null;
    mocks.playback.state = 'idle';
    mocks.playback.error = null;
    mocks.playback.notice = null;
    mocks.playback.progress = undefined;
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

  it('shows the plain speaker when this message is not being read', () => {
    mocks.availability.ready = true;
    mocks.availability.reason = undefined;

    const markup = renderBar();

    expect(speakerIcon(markup)).toContain('lucide-volume-2');
    // Outline only: a filled speaker means audio is actually playing.
    expect(speakerIcon(markup)).not.toContain('fill-current');
  });

  it('swaps the wave for trailing dots while the clip has not arrived yet', () => {
    mocks.availability.ready = true;
    mocks.availability.reason = undefined;
    mocks.playback.activeKey = 'message-1';
    mocks.playback.state = 'loading';

    const markup = renderBar();
    const button = speakerButton(markup);
    const icon = speakerIcon(markup);

    // The pending glyph is a bespoke speaker whose dots are circles; lucide's volume icons hold
    // paths only, so this cannot pass by accident.
    expect(icon).toContain('<circle');
    expect(icon).not.toContain('lucide-volume-2');
    expect(icon).toContain('animate-pulse');
    expect(button).toContain('title="Stop reading"');
    // The control stays clickable: the same button cancels the synthesis.
    expect(button).not.toContain('disabled=');
  });

  it('fills the speaker once the clip is audible', () => {
    mocks.availability.ready = true;
    mocks.availability.reason = undefined;
    mocks.playback.activeKey = 'message-1';
    mocks.playback.state = 'playing';

    const markup = renderBar();
    const button = speakerButton(markup);
    const icon = speakerIcon(markup);

    expect(icon).toContain('lucide-volume-2');
    expect(icon).toContain('fill-current');
    expect(icon).not.toContain('<circle');
    expect(button).toContain('title="Stop reading"');
    expect(button).not.toContain('disabled=');
  });

  it('keeps the pending speaker for a clip parked behind the autoplay policy', () => {
    mocks.availability.ready = true;
    mocks.availability.reason = undefined;
    mocks.playback.activeKey = 'message-1';
    mocks.playback.state = 'ready';

    const markup = renderBar();
    const button = speakerButton(markup);
    const icon = speakerIcon(markup);

    expect(icon).toContain('<circle');
    // Nothing is loading any more, so the dots stop blinking.
    expect(icon).not.toContain('animate-pulse');
    expect(button).toContain('title="Tap to play"');
  });
});

describe('MessageMetaBar directed read-aloud', () => {
  beforeEach(() => {
    mocks.availability.status = 'ready';
    mocks.availability.ready = true;
    mocks.availability.reason = undefined;
    mocks.mode = 'direct';
    mocks.playback.activeKey = null;
    mocks.playback.state = 'idle';
    mocks.playback.error = null;
    mocks.playback.notice = null;
    mocks.playback.progress = undefined;
  });

  it('offers the director mode beside the two single-clip modes', () => {
    const markup = renderBar();

    expect(markup).toContain('value="direct"');
    expect(markup).toContain('value="refine"');
    expect(markup).toContain('value="director"');
    expect(markup).toContain('Directed read-aloud');
  });

  it('shows which segment of the plan is playing', () => {
    mocks.mode = 'director';
    mocks.playback.activeKey = 'message-1';
    mocks.playback.state = 'playing';
    mocks.playback.progress = { index: 2, total: 5, played: 1, spokenText: '第二段。', words: [], wordIndex: -1 };

    const markup = renderBar();

    expect(markup).toContain('data-meta-tts-progress');
    expect(markup).toContain('Segment 2/5');
  });

  it('shows no segment counter for a single clip', () => {
    mocks.playback.activeKey = 'message-1';
    mocks.playback.state = 'playing';

    expect(renderBar()).not.toContain('data-meta-tts-progress');
  });

  it('counts segments a plan has not yet declared a length for', () => {
    mocks.mode = 'director';
    mocks.playback.activeKey = 'message-1';
    mocks.playback.state = 'playing';
    // An incrementally planned script sends `-1` as its count; hiding the counter until the plan
    // ends would leave the whole read without the one thing that proves it is still going.
    mocks.playback.progress = { index: 3, total: 0, played: 2, spokenText: '第三段。', words: [], wordIndex: -1 };

    const markup = renderBar();

    expect(markup).toContain('data-meta-tts-progress');
    expect(markup).toContain('Segment 3');
    expect(markup).not.toContain('Segment 3/0');
  });

  it('passes on a plain read that replaced a plan nobody could build', () => {
    mocks.mode = 'director';
    mocks.playback.activeKey = 'message-1';
    mocks.playback.notice = 'Read plainly: no direction was available for this reply';

    const markup = renderBar();

    // Not an error: the reply was read, only not as directed. Saying nothing would let a flat voice
    // read as the director's choice.
    expect(markup).toContain('Read plainly: no direction was available for this reply');
    expect(markup).not.toContain('Read aloud failed');
  });

  it('keeps the mode picker usable on a reply that is too long to read', () => {
    mocks.availability.ready = true;
    mocks.availability.reason = undefined;
    const long: AssistantMessage = { ...message, content: 'a'.repeat(20_001) };

    const markup = renderToStaticMarkup(<MessageMetaBar messages={[long]} />);

    // The mode is one saved preference for the conversation; a single over-long reply must not be
    // able to disable the control that changes it.
    expect(speakerButton(markup)).toContain('disabled=""');
    expect(modeSelect(markup)).not.toBe('');
    // `disabled:opacity-40` is a class, not the attribute — hence the exact match.
    expect(modeSelect(markup)).not.toContain('disabled=""');
  });

  it('offers the script panel only in director mode', () => {
    // Under the other two modes there is no plan to show, so a panel button would promise an edit
    // they would silently ignore.
    expect(renderBar()).not.toContain('data-meta-tts-script');

    mocks.mode = 'director';
    expect(renderBar()).toContain('data-meta-tts-script');
  });
});
