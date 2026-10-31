// Screen recording with ffmpeg: Windows Desktop Duplication (works with fullscreen games and can
// leave out the mouse cursor), hardware video encoding, and the app's mixed sound fed in live.
const { spawn } = require('child_process');
const net = require('net');
const fs = require('fs');
const path = require('path');
const ff = require('./ffmpeg.js');

const RATE = 48000;

function videoEncodeArgs(enc, quality, fps) {
  const q = { standard: 27, high: 22, max: 18 }[quality] || 22;
  const g = String(Math.max(30, Math.round(fps)));   // a key frame every second
  if (enc === 'h264_nvenc') return ['-c:v', enc, '-preset', 'p4', '-rc', 'vbr', '-cq', String(q), '-b:v', '0', '-maxrate', '80M', '-bufsize', '160M', '-g', g];
  if (enc === 'h264_amf') return ['-c:v', enc, '-usage', 'transcoding', '-quality', 'balanced', '-rc', 'cqp', '-qp_i', String(q), '-qp_p', String(q + 2), '-g', g];
  if (enc === 'h264_qsv') return ['-c:v', enc, '-global_quality', String(q), '-g', g];
  return ['-c:v', 'libx264', '-preset', fps > 60 ? 'superfast' : 'veryfast', '-crf', String(q), '-g', g];
}

/**
 * One running capture. [opts]: { method: 'dda'|'gdi', monitor, rect, fps, height (null = full size),
 * mouse, enc, quality, audio (bool), out (file) or segmentDir + segments }.
 */
class Capture {
  constructor(opts) {
    this.o = opts;
    this.proc = null;
    this.sock = null;
    this.server = null;
    this.pipe = null;
    this.err = '';
    this.started = false;
  }

  args(t0) {
    const o = this.o;
    const a = ['-hide_banner', '-y', '-loglevel', 'error', '-stats', '-copyts'];
    // Picture
    if (o.method === 'dda') {
      a.push('-use_wallclock_as_timestamps', '1', '-f', 'lavfi', '-i',
        'ddagrab=output_idx=' + (o.monitor || 0) + ':framerate=' + o.fps + ':draw_mouse=' + (o.mouse ? 1 : 0));
    } else {
      const r = o.rect;
      a.push('-use_wallclock_as_timestamps', '1', '-f', 'gdigrab', '-draw_mouse', o.mouse ? '1' : '0', '-framerate', String(o.fps));
      if (r) a.push('-offset_x', String(r.x), '-offset_y', String(r.y), '-video_size', r.width + 'x' + r.height);
      a.push('-i', 'desktop');
    }
    // Sound, sent by the app through a pipe
    if (o.audio) a.push('-use_wallclock_as_timestamps', '1', '-thread_queue_size', '4096', '-probesize', '32', '-analyzeduration', '0', '-f', 's16le', '-ar', String(RATE), '-ac', '2', '-i', this.pipe);

    // Both inputs are timed by the clock, then shifted by the same amount, so sound and picture line up
    const shift = 'setpts=PTS-' + t0.toFixed(6) + '/TB';
    const hwFrames = o.method === 'dda';
    const keepOnGpu = hwFrames && !o.height && !o.download && (o.enc === 'h264_nvenc' || o.enc === 'h264_amf');
    let v = shift;
    if (!keepOnGpu) {
      if (hwFrames) v += ',hwdownload,format=bgra';
      if (o.height) v += ',scale=-2:' + o.height + ':flags=bicubic';
      v += ',format=' + (o.enc === 'h264_qsv' ? 'nv12' : 'yuv420p');
    }
    const fc = ['[0:v]' + v + '[v]'];
    if (o.audio) fc.push('[1:a]asetpts=PTS-' + t0.toFixed(6) + '/TB,aresample=async=1000[a]');
    a.push('-filter_complex', fc.join(';'), '-map', '[v]');
    if (o.audio) a.push('-map', '[a]', '-c:a', 'aac', '-b:a', '192k');
    a.push(...videoEncodeArgs(o.enc, o.quality, o.fps));
    if (o.segmentDir) {
      a.push('-f', 'segment', '-segment_time', '5', '-reset_timestamps', '1', '-segment_wrap', String(o.segments || 30),
        '-segment_format', 'mpegts', path.join(o.segmentDir, 'seg%03d.ts'));
    } else {
      a.push(o.out);
    }
    return a;
  }

