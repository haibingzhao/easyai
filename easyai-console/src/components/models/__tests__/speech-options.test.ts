import { describe, expect, it } from 'vitest';
import { reconcileSpeechOptions } from '../speech-options';
import type { SpeechCapabilities, VoiceSpec } from '@/types/settings';

const voice = (id: string, patch: Partial<VoiceSpec> = {}): VoiceSpec => ({
  voice: id,
  name: id,
  traits: [],
  scenarios: [],
  languages: ['zh'],
  dialects: [],
  ...patch,
});

const capabilities = (patch: Partial<SpeechCapabilities> = {}): SpeechCapabilities => ({
  supportsInstruction: true,
  supportsEmotionTags: true,
  supportsSsml: true,
  supportsLatex: false,
  supportsWordTimestamp: true,
  supportsHotFix: true,
  formats: ['mp3', 'pcm', 'wav', 'opus'],
  sampleRates: [8000, 16000, 22050, 24000, 44100, 48000],
  maxCharsPerFrame: 20000,
  maxCharsPerTask: 200000,
  docsUrl: 'https://example.com/voices',
  emotionTags: ['[excited]'],
  voiceGroups: [{ key: 'legacy', label: '历史音色', count: 2 }],
  defaultVoice: 'longanhuan_v3.1',
  voices: [voice('longanhuan_v3.1'), voice('longsisi_v3.1', { ssml: false, instruction: false })],
  ...patch,
});

const read = (raw: string): Record<string, unknown> => JSON.parse(raw) as Record<string, unknown>;

describe('reconcileSpeechOptions', () => {
  it('seeds the recommended voice on the first lookup of an empty row', () => {
    const next = reconcileSpeechOptions('', capabilities(), { seedVoice: true });
    expect(read(next)).toEqual({ voice: 'longanhuan_v3.1' });
  });

  it('never seeds when the caller did not ask for it, so "model default" survives', () => {
    expect(reconcileSpeechOptions('', capabilities())).toBe('');
    expect(reconcileSpeechOptions('{"format":"mp3"}', capabilities())).toBe('{"format":"mp3"}');
  });

  it('only seeds when no voice is set, so an existing choice is never overwritten', () => {
    const raw = '{"voice":"longanhuan_v3.1","format":"mp3"}';
    expect(reconcileSpeechOptions(raw, capabilities(), { seedVoice: true })).toBe(raw);
  });

  it('drops a voice the previous model owned, together with its instruction and SSML switch', () => {
    const raw = JSON.stringify({
      voice: 'longxiaochun_v3',
      instruction: '请用温和的语气朗读。',
      enable_ssml: true,
      size: 'keep-me',
    });
    const next = read(
      reconcileSpeechOptions(raw, capabilities(), {
        seedVoice: true,
        previousVoices: new Set(['longxiaochun_v3']),
      })
    );
    expect(next).toEqual({ size: 'keep-me', voice: 'longanhuan_v3.1' });
  });

  it('never deletes a voice no catalog knows — it may simply be newer than the seeded list', () => {
    const raw = JSON.stringify({ voice: 'my_cloned_voice', instruction: 'warm' });
    // Opening an existing row: no previous catalog, so nothing is "stale" and nothing may be lost.
    expect(reconcileSpeechOptions(raw, capabilities())).toBe(raw);
    // Switching models: the voice belonged to neither catalog, so it stays the user's call.
    expect(reconcileSpeechOptions(raw, capabilities(), { previousVoices: new Set(['other']) })).toBe(raw);
  });

  it('keeps a voice the new model actually offers', () => {
    const raw = '{"voice":"longsisi_v3.1"}';
    expect(reconcileSpeechOptions(raw, capabilities(), { seedVoice: true })).toBe(raw);
  });

  it('drops instruction and SSML for a voice that does not support them', () => {
    const raw = JSON.stringify({ voice: 'longsisi_v3.1', instruction: 'warm', enable_ssml: true });
    expect(read(reconcileSpeechOptions(raw, capabilities()))).toEqual({ voice: 'longsisi_v3.1' });
  });

  it('drops a format and a sample rate the model does not offer', () => {
    const raw = JSON.stringify({ format: 'flac', sample_rate: 12000, volume: 50 });
    expect(read(reconcileSpeechOptions(raw, capabilities()))).toEqual({ volume: 50 });
  });

  it('keeps a supported format and sample rate', () => {
    const raw = '{"format":"wav","sample_rate":24000}';
    expect(reconcileSpeechOptions(raw, capabilities())).toBe(raw);
  });

  it('drops instruction and SSML when the model itself cannot do them', () => {
    const caps = capabilities({ supportsInstruction: false, supportsSsml: false });
    const raw = JSON.stringify({ instruction: 'warm', enable_ssml: true });
    expect(read(reconcileSpeechOptions(raw, caps))).toEqual({});
  });

  it('treats an omitted capability as unknown rather than as a "no"', () => {
    const caps = capabilities();
    delete (caps as Partial<SpeechCapabilities>).supportsInstruction;
    const raw = JSON.stringify({ instruction: 'warm' });
    expect(read(reconcileSpeechOptions(raw, caps))).toEqual({ instruction: 'warm' });
  });

  it('leaves unparseable JSON untouched instead of overwriting the raw editor', () => {
    expect(reconcileSpeechOptions('{oops', capabilities(), { seedVoice: true })).toBe('{oops');
    expect(reconcileSpeechOptions('[1,2]', capabilities(), { seedVoice: true })).toBe('[1,2]');
  });

  it('preserves unknown custom keys through every repair', () => {
    const raw = JSON.stringify({ voice: 'gone', hot_fix: true, bit_rate: 48, format: 'flac' });
    expect(
      read(
        reconcileSpeechOptions(raw, capabilities(), {
          seedVoice: true,
          previousVoices: new Set(['gone']),
        })
      )
    ).toEqual({ hot_fix: true, bit_rate: 48, voice: 'longanhuan_v3.1' });
  });
});
