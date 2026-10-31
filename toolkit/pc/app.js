// The Staff Toolkit window: chat flags, captures, settings, the recorder and the video editor.
const { ipcRenderer } = require('electron');
const fs = require('fs');
const path = require('path');

const $ = (id) => document.getElementById(id);
let S = {};          // settings
let flags = [];
let captures = [];
const el = (tag, props = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'text') e.textContent = v;
    else e.setAttribute(k, v);
  }
  for (const c of kids) if (c != null) e.append(c);
  return e;
};
const set = (patch) => { Object.assign(S, patch); ipcRenderer.send('set', patch); };
const fmtTime = (t) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const fmtDate = (t) => new Date(t).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
const fmtSize = (b) => (b > 1e9 ? (b / 1e9).toFixed(2) + ' GB' : b > 1e6 ? (b / 1e6).toFixed(1) + ' MB' : Math.round(b / 1e3) + ' KB');
const fmtDur = (s) => { s = Math.max(0, s || 0); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = Math.floor(s % 60); return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); };

let toastTimer = null;
function toast(msg) {
  $('toast').textContent = msg;
  $('toast').classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('toast').classList.remove('show'), 5000);
}

// ---------- tabs ----------
function showTab(name) {
  document.querySelectorAll('nav button[data-tab]').forEach((b) => b.classList.toggle('on', b.dataset.tab === name));
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('on', t.id === 'tab-' + name));
  if (name === 'chat') { $('flagBadge').hidden = true; }
}
document.querySelectorAll('nav button[data-tab]').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));
ipcRenderer.on('tab', (_e, t) => showTab(t));

// ---------- chat watcher ----------
function renderWatch(st) {
  const on = S.watcherOn;
  $('watcherOn').checked = !!on;
  if (!on) { $('watchDot').className = 'dot'; $('watchText').textContent = 'Not watching chat'; $('watchPath').textContent = ''; return; }
  if (st && st.watching) {
    $('watchDot').className = 'dot ok';
    $('watchText').textContent = 'Watching chat' + (currentServer ? ' on ' + currentServer : '') + ' – flagged messages appear below';
    $('watchPath').textContent = st.watching;
  } else {
    $('watchDot').className = 'dot bad';
    $('watchText').textContent = 'No Minecraft log found yet. Start the game, or choose your launcher\'s log.';
    $('watchPath').textContent = '';
  }
}
let currentServer = null;
let watchStatus = {};
ipcRenderer.on('watch-status', (_e, s) => { watchStatus = s; renderWatch(s); });
ipcRenderer.on('server', (_e, s) => { currentServer = s; renderWatch(watchStatus); });
$('watcherOn').addEventListener('change', () => { set({ watcherOn: $('watcherOn').checked }); renderWatch(watchStatus); });
$('chooseLog').addEventListener('click', chooseLog);
$('chooseLog2').addEventListener('click', chooseLog);
async function chooseLog() { const p = await ipcRenderer.invoke('choose-log'); if (p) { toast('Added: ' + p); renderLogPaths(); } }

function flagCard(f) {
  const chips = f.reasons.map((r) => el('span', { class: 'chip' + (r === 'Watched player' ? ' watch' : ''), text: r }));
  const acts = el('div', { class: 'acts' },
    el('button', { class: 'small', text: 'Copy', title: 'Copy as text for a report', onclick: () => {
      ipcRenderer.send('copy', '[' + fmtDate(f.time) + '] ' + (f.server ? '(' + f.server + ') ' : '') + (f.player ? f.player + ': ' : '') + f.message + (f.evidence ? '\nEvidence: ' + f.evidence : ''));
      toast('Copied');
    } }),
    el('button', { class: 'small', text: '⏪ Clip', title: 'Save the last seconds as evidence', disabled: rec.replay ? null : 'disabled', onclick: () => saveClip(f.id) }),
    f.status === 'done'
      ? el('button', { class: 'small', text: 'Undo', onclick: () => ipcRenderer.send('flag-status', { id: f.id, status: 'new' }) })
      : el('button', { class: 'small primary', text: 'Handled', onclick: () => ipcRenderer.send('flag-status', { id: f.id, status: 'done' }) }),
  );
  if (!rec.replay) acts.children[1].title = 'Turn on the replay buffer (Capture tab) to save clips';
  return el('div', { class: 'flag' + (f.status === 'done' ? ' done' : ''), 'data-id': f.id },
    el('div', {},
      el('div', {}, ...chips),
      el('div', { class: 'msg' }, f.player ? el('span', { class: 'who', text: f.player + ': ' }) : null, f.message),
      el('div', { class: 'meta' }, fmtDate(f.time) + (f.server ? ' · ' + f.server : ''),
        f.evidence ? el('span', {}, ' · Evidence: ', el('span', { class: 'link', text: f.evidence, onclick: () => ipcRenderer.send('open-link', f.evidence) })) : null),
    ),
    acts);
}
function renderFlags() {
  const list = $('flagList');
  list.textContent = '';
  const shown = flags.filter((f) => $('showDone').checked || f.status !== 'done');
  if (!shown.length) { list.append(el('div', { class: 'empty', text: flags.length ? 'Nothing new. Tick "Show handled" to see older ones.' : 'No flagged messages yet. They appear here as soon as someone breaks a rule.' })); return; }
  for (const f of shown.slice(0, 200)) list.append(flagCard(f));
}
$('showDone').addEventListener('change', renderFlags);
$('clearFlags').addEventListener('click', () => { if (!$('clearFlags').dataset.sure) { $('clearFlags').dataset.sure = 1; $('clearFlags').textContent = 'Click again to clear'; setTimeout(() => { delete $('clearFlags').dataset.sure; $('clearFlags').textContent = 'Clear all'; }, 3000); return; } flags = []; ipcRenderer.send('clear-flags'); renderFlags(); });
ipcRenderer.on('flag', (_e, f) => {
  flags.unshift(f);
  renderFlags();
  if (!$('tab-chat').classList.contains('on')) { const b = $('flagBadge'); b.hidden = false; b.textContent = String((+b.textContent || 0) + 1); }
});
ipcRenderer.on('flag-update', (_e, f) => { const i = flags.findIndex((x) => x.id === f.id); if (i >= 0) flags[i] = f; renderFlags(); });

