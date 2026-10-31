// Video jobs done with ffmpeg: joining replay pieces, tidying recordings, trimming, shrinking.
const { spawn, execFile } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

function ffmpegPath() {
  let p;
  try { p = require('ffmpeg-static'); } catch (_) { p = null; }
  if (p && fs.existsSync(p)) return p;
  const local = path.join(__dirname, 'node_modules', 'ffmpeg-static', process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg');
  return fs.existsSync(local) ? local : 'ffmpeg';
}

function run(args, onProgress) {
  return new Promise((resolve, reject) => {
    const p = spawn(ffmpegPath(), ['-hide_banner', '-y', ...args], { windowsHide: true });
    let err = '';
    p.stderr.on('data', (d) => {
      const s = d.toString();
      err = (err + s).slice(-4000);
      const m = /time=(\d+):(\d+):(\d+\.\d+)/.exec(s);
      if (m && onProgress) onProgress(+m[1] * 3600 + +m[2] * 60 + +m[3]);
    });
    p.on('error', reject);
    p.on('close', (code) => (code === 0 ? resolve() : reject(new Error('ffmpeg failed: ' + err.split('\n').slice(-3).join(' ')))));
  });
}

/** How long a video is, in seconds. */
function duration(file) {
  return new Promise((resolve) => {
    execFile(ffmpegPath(), ['-hide_banner', '-i', file], { windowsHide: true }, (_e, _o, stderr) => {
      const m = /Duration: (\d+):(\d+):(\d+\.\d+)/.exec(stderr || '');
      resolve(m ? +m[1] * 3600 + +m[2] * 60 + +m[3] : 0);
    });
  });
}

// The fastest H.264 encoder this PC has: NVIDIA, AMD, Intel graphics, or the processor
let encoderCache = null;
async function encoder() {
  if (encoderCache) return encoderCache;
  for (const enc of ['h264_nvenc', 'h264_amf', 'h264_qsv']) {
    try {
      await run(['-f', 'lavfi', '-i', 'color=c=black:s=256x256:d=0.2', '-c:v', enc, '-f', 'null', '-']);
      encoderCache = enc;
      return enc;
    } catch (_) {}
  }
  encoderCache = 'libx264';
  return encoderCache;
}

function encodeArgs(enc, quality = 'high') {
  const q = { standard: 26, high: 21, max: 17 }[quality] || 21;
  if (enc === 'h264_nvenc') return ['-c:v', enc, '-preset', 'p5', '-rc', 'vbr', '-cq', String(q), '-b:v', '0'];
  if (enc === 'h264_amf') return ['-c:v', enc, '-quality', 'balanced', '-rc', 'cqp', '-qp_i', String(q), '-qp_p', String(q + 2)];
  if (enc === 'h264_qsv') return ['-c:v', enc, '-global_quality', String(q)];
  return ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', String(q)];
}

/** Makes a recording into a normal .mp4 that plays everywhere (no quality loss for the video). */
async function finish(input, output, onProgress) {
  const isWebm = /\.webm$/i.test(input);
  await run(['-i', input, '-map', '0', '-c:v', 'copy', ...(isWebm ? ['-c:a', 'aac', '-b:a', '192k'] : ['-c:a', 'copy']),
    '-movflags', '+faststart', output], onProgress);
}

/** Joins replay pieces and keeps the last [seconds]. */
async function joinLast(pieces, seconds, output) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stk-'));
  const list = path.join(dir, 'list.txt');
  fs.writeFileSync(list, pieces.map((p) => "file '" + p.replace(/'/g, "'\\''") + "'").join('\n'));
  const ext = path.extname(pieces[0]);
  const joined = path.join(dir, 'joined' + ext);
  await run(['-f', 'concat', '-safe', '0', '-i', list, '-c', 'copy', joined]);
  const total = await duration(joined);
  const start = Math.max(0, total - seconds);
  const isWebm = ext.toLowerCase() === '.webm';
  await run(['-ss', start.toFixed(2), '-i', joined, '-c:v', 'copy', ...(isWebm ? ['-c:a', 'aac', '-b:a', '192k'] : ['-c:a', 'copy']),
    '-movflags', '+faststart', output]);
  fs.rmSync(dir, { recursive: true, force: true });
}

/** Cuts out [start, end] seconds. Exact cuts re-encode the video; quick cuts don't. */
async function trim(input, output, start, end, exact, quality, onProgress) {
  const len = Math.max(0.1, end - start);
  if (!exact) {
    await run(['-ss', start.toFixed(2), '-i', input, '-t', len.toFixed(2), '-c', 'copy', '-movflags', '+faststart', output], onProgress);
    return;
  }
  const enc = await encoder();
  await run(['-ss', start.toFixed(2), '-i', input, '-t', len.toFixed(2), ...encodeArgs(enc, quality),
    '-c:a', 'aac', '-b:a', '192k', '-movflags', '+faststart', output], onProgress);
}

/** Whether a video has a sound track. */
function hasAudio(file) {
  return new Promise((resolve) => {
    execFile(ffmpegPath(), ['-hide_banner', '-i', file], { windowsHide: true }, (_e, _o, stderr) => resolve(/Stream #.*Audio:/.test(stderr || '')));
  });
}

/**
 * Edits a video: cut to [start, end], crop to {x, y, w, h} (in video pixels), change the original
 * sound's volume (0 = muted), and mix in music {file, volume, offset, fadeOut}.
 */
async function edit(input, output, opts, onProgress) {
  const { start = 0, end, crop, volume = 1, music, quality = 'high' } = opts;
  const len = Math.max(0.1, (end || (await duration(input))) - start);
  const args = ['-ss', start.toFixed(3), '-t', len.toFixed(3), '-i', input];
  if (music && music.file) args.push('-ss', String(Math.max(0, music.offset || 0)), '-stream_loop', '-1', '-i', music.file);
  const filters = [];
  let vOut = '0:v:0';
  if (crop && crop.w > 0 && crop.h > 0) {
    const even = (n) => Math.max(2, Math.floor(n / 2) * 2);
    filters.push('[0:v:0]crop=' + even(crop.w) + ':' + even(crop.h) + ':' + Math.max(0, Math.round(crop.x)) + ':' + Math.max(0, Math.round(crop.y)) + '[v]');
    vOut = '[v]';
  }
  const orig = volume > 0 && (await hasAudio(input));
  const parts = [];
  if (orig) { filters.push('[0:a:0]volume=' + volume.toFixed(2) + '[a0]'); parts.push('[a0]'); }
  if (music && music.file) {
    const fade = music.fadeOut ? ',afade=t=out:st=' + Math.max(0, len - 3).toFixed(2) + ':d=3' : '';
    filters.push('[1:a:0]atrim=0:' + len.toFixed(3) + ',asetpts=PTS-STARTPTS,volume=' + (music.volume ?? 0.6).toFixed(2) + fade + '[a1]');
    parts.push('[a1]');
  }
  let aOut = null;
  if (parts.length === 2) { filters.push('[a0][a1]amix=inputs=2:duration=first:normalize=0[a]'); aOut = '[a]'; }
  else if (parts.length === 1) aOut = parts[0];
  if (filters.length) args.push('-filter_complex', filters.join(';'));
  args.push('-map', vOut);
  if (aOut) args.push('-map', aOut, '-c:a', 'aac', '-b:a', '192k'); else args.push('-an');
  const enc = await encoder();
  args.push(...encodeArgs(enc, quality), '-pix_fmt', 'yuv420p', '-t', len.toFixed(3), '-movflags', '+faststart', output);
  await run(args, onProgress);
}

/** Shrinks a video to fit under [maxBytes] (for Discord's upload limit). Tries again smaller if needed. */
async function shrink(input, output, maxBytes, onProgress) {
  const secs = Math.max(1, await duration(input));
  const enc = await encoder();
  let target = maxBytes * 0.9;
  for (let attempt = 0; attempt < 3; attempt++) {
    const totalKbps = (target * 8) / 1000 / secs;
    const audioKbps = totalKbps < 400 ? 48 : 96;
    const videoKbps = Math.max(60, Math.floor(totalKbps - audioKbps));
    const height = videoKbps < 600 ? 360 : videoKbps < 1200 ? 480 : videoKbps < 3000 ? 720 : 1080;
    const rate = enc === 'libx264' ? ['-c:v', 'libx264', '-preset', 'veryfast'] : ['-c:v', enc];
    await run(['-i', input, '-vf', 'scale=-2:min(' + height + '\\,ih)', ...rate, '-b:v', videoKbps + 'k', '-maxrate', videoKbps + 'k',
      '-bufsize', videoKbps * 2 + 'k', '-c:a', 'aac', '-b:a', audioKbps + 'k', '-movflags', '+faststart', output], onProgress);
    const size = fs.statSync(output).size;
    if (size <= maxBytes) return;
    target = target * (maxBytes / size) * 0.9;
  }
}

module.exports = { run, duration, encoder, finish, joinLast, trim, edit, hasAudio, shrink, ffmpegPath };
