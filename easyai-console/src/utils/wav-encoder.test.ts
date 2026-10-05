import { describe, expect, it } from 'vitest';
import { encodeWav } from './wav-encoder';

async function header(samples: Float32Array): Promise<DataView> {
  const blob = await encodeWav(samples).arrayBuffer();
  return new DataView(blob);
}

describe('encodeWav', () => {
  it('writes a canonical 44-byte RIFF/WAVE PCM16 header', async () => {
    const view = await header(new Float32Array([0.5, -0.5]));
    const ascii = (offset: number, length: number) =>
      String.fromCharCode(...new Uint8Array(view.buffer, offset, length));
    expect(ascii(0, 4)).toBe('RIFF');
    expect(view.getUint32(4, true)).toBe(40); // 36 + 2 samples * 2 bytes
    expect(ascii(8, 4)).toBe('WAVE');
    expect(ascii(12, 4)).toBe('fmt ');
    expect(view.getUint32(16, true)).toBe(16);
    expect(view.getUint16(20, true)).toBe(1); // PCM
    expect(view.getUint16(22, true)).toBe(1); // mono
    expect(view.getUint32(24, true)).toBe(16000);
    expect(view.getUint32(28, true)).toBe(32000);
    expect(view.getUint16(32, true)).toBe(2);
    expect(view.getUint16(34, true)).toBe(16);
    expect(ascii(36, 4)).toBe('data');
    expect(view.getUint32(40, true)).toBe(4);
  });

  it('encodes samples as little-endian int16 starting at byte 44', async () => {
    const view = await header(new Float32Array([1, -1]));
    expect(view.getInt16(44, true)).toBe(32767);
    expect(view.getInt16(46, true)).toBe(-32768);
  });
});