let chatLines = 0;
ipcRenderer.on('chat', (_e, c) => {
  const box = $('liveChat');
  if (!chatLines) box.textContent = '';
  const atBottom = box.scrollTop + box.clientHeight >= box.scrollHeight - 20;
  box.append(el('div', { text: fmtTime(c.time) + '  ' + c.text }));
  if (++chatLines > 300) { box.firstChild.remove(); chatLines--; }
  if (atBottom) box.scrollTop = box.scrollHeight;
});

// ---------- recorder ----------
// One capture stream (screen + chosen audio) feeds both the full recording and the replay buffer.
const rec = { stream: null, parts: [], ctx: null, recorder: null, recording: false, since: 0, file: null, out: null, write: null, replay: false, segs: [], replayTimer: null, mime: '' };
const SEG_MS = 10000;

async function buildStream() {
  if (rec.stream) return rec.stream;
  const res = { native: null, 1440: 1440, 1080: 1080, 720: 720 }[S.resolution];
  const fps = Number(S.fps) || 60;
  const wantDesktop = S.audio.desktop.on && !S.audio.muted;
  const display = await navigator.mediaDevices.getDisplayMedia({
    video: { frameRate: { ideal: fps, max: fps }, ...(res ? { height: { max: res } } : {}) },
    audio: wantDesktop,
  });
  rec.parts = [display];
  const tracks = [display.getVideoTracks()[0]];
  if (!S.audio.muted) {
    const ctx = new AudioContext({ sampleRate: 48000 });
    const dest = ctx.createMediaStreamDestination();
    let any = false;
    const add = (st, volume) => { const g = ctx.createGain(); g.gain.value = volume; ctx.createMediaStreamSource(st).connect(g).connect(dest); any = true; };
    if (display.getAudioTracks().length) add(new MediaStream(display.getAudioTracks()), S.audio.desktop.volume ?? 1);
    const mic = async (id, volume, noise) => {
      try {
        const st = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: id && id !== 'default' ? { exact: id } : undefined, echoCancellation: false, noiseSuppression: !!noise, autoGainControl: false } });
        rec.parts.push(st); add(st, volume ?? 1);
      } catch (e) { toast('Couldn\'t use an audio device: ' + e.message); }
    };
    if (S.audio.mic.on) await mic(S.audio.mic.id, S.audio.mic.volume, S.audio.mic.noise);
    for (const x of S.audio.extra || []) if (x.on) await mic(x.id, x.volume, false);
    if (any) tracks.push(dest.stream.getAudioTracks()[0]);
    rec.ctx = ctx;
  }
  rec.stream = new MediaStream(tracks);
  tracks[0].addEventListener('ended', () => { toast('The window or screen being captured closed'); stopAll(); });
  return rec.stream;
}

function dropStream() {
  if (rec.recording || rec.replay) return;
  for (const p of rec.parts) p.getTracks().forEach((t) => t.stop());
  rec.parts = [];
  if (rec.ctx) { rec.ctx.close().catch(() => {}); rec.ctx = null; }
  rec.stream = null;
}

function recorderOptions(stream) {
  const audio = stream.getAudioTracks().length > 0;
  const list = audio
    ? ['video/mp4;codecs=avc1.640033,mp4a.40.2', 'video/mp4;codecs=avc1,mp4a.40.2', 'video/webm;codecs=h264,opus', 'video/webm;codecs=vp9,opus', 'video/webm']
    : ['video/mp4;codecs=avc1.640033', 'video/mp4;codecs=avc1', 'video/webm;codecs=h264', 'video/webm;codecs=vp9', 'video/webm'];
  const mime = list.find((m) => MediaRecorder.isTypeSupported(m)) || '';
  const v = stream.getVideoTracks()[0].getSettings();
  const scale = ((v.width || 1920) * (v.height || 1080) * (v.frameRate || 60)) / (1920 * 1080 * 60);
  const base = { standard: 8e6, high: 16e6, max: 35e6 }[S.quality] || 16e6;
  return { mimeType: mime, videoBitsPerSecond: Math.round(Math.max(2e6, Math.min(120e6, base * scale))), audioBitsPerSecond: 192000 };
}
const extFor = (mime) => (mime.startsWith('video/mp4') ? '.mp4' : '.webm');

function pushState() {
  ipcRenderer.send('rec-state', { recording: rec.recording, replay: rec.replay, since: rec.since });
  $('recBtn').classList.toggle('recording', rec.recording);
  $('recBtn').firstChild.textContent = rec.recording ? '⏹ Stop recording' : '⏺ Start recording';
  $('recBadge').classList.toggle('show', rec.recording);
  $('clipBtn').disabled = !rec.replay;
  $('replayOn').checked = !!S.replayOn;
  renderFlags();
}

