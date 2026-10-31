// Tests the video jobs: joining replay pieces, editing with crop + music, shrinking (run in the build)
const assert = require('assert');
const fs = require('fs'); const os = require('os'); const path = require('path');
const ff = require('./ffmpeg.js');
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fft-'));
  const pieces = [];
  for (let i = 0; i < 3; i++) {
    const p = path.join(dir, 'p' + i + '.mp4');
    await ff.run(['-f', 'lavfi', '-i', 'testsrc=s=640x360:r=30:d=10', '-f', 'lavfi', '-i', 'sine=f=440:d=10', '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '30', '-c:a', 'aac', '-shortest', p]);
    pieces.push(p);
  }
  const clip = path.join(dir, 'clip.mp4');
  await ff.joinLast(pieces, 15, clip);
  const d = await ff.duration(clip);
  assert.ok(d > 13 && d < 17, 'clip length ' + d);
  const music = path.join(dir, 'music.wav');
  await ff.run(['-f', 'lavfi', '-i', 'sine=f=220:d=4', music]);
  const edited = path.join(dir, 'edited.mp4');
  await ff.edit(clip, edited, { start: 2, end: 9, crop: { x: 100, y: 0, w: 203, h: 360 }, volume: 0.5, music: { file: music, volume: 0.8, offset: 1, fadeOut: true } });
  const de = await ff.duration(edited);
  assert.ok(de > 6.5 && de < 7.5, 'edited length ' + de);
  assert.ok(await ff.hasAudio(edited), 'edited has sound');
  const muted = path.join(dir, 'muted.mp4');
  await ff.edit(clip, muted, { start: 0, end: 3, volume: 0 });
  assert.ok(!(await ff.hasAudio(muted)), 'muted has no sound');
  const small = path.join(dir, 'small.mp4');
  await ff.shrink(clip, small, 1024 * 1024);
  assert.ok(fs.statSync(small).size < 1024 * 1024, 'shrunk size ' + fs.statSync(small).size);
  console.log('video jobs: all tests passed (encoder: ' + (await ff.encoder()) + ')');
})().catch((e) => { console.error(e); process.exit(1); });