  /** Starts capturing; resolves once frames are being recorded, rejects if it fails. */
  async start() {
    if (this.o.audio) {
      this.pipe = '\\\\.\\pipe\\staff-toolkit-' + process.pid + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7);
      this.server = net.createServer((s) => {
        this.sock = s;
        s.on('error', () => {});
        // Silence until the app's sound arrives, so recording never waits for it
        this.silence = setInterval(() => { if (!this.gotSound) this.rawWrite(Buffer.alloc(960 * 4)); }, 20);
      });
      await new Promise((res, rej) => { this.server.once('error', rej); this.server.listen(this.pipe, res); });
    }
    const t0 = Date.now() / 1000;
    this.proc = spawn(ff.ffmpegPath(), this.args(t0), { windowsHide: true, stdio: ['pipe', 'ignore', 'pipe'] });
    this.proc.stdin.on('error', () => {});
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { if (!this.started) { this.kill(); reject(new Error('it took too long to start' + (this.err ? ': ' + this.err.trim().split('\n').pop() : ''))); } }, 10000);
      this.proc.stderr.on('data', (d) => {
        const s = d.toString();
        if (/frame=\s*\d+/.test(s) && !this.started && /frame=\s*[1-9]/.test(s)) { this.started = true; clearTimeout(timer); resolve(); }
        if (!/frame=/.test(s)) this.err = (this.err + s).slice(-2000);
      });
      this.proc.on('error', (e) => { clearTimeout(timer); reject(e); });
      this.proc.on('close', (code) => {
        this.closed = true;
        this.cleanupPipe();
        if (!this.started) { clearTimeout(timer); reject(new Error((this.err.trim().split('\n').slice(-2).join(' ') || 'ffmpeg stopped (' + code + ')'))); }
        if (this.onClose) this.onClose(code);
      });
    });
  }

  /** Sound from the app (16-bit stereo at 48 kHz). */
  write(buf) {
    this.gotSound = true;
    this.rawWrite(buf);
  }

  rawWrite(buf) {
    if (this.sock && !this.sock.destroyed) { try { this.sock.write(buf); } catch (_) {} }
  }

  /** Stops and finishes the file properly. */
  stop() {
    return new Promise((resolve) => {
      if (!this.proc || this.closed) { resolve(); return; }
      const done = () => resolve();
      this.proc.once('close', done);
      try { this.proc.stdin.write('q'); } catch (_) {}
      try { this.sock && this.sock.end(); } catch (_) {}
      setTimeout(() => { if (!this.closed) this.kill(); }, 8000);
    });
  }

  kill() { try { this.proc && this.proc.kill(); } catch (_) {} }

  cleanupPipe() {
    clearInterval(this.silence);
    try { this.sock && this.sock.destroy(); } catch (_) {}
    try { this.server && this.server.close(); } catch (_) {}
    this.sock = null; this.server = null;
  }
}

/**
 * Tries the best way to capture first and falls back step by step:
 * Desktop Duplication on the graphics card -> Desktop Duplication copied to the processor ->
 * processor encoding -> the older Windows capture (GDI).
 */
async function startBest(base, log) {
  const enc = await ff.encoder();
  const tries = [
    { method: 'dda', enc },
    ...(enc !== 'libx264' ? [{ method: 'dda', enc, download: true }] : []),
    { method: 'dda', enc: 'libx264' },
    { method: 'gdi', enc: enc === 'libx264' ? 'libx264' : enc },
    { method: 'gdi', enc: 'libx264' },
  ];
  let last = null;
  for (const t of tries) {
    const o = { ...base, ...t };
    const c = new Capture(o);
    if (base.onCreate) base.onCreate(c);
    try {
      await c.start();
      if (log) log('capture: ' + o.method + ' + ' + o.enc);
      return c;
    } catch (e) {
      last = e;
      if (log) log('capture try failed (' + o.method + ' + ' + o.enc + '): ' + e.message);
      if (o.out) fs.rm(o.out, { force: true }, () => {});   // don't leave a half-written file behind
    }
  }
  throw last || new Error('no way to capture the screen worked');
}

module.exports = { Capture, startBest, RATE };