async function browserStartRecording() {
  if (rec.recording) return;
  try {
    const stream = await buildStream();
    const opts = recorderOptions(stream);
    const tmp = await ipcRenderer.invoke('temp-dir');
    rec.file = path.join(tmp, 'rec-' + Date.now() + extFor(opts.mimeType));
    rec.out = await ipcRenderer.invoke('out-file', 'recording');
    const out = fs.createWriteStream(rec.file);
    let chain = Promise.resolve();
    // Written to disk every second, so long recordings don't fill up memory
    const r = new MediaRecorder(stream, opts);
    r.ondataavailable = (e) => { if (e.data.size) chain = chain.then(async () => { out.write(Buffer.from(await e.data.arrayBuffer())); }); };
    r.onstop = () => {
      chain.then(() => new Promise((res) => out.end(res))).then(async () => {
        const raw = rec.file;
        rec.recording = false; pushState(); dropStream();
        await ipcRenderer.invoke('finish-recording', { raw, out: rec.out });
      });
    };
    r.onerror = (e) => toast('Recording problem: ' + (e.error ? e.error.message : 'unknown'));
    r.start(1000);
    rec.recorder = r;
    rec.recording = true;
    rec.since = Date.now();
    pushState();
  } catch (e) {
    dropStream();
    toast('Couldn\'t start recording: ' + e.message);
  }
}
function browserStopRecording() { if (rec.recording && rec.recorder && rec.recorder.state !== 'inactive') rec.recorder.stop(); }

setInterval(() => { if (rec.recording) $('recTime').textContent = fmtDur((Date.now() - rec.since) / 1000); }, 500);

// Replay buffer: keeps recording in 10 second pieces and throws away old ones
function startPiece() {
  const opts = recorderOptions(rec.stream);
  const seg = { chunks: [], start: Date.now(), mime: opts.mimeType };
  const r = new MediaRecorder(rec.stream, opts);
  r.ondataavailable = (e) => { if (e.data.size) seg.chunks.push(e.data); };
  r.start(1000);
  seg.rec = r;
  rec.segs.push(seg);
  const keep = Math.ceil(((S.replaySeconds || 30) * 1000) / SEG_MS) + 2;
  while (rec.segs.length > keep) { const old = rec.segs.shift(); if (old.rec.state !== 'inactive') old.rec.stop(); }
}
async function browserStartReplay() {
  if (rec.replay) return;
  try {
    await buildStream();
    rec.replay = true;
    rec.segs = [];
    startPiece();
    rec.replayTimer = setInterval(() => {
      const old = rec.segs[rec.segs.length - 1];
      startPiece();
      // Small overlap so there's no gap between pieces
      setTimeout(() => { if (old && old.rec.state !== 'inactive') old.rec.stop(); }, 300);
    }, SEG_MS);
    pushState();
  } catch (e) {
    rec.replay = false;
    set({ replayOn: false });
    dropStream();
    toast('Couldn\'t start the replay buffer: ' + e.message);
    pushState();
  }
}
function browserStopReplay() {
  clearInterval(rec.replayTimer);
  for (const s of rec.segs) if (s.rec.state !== 'inactive') s.rec.stop();
  rec.segs = [];
  rec.replay = false;
  dropStream();
  pushState();
}
async function browserSaveClip(flagId) {
  if (!rec.replay || !rec.segs.length) { toast('Turn on the replay buffer first (Capture tab)'); return; }
  const secs = S.replaySeconds || 30;
  const cur = rec.segs[rec.segs.length - 1];
  if (cur.rec.state === 'recording') cur.rec.requestData();
  await new Promise((r) => setTimeout(r, 250));
  const need = Math.ceil((secs * 1000) / SEG_MS) + 1;
  const segs = rec.segs.slice(-need).filter((s) => s.chunks.length);
  const tmp = await ipcRenderer.invoke('temp-dir');
  const pieces = [];
  for (const [i, s] of segs.entries()) {
    const p = path.join(tmp, 'piece-' + Date.now() + '-' + i + extFor(s.mime));
    fs.writeFileSync(p, Buffer.from(await new Blob(s.chunks, { type: s.mime }).arrayBuffer()));
    pieces.push(p);
  }
  const out = await ipcRenderer.invoke('out-file', 'clip');
  toast('Saving the last ' + secs + ' seconds…');
  await ipcRenderer.invoke('finish-clip', { pieces, seconds: secs, out, flagId: typeof flagId === 'string' ? flagId : null });
}
// ---------- the main recorder (ffmpeg: no mouse cursor, works with fullscreen games) ----------
// Used for screen recordings and the replay buffer; recording a single window, or if it can't
// start on this PC, uses the browser-based recorder above instead.
const isScreen = () => !S.source || S.source.startsWith('screen');
const cleanErr = (e) => String((e && e.message) || e).replace(/^Error invoking remote method '[\w-]+': (Error: )?/, '');
const snd = { ctx: null, parts: [], users: new Set() };

