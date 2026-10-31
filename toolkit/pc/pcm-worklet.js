// Turns the mixed sound into 16-bit stereo chunks for the recorder. Sends silence when nothing is
// playing, so the recording's sound never stalls.
class PcmTap extends AudioWorkletProcessor {
  constructor() {
    super();
    this.size = 960 * 2;   // 20 ms at 48 kHz, stereo
    this.buf = new Int16Array(this.size);
    this.n = 0;
  }

  process(inputs) {
    const input = inputs[0];
    const L = input && input[0];
    const R = (input && input[1]) || L;
    const frames = L ? L.length : 128;
    for (let i = 0; i < frames; i++) {
      const l = L ? Math.max(-1, Math.min(1, L[i])) : 0;
      const r = R ? Math.max(-1, Math.min(1, R[i])) : 0;
      this.buf[this.n++] = l * 32767;
      this.buf[this.n++] = r * 32767;
      if (this.n >= this.size) {
        this.port.postMessage(this.buf.buffer, [this.buf.buffer]);
        this.buf = new Int16Array(this.size);
        this.n = 0;
      }
    }
    return true;
  }
}
registerProcessor('pcm-tap', PcmTap);
