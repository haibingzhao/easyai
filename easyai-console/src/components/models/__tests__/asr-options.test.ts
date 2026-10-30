import { describe, expect, it } from 'vitest';
import { effectiveAsrValue, reconcileAsrOptions } from '../asr-options';
import type { AsrCapabilities, AsrParamSpec } from '@/types/settings';

const vad = (): AsrParamSpec => ({
  type: 'enum',
  options: ['near_meeting_16k', 'far_field_meeting_16k'],
  default: 'near_meeting_16k',
});

const capabilities = (params: Record<string, AsrParamSpec>, channel: AsrCapabilities['channel'] = 'websocket') =>
  ({ channel, params } as AsrCapabilities);

const STREAMING_30 = capabilities({
  max_sentence_silence: { type: 'int', min: 200, max: 6000, default: 400 },
});

const STREAMING_31 = capabilities({
  vad_model: vad(),
  keep_dialect: { type: 'bool', default: false },
});

const MESSAGE = capabilities({
  intermediate_result_enabled: { type: 'bool', default: true, locked: true },
  vad_model: vad(),
});

const read = (raw: string): Record<string, unknown> => JSON.parse(raw) as Record<string, unknown>;

describe('reconcileAsrOptions', () => {
  it('drops parameters the new model does not declare', () => {
    const raw = JSON.stringify({ vad_model: 'far_field_meeting_16k', keep_dialect: true });
    const next = reconcileAsrOptions(raw, STREAMING_30, { previousCapabilities: STREAMING_31 });
    expect(read(next)).toEqual({});
  });

  it('keeps parameters both models declare', () => {
    const raw = JSON.stringify({ vad_model: 'far_field_meeting_16k', max_sentence_silence: 700 });
    const next = reconcileAsrOptions(raw, STREAMING_31, { previousCapabilities: STREAMING_31 });
    expect(next).toBe(raw);
  });

  it('drops the message-only intermediate switch when switching to streaming', () => {
    const raw = JSON.stringify({ intermediate_result_enabled: false, vad_model: 'near_meeting_16k' });
    const next = reconcileAsrOptions(raw, STREAMING_31, { previousCapabilities: MESSAGE });
    expect(read(next)).toEqual({ vad_model: 'near_meeting_16k' });
  });

  it('never rewrites a row whose model has not changed', () => {
    const raw = '{"vad_model":"far_field_meeting_16k"}';
    expect(reconcileAsrOptions(raw, STREAMING_31)).toBe(raw);
  });

  it('leaves unknown keys from a newer catalog alone', () => {
    const raw = JSON.stringify({ future_field: 'x', vad_model: 'near_meeting_16k' });
    const next = reconcileAsrOptions(raw, STREAMING_30, { previousCapabilities: STREAMING_31 });
    expect(read(next)).toEqual({ future_field: 'x' });
  });

  it('does not inject defaults into an empty row', () => {
    expect(reconcileAsrOptions('', STREAMING_31, { previousCapabilities: STREAMING_30 })).toBe('');
  });

  it('leaves unparseable JSON to the raw editor', () => {
    const raw = '{"vad_model":';
    expect(reconcileAsrOptions(raw, STREAMING_30, { previousCapabilities: STREAMING_31 })).toBe(raw);
  });

  it('returns the original text when nothing was removed', () => {
    const raw = '{ "vad_model": "near_meeting_16k" }';
    expect(reconcileAsrOptions(raw, STREAMING_31, { previousCapabilities: STREAMING_31 })).toBe(raw);
  });
});

describe('effectiveAsrValue', () => {
  it('prefers the stored value over the capability default', () => {
    expect(effectiveAsrValue('{"max_sentence_silence":900}', STREAMING_30, 'max_sentence_silence')).toBe(900);
  });

  it('falls back to the capability default for an unset key', () => {
    expect(effectiveAsrValue('', STREAMING_30, 'max_sentence_silence')).toBe(400);
  });

  it('returns undefined for a key the model does not declare', () => {
    expect(effectiveAsrValue('', STREAMING_30, 'vad_model')).toBeUndefined();
  });
});