/** Mixes the chosen sound (desktop, mic, extra devices) and streams it to the recorder. */
async function ensureAudio(user) {
  const a = S.audio;
  if (a.muted || !(a.desktop.on || a.mic.on || (a.extra || []).some((x) => x.on))) return false;
  snd.users.add(user);
  if (snd.ctx) return true;
  const ctx = new AudioContext({ sampleRate: 48000 });
  await ctx.audioWorklet.addModule('pcm-worklet.js');
  const node = new AudioWorkletNode(ctx, 'pcm-tap', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2], channelCount: 2, channelCountMode: 'explicit' });
  node.port.onmessage = (e) => ipcRenderer.send('pcm', Buffer.from(e.data));
  const sink = ctx.createGain();
  sink.gain.value = 0;
  node.connect(sink).connect(ctx.destination);
  const add = (st, volume) => { const g = ctx.createGain(); g.gain.value = volume ?? 1; ctx.createMediaStreamSource(st).connect(g).connect(node); };
  if (a.desktop.on) {
    try {
      const d = await navigator.mediaDevices.getDisplayMedia({ video: { width: { max: 320 }, height: { max: 180 }, frameRate: { max: 1 } }, audio: true });
      snd.parts.push(d);
      if (d.getAudioTracks().length) add(new MediaStream(d.getAudioTracks()), a.desktop.volume);
    } catch (e) { toast('Couldn\'t get desktop sound: ' + e.message); }
  }
  const dev = async (id, volume, noise) => {
    try {
      const st = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: id && id !== 'default' ? { exact: id } : undefined, echoCancellation: false, noiseSuppression: !!noise, autoGainControl: false } });
      snd.parts.push(st); add(st, volume);
    } catch (e) { toast('Couldn\'t use an audio device: ' + e.message); }
  };
  if (a.mic.on) await dev(a.mic.id, a.mic.volume, a.mic.noise);
  for (const x of a.extra || []) if (x.on) await dev(x.id, x.volume, false);
  snd.ctx = ctx;
  return true;
}
function releaseAudio(user) {
  snd.users.delete(user);
  if (snd.users.size || !snd.ctx) return;
  for (const p of snd.parts) p.getTracks().forEach((t) => t.stop());
  snd.parts = [];
  snd.ctx.close().catch(() => {});
  snd.ctx = null;
}

async function startRecording() {
  if (rec.recording) return;
  if (isScreen()) {
    try {
      const audio = await ensureAudio('rec');
      await ipcRenderer.invoke('ff-start', { kind: 'record', audio });
      rec.engine = 'ff'; rec.recording = true; rec.since = Date.now();
      pushState();
      return;
    } catch (e) {
      releaseAudio('rec');
      toast('Using the standard recorder (' + cleanErr(e) + ')');
    }
  }
  rec.engine = 'browser';
  await browserStartRecording();
}
async function stopRecording() {
  if (!rec.recording) return;
  if (rec.engine === 'ff') {
    rec.recording = false;
    pushState();
    try { await ipcRenderer.invoke('ff-stop', { kind: 'record' }); } catch (e) { toast('Couldn\'t save the recording: ' + cleanErr(e)); }
    releaseAudio('rec');
    return;
  }
  browserStopRecording();
}
function toggleRecording() { rec.recording ? stopRecording() : startRecording(); }

async function startReplay() {
  if (rec.replay) return;
  if (isScreen()) {
    try {
      const audio = await ensureAudio('replay');
      await ipcRenderer.invoke('ff-start', { kind: 'replay', audio });
      rec.replayEngine = 'ff'; rec.replay = true;
      pushState();
      return;
    } catch (e) {
      releaseAudio('replay');
      toast('Using the standard replay buffer (' + cleanErr(e) + ')');
    }
  }
  rec.replayEngine = 'browser';
  await browserStartReplay();
}
async function stopReplay() {
  if (rec.replayEngine === 'ff') {
    rec.replay = false;
    pushState();
    try { await ipcRenderer.invoke('ff-stop', { kind: 'replay' }); } catch (_) {}
    releaseAudio('replay');
    return;
  }
  browserStopReplay();
}
async function saveClip(flagId) {
  if (rec.replay && rec.replayEngine === 'ff') {
    const secs = S.replaySeconds || 30;
    toast('Saving the last ' + secs + ' seconds\u2026');
    try { await ipcRenderer.invoke('ff-clip', { seconds: secs, flagId: typeof flagId === 'string' ? flagId : null }); } catch (e) { toast('Couldn\'t save the clip: ' + cleanErr(e)); }
    return;
  }
  await browserSaveClip(flagId);
}
// The recorder stopped by itself (e.g. the screen setup changed)
ipcRenderer.on('ff-ended', (_e, kind) => {
  if (kind === 'record' && rec.engine === 'ff' && rec.recording) { rec.recording = false; releaseAudio('rec'); toast('Recording stopped unexpectedly \u2013 what was recorded has been saved'); }
  if (kind === 'replay' && rec.replayEngine === 'ff' && rec.replay) { rec.replay = false; releaseAudio('replay'); toast('The replay buffer stopped unexpectedly'); }
  pushState();
});

function stopAll() { stopRecording(); if (rec.replay) { set({ replayOn: false }); stopReplay(); } }

// Restart capture when its settings change (not while recording)
async function captureChanged() {
  if (rec.recording) { toast('Changes apply to your next recording'); return; }
  if (rec.replay) { stopReplay(); await startReplay(); }
}

ipcRenderer.on('rec', (_e, c) => {
  if (c === 'toggle') toggleRecording();
  else if (c === 'clip') saveClip();
  else if (c && c.clip) saveClip(c.flagId);
});
$('recBtn').addEventListener('click', toggleRecording);
$('clipBtn').addEventListener('click', () => saveClip());
$('shotBtn').addEventListener('click', () => ipcRenderer.send('screenshot'));
$('replayOn').addEventListener('change', () => { set({ replayOn: $('replayOn').checked }); $('replayOn').checked ? startReplay() : stopReplay(); });
$('replaySeconds').addEventListener('change', () => { set({ replaySeconds: +$('replaySeconds').value }); $('clipSecs').textContent = $('replaySeconds').value; });
for (const id of ['fps', 'resolution', 'quality']) $(id).addEventListener('change', () => { set({ [id]: $(id).value }); captureChanged(); });
$('showCursor').addEventListener('change', () => { set({ showCursor: $('showCursor').checked }); captureChanged(); });

