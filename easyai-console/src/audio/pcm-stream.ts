/**
 * Continuous 16 kHz PCM16 encoder for the streaming recognition channel.
 *
 * The HTTP path re-samples each segment independently (`downmixTo16k`), which is fine when every
 * segment is a self-contained WAV file. A stream cannot do that: each block would restart the
 * interpolation, so the output drifts by up to one sample per AudioWorklet block — minutes of
 * speech later the transcript sits at the wrong place in the audio and the service's VAD timings
 * stop matching what the user said. This encoder therefore keeps an absolute input position across
 * blocks and emits fixed 1600-sample (100 ms) frames, with a partial final frame on flush.
 *
 * Conversion is plain linear interpolation: an anti-aliasing filter would need state that is harder
 * to verify than it is worth for speech, and the service accepts already-resampled 16 kHz input.
 */
export class PcmStreamEncoder {
  private readonly frameSamples: number;
  private readonly step: number;

  /** Absolute index of the next block's first input sample. */
  private blockStart = 0;
  /** Absolute input position of the next output sample; may be fractional. */
  private outPos = 0;
  /** Final sample of the previous block, needed for the interval that spans a block boundary. */
  private tail: number | null = null;

  private readonly frame: Int16Array;
  private frameFill = 0;
  private emittedSamples = 0;

  constructor(inputRate: number, targetRate = 16000, frameSamples = 1600) {
    if (!Number.isFinite(inputRate) || inputRate <= 0) {
      throw new Error(`PcmStreamEncoder needs a positive input rate, got ${inputRate}`);
    }
    this.frameSamples = frameSamples;
    this.step = inputRate / targetRate;
    this.frame = new Int16Array(frameSamples);
  }

  /** Total samples emitted so far, including flushed partial frames. */
  get totalSamples(): number {
    return this.emittedSamples;
  }

  /** Push one AudioWorklet render block; returns every complete frame it completed. */
  push(block: Float32Array): Int16Array[] {
    const frames: Int16Array[] = [];
    const n = block.length;
    if (n === 0) return frames;
    const start = this.blockStart;
    const end = start + n;
    const sampleAt = (index: number): number => {
      if (index >= end) return block[n - 1];
      if (index >= start) return block[index - start];
      // The only index below `start` that can be read is the previous block's last sample.
      return this.tail ?? block[0];
    };

    let pos = this.outPos;
    while (pos < end) {
      const index = Math.floor(pos);
      const fraction = pos - index;
      if (fraction === 0) {
        this.writeSample(frames, sampleAt(index));
        pos += this.step;
        continue;
      }
      // A fractional position in the last interval needs the next block's first sample, which does
      // not exist yet: carry the position instead of clamping and injecting a fake value.
      if (index + 1 >= end) break;
      const a = sampleAt(index);
      const b = sampleAt(index + 1);
      this.writeSample(frames, a + (b - a) * fraction);
      pos += this.step;
    }
    this.outPos = pos;
    this.blockStart = end;
    this.tail = block[n - 1];
    return frames;
  }

  /** Flush the remaining sub-frame samples; null when the tail is empty. */
  flush(): Int16Array | null {
    if (this.frameFill === 0) return null;
    const tail = this.frame.slice(0, this.frameFill);
    this.frameFill = 0;
    this.emittedSamples += tail.length;
    return tail;
  }

  private writeSample(frames: Int16Array[], value: number): void {
    this.frame[this.frameFill++] = toPcm16(value);
    if (this.frameFill < this.frameSamples) return;
    frames.push(this.frame.slice(0, this.frameSamples));
    this.frameFill = 0;
    this.emittedSamples += this.frameSamples;
  }
}

/** Clip to [-1, 1] and scale: the service reads little-endian int16, not float. */
export function toPcm16(value: number): number {
  const clamped = value > 1 ? 1 : value < -1 ? -1 : value;
  // 0x7fff keeps positive full-scale inside the signed range (0x8000 would wrap to -32768).
  return clamped < 0 ? Math.round(clamped * 0x8000) : Math.round(clamped * 0x7fff);
}

/** Mix multi-channel input to mono by averaging, matching what the capture chain sends. */
export function downmixChannels(channels: Float32Array[]): Float32Array {
  if (channels.length === 0) return new Float32Array(0);
  if (channels.length === 1) return channels[0];
  const length = channels[0].length;
  const mono = new Float32Array(length);
  for (const channel of channels) {
    for (let i = 0; i < length; i++) mono[i] += channel[i];
  }
  for (let i = 0; i < length; i++) mono[i] /= channels.length;
  return mono;
}
