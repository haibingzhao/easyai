import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AsrModelFields } from '../AsrModelFields';
import type { AsrCapabilities } from '@/types/settings';

const STREAMING_30: AsrCapabilities = {
  channel: 'websocket',
  docsUrl: 'https://example.com/streaming',
  audio: { formats: ['pcm'], sampleRates: [16000] },
  params: {
    max_sentence_silence: { type: 'int', min: 200, max: 6000, default: 400, vendorDefault: 800 },
    language_hints: { type: 'string[]', maxItems: 4, finalOnly: true },
  },
  features: { context: 'initial' },
};

const STREAMING_31: AsrCapabilities = {
  ...STREAMING_30,
  params: {
    vad_model: {
      type: 'enum',
      options: ['near_meeting_16k', 'far_field_meeting_16k'],
      default: 'near_meeting_16k',
      vendorDefault: 'far_field_meeting_16k',
    },
    ...STREAMING_30.params,
    keep_dialect: { type: 'bool', default: false, finalOnly: true },
  },
};

const MESSAGE: AsrCapabilities = {
  channel: 'websocket',
  params: {
    intermediate_result_enabled: { type: 'bool', default: true, locked: true },
    disfluency_removal_enabled: { type: 'bool', default: false },
  },
  features: { context: 'initial', continueTask: true },
};

const FILE_ONLY: AsrCapabilities = { channel: 'none' };

const render = (capabilities: AsrCapabilities, options = '') =>
  renderToStaticMarkup(<AsrModelFields capabilities={capabilities} options={options} onChange={() => undefined} />);

const controls = (markup: string) => (markup.match(/<(input|select|textarea)/g) ?? []).length;

describe('AsrModelFields', () => {
  it('renders the silence threshold but no VAD controls for the 3.0 streaming model', () => {
    const markup = render(STREAMING_30);
    expect(markup).toContain('max_sentence_silence');
    expect(markup).not.toContain('vad_model');
    expect(markup).not.toContain('keep_dialect');
  });

  it('renders both VAD and dialect controls for 3.1 streaming', () => {
    const markup = render(STREAMING_31);
    expect(markup).toContain('vad_model');
    expect(markup).toContain('keep_dialect');
    expect(markup).toContain('near_meeting_16k');
  });

  it('shows the documented default next to a project-specific one', () => {
    expect(render(STREAMING_31)).toContain('far_field_meeting_16k');
  });

  it('shows the intermediate-result switch as fixed and keeps only editable switches', () => {
    const markup = render(MESSAGE);
    expect(markup).toContain('intermediate_result_enabled');
    // The locked parameter is a read-only line; the single checkbox left is disfluency removal.
    expect((markup.match(/type="checkbox"/g) ?? []).length).toBe(1);
    expect(markup).toContain('disfluency_removal_enabled');
  });

  it('renders no controls for a file-only model', () => {
    const markup = render(FILE_ONLY);
    expect(controls(markup)).toBe(0);
    expect(markup).not.toContain('max_sentence_silence');
  });

  it('disables the structured fields when the raw JSON is unparseable', () => {
    const markup = render(STREAMING_31, '{"vad_model":');
    expect(controls(markup)).toBe(0);
    expect(markup).not.toContain('max_sentence_silence');
  });

  it('shows the audio format read-only so the request cannot disagree with the capture chain', () => {
    const markup = render(STREAMING_31);
    expect(markup).toContain('16 kHz');
    expect(markup).not.toContain('44100');
  });
});
