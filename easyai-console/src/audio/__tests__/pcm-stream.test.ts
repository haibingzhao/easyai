import { describe, expect, it } from 'vitest';
import { PcmStreamEncoder, downmixChannels, toPcm16 } from '../pcm-stream';

/** A 440 Hz tone, so interpolation has something non-trivial to interpolate. */
function tone(rate: number, seconds: number): Float32Array {
  const samples = Math.round(rate * seconds);
  const out = new Float32Array(samples);
  for (let i = 0; i < samples; i++) out[i] = Math.sin((2 * Math.PI * 440 * i) / rate) * 0.5;
  return out;
}

function slice(block: Float32Array, from: number, length: number): Float32Array {
  return block.slice(from, from + length);
}

/** Feed [blocks] of [size] input samples and return the total emitted sample count. */
function run(rate: number, size: number, blocks: number): { samples: number; frames: Int16Array[]; encoder: PcmStreamEncoder } {
  const encoder = new PcmStreamEncoder(rate);
  const input = tone(rate, (size * blocks) / rate + 1);
  const frames: Int16Array[] = [];
  for (let b = 0; b < blocks; b++) frames.push(...encoder.push(slice(input, b * size, size)));
  const tail = encoder.flush();
  const samples = encoder.totalSamples;
  return { samples, frames: tail ? [...frames, tail] : frames, encoder };
}

describe('PcmStreamEncoder', () => {
  it('passes 16 kHz through one-for-one', () => {
    const { samples } = run(16000, 480, 10);
    // 10 blocks of 480 = 4800 samples = 0.3 s, no resampling involved.
    expect(samples).toBe(4800);
  });

  it('keeps the long-run sample count within one sample at 48 kHz', () => {
    const { samples } = run(48000, 480, 100);
    // 100 blocks of 10 ms = 1 s at 16 kHz = 16000 samples.
    expect(Math.abs(samples - 16000)).toBeLessThanOrEqual(1);
  });

  it('keeps the long-run sample count within one sample at 44.1 kHz with odd block sizes', () => {
    const { samples } = run(44100, 441, 100);
    expect(Math.abs(samples - 16000)).toBeLessThanOrEqual(1);
  });

  it('does not drift across many short blocks', () => {
    // 128-sample render blocks are the common AudioWorklet quantum at 48 kHz (2.7 ms each).
    // 128 * 1500 = 192 000 input samples = 4 s, which must land on exactly 64 000 output samples.
    const { samples } = run(48000, 128, 1500);
    expect(samples).toBe(64000);
  });

  it('emits full 100 ms frames and a shorter final frame', () => {
    // 5000 samples = 3 x 1600 + 200: three full frames plus the flushed remainder.
    const { frames, samples } = run(16000, 500, 10);
    expect(samples).toBe(5000);
    expect(frames.length).toBe(4);
    expect(frames.slice(0, 3).every((frame) => frame.length === 1600)).toBe(true);
    expect(frames[3].length).toBe(5000 - 3 * 1600);
  });

  it('emits no trailing frame when the input ends on a frame boundary', () => {
    const { frames, samples } = run(16000, 480, 10);
    // 4800 samples = exactly 3 frames, so flush has nothing left to send.
    expect(samples).toBe(4800);
    expect(frames.length).toBe(3);
    expect(frames.every((frame) => frame.length === 1600)).toBe(true);
  });

  it('flushes a partial frame and reports it in the sample count', () => {
    const encoder = new PcmStreamEncoder(16000);
    const frames = encoder.push(new Float32Array(1000));
    expect(frames.length).toBe(0);
    const tail = encoder.flush();
    expect(tail?.length).toBe(1000);
    expect(encoder.totalSamples).toBe(1000);
    expect(encoder.flush()).toBeNull();
  });

  it('carries a fractional position across a block boundary instead of clamping', () => {
    const encoder = new PcmStreamEncoder(44100);
    const input = tone(44100, 1);
    const frames: Int16Array[] = [];
    // 300-sample blocks: 44100/16000 = 2.75625, so boundaries rarely land on integers.
    for (let offset = 0; offset + 300 <= 44100; offset += 300) {
      frames.push(...encoder.push(slice(input, offset, 300)));
    }
    encoder.flush();
    const samples = encoder.totalSamples;
    expect(frames.length).toBeGreaterThan(0);
    expect(Math.abs(samples - 16000)).toBeLessThanOrEqual(2);
  });

  it('rejects an unusable input rate', () => {
    expect(() => new PcmStreamEncoder(0)).toThrow();
    expect(() => new PcmStreamEncoder(Number.NaN)).toThrow();
  });
});

describe('toPcm16', () => {
  it('clamps out-of-range floats instead of wrapping the sign', () => {
    expect(toPcm16(2)).toBe(32767);
    expect(toPcm16(-2)).toBe(-32768);
    expect(toPcm16(0)).toBe(0);
  });
});

describe('downmixChannels', () => {
  it('averages every channel into one', () => {
    const mono = downmixChannels([new Float32Array([1, 0]), new Float32Array([0, 1])]);
    expect(Array.from(mono)).toEqual([0.5, 0.5]);
  });

  it('returns the single channel untouched', () => {
    const channel = new Float32Array([0.25, -0.25]);
    expect(downmixChannels([channel])).toBe(channel);
  });
});
