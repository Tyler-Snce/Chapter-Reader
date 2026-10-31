// Tests the recorder on Windows: a recording with sound fed in live, and replay-buffer clips.
// (The build machine has no graphics card, so this exercises the fallback capture.)
if (process.platform !== 'win32') { console.log('recorder test: skipped (Windows only)'); process.exit(0); }
const assert = require('assert');
const fs = require('fs'); const os = require('os'); const path = require('path');
const { startBest } = require('./capture.js');
const ff = require('./ffmpeg.js');

function sine(ms, t0) {
  const frames = Math.round(48000 * ms / 1000);
  const b = Buffer.alloc(frames * 4);
  for (let i = 0; i < frames; i++) {
    const v = Math.round(Math.sin(2 * Math.PI * 440 * (t0 + i) / 48000) * 8000);
    b.writeInt16LE(v, i * 4); b.writeInt16LE(v, i * 4 + 2);
  }
  return b;
}
async function feed(c, seconds) {
  let n = 0;
  await new Promise((res) => {
    const iv = setInterval(() => { c.write(sine(20, n)); n += 960; if (n >= 48000 * seconds) { clearInterval(iv); res(); } }, 20);
  });
}
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cap-'));
  const log = (m) => console.log('  ' + m);
  // A recording
  const out = path.join(dir, 'rec.mkv');
  const c = await startBest({ fps: 30, quality: 'standard', audio: true, out, mouse: false }, log);
  await feed(c, 4);
  await c.stop();
  const d = await ff.duration(out);
  assert.ok(d > 2.5 && d < 6, 'recording length ' + d);
  assert.ok(await ff.hasAudio(out), 'recording has sound');
  const mp4 = path.join(dir, 'rec.mp4');
  await ff.finish(out, mp4);
  assert.ok(fs.statSync(mp4).size > 1000);
  // The replay buffer and a clip from it
  const seg = path.join(dir, 'seg'); fs.mkdirSync(seg);
  const r = await startBest({ fps: 30, quality: 'standard', audio: true, segmentDir: seg, segments: 10, mouse: false }, log);
  await feed(r, 12);
  const pieces = fs.readdirSync(seg).filter((f) => f.endsWith('.ts')).map((f) => path.join(seg, f))
    .sort((a, b) => fs.statSync(a).mtimeMs - fs.statSync(b).mtimeMs);
  assert.ok(pieces.length >= 2, 'pieces ' + pieces.length);
  const copies = pieces.map((p, i) => { const to = path.join(dir, 'c' + i + '.ts'); fs.copyFileSync(p, to); return to; });
  const clip = path.join(dir, 'clip.mp4');
  await ff.joinLast(copies, 8, clip);
  await r.stop();
  const dc = await ff.duration(clip);
  assert.ok(dc > 6 && dc < 10, 'clip length ' + dc);
  assert.ok(await ff.hasAudio(clip), 'clip has sound');
  console.log('recorder: all tests passed');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