// What to capture
$('pickSrc').addEventListener('click', async () => {
  const box = $('sources');
  if (!box.hidden) { box.hidden = true; return; }
  box.textContent = 'Loading…';
  box.hidden = false;
  const list = await ipcRenderer.invoke('sources');
  box.textContent = '';
  for (const s of list) {
    const b = el('button', { class: 'src' + ((S.source || '') === s.id || (!S.source && s.id.startsWith('screen') && !box.querySelector('.on')) ? ' on' : ''), title: s.name, onclick: () => {
      set({ source: s.id, sourceName: s.name });
      $('srcName').textContent = s.name;
      box.hidden = true;
      captureChanged();
    } }, el('img', { src: s.thumb, alt: '' }), el('span', { text: (s.id.startsWith('screen') ? '\u{1F5A5} ' : '') + s.name }));
    box.append(b);
  }
});

// Audio sources
let devices = [];
async function loadDevices() {
  try {
    // Device names are only given after permission, so ask once
    if (!devices.length) { const t = await navigator.mediaDevices.getUserMedia({ audio: true }); t.getTracks().forEach((x) => x.stop()); }
  } catch (_) {}
  devices = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'audioinput');
  renderAudio();
}
function deviceSelect(value, onChange) {
  const s = el('select', { onchange: () => onChange(s.value) });
  s.append(el('option', { value: 'default', text: 'Default microphone' }));
  for (const d of devices) if (d.deviceId !== 'default' && d.deviceId !== 'communications') s.append(el('option', { value: d.deviceId, text: d.label || 'Audio input' }));
  s.value = value || 'default';
  return s;
}
function volRange(value, onChange) {
  const r = el('input', { type: 'range', min: '0', max: '2', step: '0.05', title: 'Volume' });
  r.value = String(value ?? 1);
  r.addEventListener('change', () => onChange(+r.value));
  return r;
}
function saveAudio() { set({ audio: S.audio }); captureChanged(); }
function renderAudio() {
  const box = $('audioRows');
  box.textContent = '';
  const a = S.audio;
  const check = (on, fn) => { const c = el('input', { type: 'checkbox', title: 'On / off' }); c.checked = !!on; c.addEventListener('change', () => fn(c.checked)); return c; };
  box.append(el('div', { class: 'audioRow' },
    check(a.desktop.on, (v) => { a.desktop.on = v; saveAudio(); }),
    el('span', { text: '\u{1F50A} Desktop sound (game, music, Discord calls)' }),
    volRange(a.desktop.volume, (v) => { a.desktop.volume = v; saveAudio(); }),
    el('span', { class: 'note', text: 'everything you hear' })));
  const noise = el('label', { class: 'note' }, check(a.mic.noise, (v) => { a.mic.noise = v; saveAudio(); }), 'Reduce noise');
  box.append(el('div', { class: 'audioRow' },
    check(a.mic.on, (v) => { a.mic.on = v; saveAudio(); }),
    el('div', {}, el('div', { text: '\u{1F3A4} Microphone' }), deviceSelect(a.mic.id, (v) => { a.mic.id = v; saveAudio(); })),
    volRange(a.mic.volume, (v) => { a.mic.volume = v; saveAudio(); }),
    noise));
  (a.extra || []).forEach((x, i) => {
    box.append(el('div', { class: 'audioRow' },
      check(x.on, (v) => { x.on = v; saveAudio(); }),
      el('div', {}, el('div', { text: '\u{1F39A} Extra audio device' }), deviceSelect(x.id, (v) => { x.id = v; saveAudio(); })),
      volRange(x.volume, (v) => { x.volume = v; saveAudio(); }),
      el('button', { class: 'small', text: 'Remove', onclick: () => { a.extra.splice(i, 1); saveAudio(); renderAudio(); } })));
  });
  $('muted').checked = !!a.muted;
  box.style.opacity = a.muted ? '.45' : '1';
}
$('addAudio').addEventListener('click', () => { S.audio.extra = [...(S.audio.extra || []), { id: 'default', on: true, volume: 1 }]; saveAudio(); renderAudio(); });
$('muted').addEventListener('change', () => { S.audio.muted = $('muted').checked; saveAudio(); renderAudio(); });

