/**
 * Microphone render blocks, forwarded to the main thread one at a time.
 *
 * Two modes, chosen by the main thread:
 *  - default (no `flow-control` message): every block is posted. This is what the HTTP dictation
 *    channel uses, and it keeps that path byte-for-byte identical to its previous behaviour.
 *  - flow control (streaming channel): the main thread grants a credit window of about two seconds
 *    of audio and returns one credit per block it has actually handed to the socket. When the window
 *    runs out the worklet stops producing and reports a single `overflow`, so a stalled main thread
 *    becomes a reported failure instead of an unbounded backlog of un-sent speech.
 *
 * Credits are counted per render block rather than per 100 ms frame because only the worklet sees
 * blocks; the window is derived from `sampleRate`, so the tolerance stays the same at 16/44.1/48 kHz.
 */
class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    /** null = unlimited (HTTP channel); number = remaining blocks this worklet may post. */
    this.credits = null;
    this.creditWindow = null;
    this.blockFrames = 0;
    this.overflowReported = false;
    this.port.onmessage = (event) => {
      const message = event.data;
      if (!message || typeof message !== 'object') return;
      if (message.type === 'flow-control') {
        if (this.credits !== null) return;
        this.credits = this.computeWindow();
        this.creditWindow = this.credits;
        return;
      }
      if (message.type === 'credits') {
        if (this.credits === null) return;
        const count = typeof message.count === 'number' ? message.count : 0;
        this.credits += count;
        if (this.credits > 0) this.overflowReported = false;
      }
    };
  }

  /** About two seconds of render blocks — the same budget the socket's byte limit enforces. */
  computeWindow() {
    // The render quantum is 128 frames everywhere AudioWorklet is implemented, but the block length
    // actually observed wins so the window keeps meaning "two seconds" on any host.
    const blockFrames = this.blockFrames || 128;
    return Math.max(8, Math.round((sampleRate / blockFrames) * 2));
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || input.length === 0 || input[0].length === 0) return true;
    this.blockFrames = input[0].length;
    if (this.credits !== null && this.credits <= 0) {
      if (!this.overflowReported) {
        this.overflowReported = true;
        this.port.postMessage({ type: 'overflow' });
      }
      return true;
    }
    if (this.credits !== null) this.credits -= 1;
    this.port.postMessage(input[0].slice(0));
    return true;
  }
}

registerProcessor('capture-processor', CaptureProcessor);