// ---------- captures list ----------
function capRow(c) {
  const isShot = c.kind === 'screenshot';
  const ico = el('div', { class: 'ico', draggable: 'true', title: 'Drag into Discord or a folder' }, isShot ? el('img', { src: 'file://' + c.file.replace(/\\/g, '/'), alt: '' }) : (c.kind === 'clip' ? '⏪' : '\u{1F3AC}'));
  ico.addEventListener('dragstart', (e) => { e.preventDefault(); ipcRenderer.send('drag-out', c.file); });
  const linkPart = c.uploading ? el('span', { class: 'note', text: ' · uploading…' })
    : c.link ? el('span', {}, ' · ', el('span', { class: 'link', text: c.link, title: 'Open', onclick: () => ipcRenderer.send('open-link', c.link) }))
      : c.error ? el('span', { class: 'note', style: 'color:var(--warn)', title: c.error, text: ' · upload failed: ' + c.error }) : null;
  if (c.link && c.expires && linkPart) linkPart.append(el('span', { class: 'note', text: ' (link lasts until ' + fmtDate(c.expires) + ')' }));
  const acts = el('div', { class: 'acts' });
  if (c.link) acts.append(el('button', { class: 'small primary', text: 'Copy link', onclick: () => { ipcRenderer.send('copy', c.link); toast('Link copied'); } }));
  else acts.append(el('button', { class: 'small', text: 'Upload', disabled: c.uploading ? 'disabled' : null, onclick: () => ipcRenderer.invoke('upload', c.id) }));
  if (!isShot) acts.append(el('button', { class: 'small', text: '✂ Edit', onclick: () => openEditor(c) }));
  acts.append(
    el('button', { class: 'small', text: 'Copy file', title: 'Paste it into Discord or File Explorer', onclick: () => { ipcRenderer.send('copy-file', c.file); toast('File copied – paste it anywhere'); } }),
    el('button', { class: 'small', text: 'Save as…', onclick: async () => { const p = await ipcRenderer.invoke('save-copy', c.file); if (p) toast('Saved to ' + p); } }),
    el('button', { class: 'small', text: 'Show', title: 'Show in folder', onclick: () => ipcRenderer.send('reveal', c.file) }),
    el('button', { class: 'small danger', text: '✕', title: 'Delete', onclick: (e) => {
      const b = e.currentTarget;
      if (!b.dataset.sure) { b.dataset.sure = 1; b.textContent = 'Delete file?'; setTimeout(() => { delete b.dataset.sure; b.textContent = '✕'; }, 3000); return; }
      captures = captures.filter((x) => x.id !== c.id); ipcRenderer.send('delete-capture', { id: c.id, file: c.file }); renderCaptures();
    } }));
  return el('div', { class: 'cap' },
    ico,
    el('div', { style: 'min-width:0' },
      el('div', { class: 'name', title: c.file, text: path.basename(c.file), ondblclick: () => ipcRenderer.send('open', c.file) }),
      el('div', { class: 'note' }, fmtDate(c.time) + ' · ' + fmtSize(c.size || 0), linkPart)),
    acts);
}
function renderCaptures() {
  const box = $('capList');
  box.textContent = '';
  if (!captures.length) { box.append(el('div', { class: 'empty', text: 'Screenshots, clips and recordings show up here.' })); return; }
  for (const c of captures.slice(0, 150)) box.append(capRow(c));
}
ipcRenderer.on('capture', (_e, c) => { captures.unshift(c); renderCaptures(); });
ipcRenderer.on('capture-update', (_e, c) => { const i = captures.findIndex((x) => x.id === c.id); if (i >= 0) captures[i] = c; renderCaptures(); });
$('openFolder').addEventListener('click', () => ipcRenderer.send('open-folder'));

// ---------- settings ----------
function renderSettings() {
  document.querySelectorAll('input[name=after]').forEach((r) => { r.checked = r.value === S.afterCapture; });
  $('uploadTo').value = S.uploadTo;
  $('webhook').value = S.discordWebhook || '';
  $('flagsToDiscord').checked = !!S.flagsToDiscord;
  $('autoClipFlags').checked = !!S.autoClipFlags;
  $('notifyFlags').checked = !!S.notifyFlags;
  $('startup').checked = !!S.startup;
  document.querySelectorAll('.hotkey').forEach((h) => { h.value = S.hotkeys[h.dataset.key] || ''; });
  $('kShot').textContent = S.hotkeys.screenshot; $('kRec').textContent = S.hotkeys.record; $('kClip').textContent = S.hotkeys.clip;
  document.querySelectorAll('.rule').forEach((c) => { c.checked = S.rules[c.dataset.rule] !== false; });
  for (const k of ['blockedWords', 'allowDomains', 'watchPlayers', 'ignorePlayers']) $(k).value = (S.rules[k] || []).join('\n');
  $('showCursor').checked = !!S.showCursor; $('fps').value = String(S.fps); $('resolution').value = S.resolution; $('quality').value = S.quality;
  $('replaySeconds').value = String(S.replaySeconds); $('clipSecs').textContent = S.replaySeconds;
  $('srcName').textContent = S.sourceName || 'Main screen';
  renderLogPaths();
}
function renderLogPaths() {
  const box = $('logPaths');
  box.textContent = '';
  for (const p of S.logPaths || []) {
    box.append(el('div', { class: 'row', style: 'margin-bottom:6px' }, el('span', { class: 'path grow', text: p }),
      el('button', { class: 'small', text: 'Remove', onclick: () => { set({ logPaths: S.logPaths.filter((x) => x !== p) }); renderLogPaths(); } })));
  }
}
document.querySelectorAll('input[name=after]').forEach((r) => r.addEventListener('change', () => set({ afterCapture: r.value })));
$('uploadTo').addEventListener('change', () => {
  set({ uploadTo: $('uploadTo').value });
  if (/discord|both/.test($('uploadTo').value) && !S.discordWebhook) toast('Add a Discord webhook link below so it knows which channel to post in');
});
$('saveWebhook').addEventListener('click', () => { set({ discordWebhook: $('webhook').value.trim() }); toast('Saved'); });
$('testWebhook').addEventListener('click', async () => {
  set({ discordWebhook: $('webhook').value.trim() });
  try { await ipcRenderer.invoke('test-discord'); toast('Test message sent – check the channel'); } catch (e) { toast(String(e.message).replace(/^Error invoking remote method '[\w-]+': (Error: )?/, '')); }
});
for (const id of ['flagsToDiscord', 'autoClipFlags', 'notifyFlags', 'startup']) $(id).addEventListener('change', () => set({ [id]: $(id).checked }));
$('folderLink').addEventListener('click', () => ipcRenderer.send('open-folder'));
$('chooseFolder').addEventListener('click', async () => { $('folderLink').textContent = await ipcRenderer.invoke('choose-folder'); });
$('saveRules').addEventListener('click', () => {
  const r = { ...S.rules };
  document.querySelectorAll('.rule').forEach((c) => { r[c.dataset.rule] = c.checked; });
  for (const k of ['blockedWords', 'allowDomains', 'watchPlayers', 'ignorePlayers']) r[k] = $(k).value.split('\n').map((x) => x.trim()).filter(Boolean);
  set({ rules: r });
  $('rulesSaved').textContent = 'Saved – used straight away';
  setTimeout(() => { $('rulesSaved').textContent = ''; }, 3000);
});

// Shortcut boxes: press keys to set
document.querySelectorAll('.hotkey').forEach((h) => {
  h.addEventListener('focus', () => { h.dataset.old = h.value; h.value = 'Press keys…'; });
  h.addEventListener('blur', () => { if (h.value === 'Press keys…') h.value = h.dataset.old; });
  h.addEventListener('keydown', (e) => {
    e.preventDefault();
    if (['Control', 'Shift', 'Alt', 'Meta'].includes(e.key)) return;
    if (e.key === 'Escape') { h.blur(); return; }
    const parts = [];
    if (e.ctrlKey) parts.push('Ctrl'); if (e.altKey) parts.push('Alt'); if (e.shiftKey) parts.push('Shift');
    let k = e.key.length === 1 ? e.key.toUpperCase() : e.key;
    if (k === ' ') k = 'Space';
    if (/^Arrow/.test(k)) k = k.slice(5);
    parts.push(k);
    const combo = parts.join('+');
    if (parts.length < 2 && !/^F\d+$/.test(k)) { h.value = 'Use Ctrl, Alt or Shift with a key'; return; }
    h.value = combo;
    set({ hotkeys: { ...S.hotkeys, [h.dataset.key]: combo } });
    renderSettings();
    h.blur();
  });
});
ipcRenderer.on('hotkeys-failed', (_e, list) => { $('hotkeyWarn').textContent = list.length ? 'Another app is already using: ' + list.join(', ') + '. Pick different keys.' : ''; });

ipcRenderer.on('settings', (_e, s) => { S = s; });
ipcRenderer.on('busy', (_e, msg) => { $('busy').textContent = msg; $('busy').classList.toggle('show', !!msg); });
ipcRenderer.on('toast', (_e, msg) => toast(msg));

// ---------- editor ----------
const ed = { cap: null, dur: 0, start: 0, end: 0, crop: null, cropMode: 'none', music: null };
const V = $('edVideo');
const A = $('edAudio');

function openEditor(c) {
  ed.cap = c; ed.crop = null; ed.music = null;
  $('edName').textContent = 'Edit: ' + path.basename(c.file);
  V.src = 'file://' + c.file.replace(/\\/g, '/');
  $('edCrop').value = 'none'; $('cropBox').classList.remove('show');
  $('edVol').value = '1'; $('edVolV').textContent = '100%';
  $('edMusicName').textContent = 'None'; $('edMusicX').hidden = true; A.removeAttribute('src');
  $('editor').classList.add('show');
}
function closeEditor() { V.pause(); A.pause(); V.removeAttribute('src'); V.load(); $('editor').classList.remove('show'); }
$('edClose').addEventListener('click', closeEditor);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && $('editor').classList.contains('show')) closeEditor(); });

V.addEventListener('loadedmetadata', async () => {
  ed.dur = isFinite(V.duration) && V.duration > 0 ? V.duration : await ipcRenderer.invoke('duration', ed.cap.file);
  ed.start = 0; ed.end = ed.dur;
  drawTimeline();
});
function drawTimeline() {
  const d = ed.dur || 1;
  $('tlSel').style.left = (ed.start / d) * 100 + '%';
  $('tlSel').style.width = ((ed.end - ed.start) / d) * 100 + '%';
  $('tlHead').style.left = (V.currentTime / d) * 100 + '%';
  $('edStartT').textContent = fmtDur(ed.start); $('edEndT').textContent = fmtDur(ed.end); $('edNowT').textContent = fmtDur(V.currentTime);
  $('edLen').textContent = 'Length: ' + fmtDur(ed.end - ed.start);
}
V.addEventListener('timeupdate', () => {
  if (!V.paused && V.currentTime >= ed.end) { V.pause(); A.pause(); }
  drawTimeline();
});
$('timeline').addEventListener('pointerdown', (e) => {
  const seek = (ev) => { const r = $('timeline').getBoundingClientRect(); V.currentTime = Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width)) * ed.dur; syncMusic(); drawTimeline(); };
  seek(e);
  const move = (ev) => seek(ev);
  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', () => window.removeEventListener('pointermove', move), { once: true });
});
$('edSetStart').addEventListener('click', () => { ed.start = Math.min(V.currentTime, ed.end - 0.2); drawTimeline(); });
$('edSetEnd').addEventListener('click', () => { ed.end = Math.max(V.currentTime, ed.start + 0.2); drawTimeline(); });
$('edPlay').addEventListener('click', () => {
  if (V.paused) { if (V.currentTime < ed.start || V.currentTime >= ed.end - 0.05) V.currentTime = ed.start; V.play(); syncMusic(true); }
  else { V.pause(); A.pause(); }
});
V.addEventListener('play', () => { $('edPlay').textContent = '⏸ Pause'; });
V.addEventListener('pause', () => { $('edPlay').textContent = '▶ Play'; A.pause(); });

// Preview sound: original volume and music together
function parseTime(s) { const p = String(s).trim().split(':').map(Number); return p.some(isNaN) ? 0 : p.reduce((a, b) => a * 60 + b, 0); }
function syncMusic(play) {
  V.volume = Math.min(1, +$('edVol').value);
  if (!ed.music) return;
  A.volume = Math.min(1, +$('edMusicVol').value);
  A.currentTime = parseTime($('edMusicOff').value) + Math.max(0, V.currentTime - ed.start);
  if (play || !V.paused) A.play().catch(() => {});
}
$('edVol').addEventListener('input', () => { $('edVolV').textContent = Math.round($('edVol').value * 100) + '%'; syncMusic(); });
$('edMute').addEventListener('click', () => { $('edVol').value = $('edVol').value === '0' ? '1' : '0'; $('edVol').dispatchEvent(new Event('input')); });
$('edMusicVol').addEventListener('input', () => { $('edMusicVolV').textContent = Math.round($('edMusicVol').value * 100) + '%'; syncMusic(); });
$('edMusicOff').addEventListener('change', () => syncMusic());
$('edMusic').addEventListener('click', async () => {
  const f = await ipcRenderer.invoke('choose-music');
  if (!f) return;
  ed.music = f;
  A.src = 'file://' + f.replace(/\\/g, '/');
  $('edMusicName').textContent = path.basename(f);
  $('edMusicX').hidden = false;
});
$('edMusicX').addEventListener('click', () => { ed.music = null; A.pause(); A.removeAttribute('src'); $('edMusicName').textContent = 'None'; $('edMusicX').hidden = true; });

// Crop box over the video (stored in screen pixels, converted to video pixels when saving)
const box = $('cropBox');
function placeCrop(mode) {
  ed.cropMode = mode;
  if (mode === 'none') { box.classList.remove('show'); return; }
  const w = V.clientWidth, h = V.clientHeight;
  let bw = w, bh = h;
  if (mode !== 'free') {
    const [a, b] = mode.split(':').map(Number);
    if (w / h > a / b) { bh = h; bw = (h * a) / b; } else { bw = w; bh = (w * b) / a; }
  } else { bw = w * 0.8; bh = h * 0.8; }
  Object.assign(box.style, { left: (w - bw) / 2 + 'px', top: (h - bh) / 2 + 'px', width: bw + 'px', height: bh + 'px' });
  box.classList.add('show');
}
$('edCrop').addEventListener('change', () => placeCrop($('edCrop').value));
box.addEventListener('pointerdown', (e) => {
  e.preventDefault();
  const resize = e.target.tagName === 'I';
  const sx = e.clientX, sy = e.clientY;
  const r0 = { l: box.offsetLeft, t: box.offsetTop, w: box.offsetWidth, h: box.offsetHeight };
  const W = V.clientWidth, H = V.clientHeight;
  const ratio = ed.cropMode !== 'free' && ed.cropMode !== 'none' ? r0.w / r0.h : null;
  const move = (ev) => {
    const dx = ev.clientX - sx, dy = ev.clientY - sy;
    if (resize) {
      let w = Math.max(40, Math.min(W - r0.l, r0.w + dx));
      let h = ratio ? w / ratio : Math.max(40, Math.min(H - r0.t, r0.h + dy));
      if (ratio && r0.t + h > H) { h = H - r0.t; w = h * ratio; }
      box.style.width = w + 'px'; box.style.height = h + 'px';
    } else {
      box.style.left = Math.max(0, Math.min(W - r0.w, r0.l + dx)) + 'px';
      box.style.top = Math.max(0, Math.min(H - r0.h, r0.t + dy)) + 'px';
    }
  };
  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', () => window.removeEventListener('pointermove', move), { once: true });
});
function cropInVideoPixels() {
  if (ed.cropMode === 'none' || !V.videoWidth) return null;
  const k = V.videoWidth / V.clientWidth;
  return { x: box.offsetLeft * k, y: box.offsetTop * k, w: box.offsetWidth * k, h: box.offsetHeight * k };
}

$('edSave').addEventListener('click', async () => {
  const opts = {
    file: ed.cap.file, start: ed.start, end: ed.end, crop: cropInVideoPixels(),
    volume: +$('edVol').value,
    music: ed.music ? { file: ed.music, volume: +$('edMusicVol').value, offset: parseTime($('edMusicOff').value), fadeOut: $('edFade').checked } : null,
    exact: $('edExact').checked,
  };
  $('edSave').disabled = true;
  try {
    await ipcRenderer.invoke('edit', opts);
    closeEditor();
    showTab('capture');
    toast('Edited copy saved');
  } catch (e) {
    toast('Couldn\'t save the edit: ' + String(e.message).replace(/^Error invoking remote method '[\w-]+': (Error: )?/, ''));
  } finally { $('edSave').disabled = false; }
});

// ---------- start ----------
(async () => {
  const init = await ipcRenderer.invoke('init');
  S = init.settings; flags = init.flags; captures = init.captures; currentServer = init.server; watchStatus = init.watchStatus;
  renderSettings(); renderFlags(); renderCaptures(); renderWatch(watchStatus);
  $('folderLink').textContent = init.folder;
  await loadDevices();
  if (S.replayOn) startReplay();
  pushState();
})();
navigator.mediaDevices.addEventListener('devicechange', loadDevices);
