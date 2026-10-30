// The reader: loads chapters, speaks them, and shows the text with highlighting.
// Runs in the (sometimes hidden) reading window, so it keeps going in the background.
const { ipcRenderer } = require('electron');
const { parseChapter, chunks } = require('./extract.js');
const nodeCrypto = require('crypto');

// Online sync: continue on your phone where you stopped on the PC, and the other way round
const SYNC_DB = 'SYNC_DATABASE_URL';
const syncOn = () => SYNC_DB.startsWith('https://');

function syncKey() {
  const name = (settings.syncUser || '').trim().toLowerCase().replace(/[^a-z0-9_-]/g, '');
  if (!name) return null;
  const pin = (settings.syncPin || '').trim();
  if (!pin) return name;
  return name + '-' + nodeCrypto.createHash('sha256').update(name + ':' + pin, 'utf8').digest('hex').slice(0, 10);
}
const syncUrl = (k) => SYNC_DB.replace(/\/$/, '') + '/progress/' + k + '.json';

async function syncPush() {
  const k = syncKey(); const r = settings.resume;
  if (!syncOn() || !k || !r || !r.url) return;
  try {
    await fetch(syncUrl(k), {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: r.url, para: r.para || 0, title: r.title || '', updated: r.updated || Date.now(), device: 'pc' }),
    });
  } catch (_) {}
}

/** Takes the online progress if it's newer than this PC's. Returns true if it changed. */
async function syncPull() {
  const k = syncKey();
  if (!syncOn() || !k) return false;
  try {
    const r = await fetch(syncUrl(k));
    const o = await r.json();
    if (!o || !o.url) return false;
    if ((o.updated || 0) <= ((settings.resume && settings.resume.updated) || 0)) return false;
    settings.resume = { url: o.url, para: o.para || 0, title: o.title || '', updated: o.updated };
    ipcRenderer.send('settings-set', { resume: settings.resume });
    return true;
  } catch (_) { return false; }
}
let pushTimer = null;

let settings = { engine: 'piper', edgeVoice: 'en-GB-SoniaNeural', rate: 1, auto: true, textSize: 19 };
const st = { chapter: null, items: [], idx: 0, playing: false, loading: false, status: '' };

let token = 0;              // bumps whenever playback restarts, so stale audio is ignored
let audio = null;
let clips = new Map();      // piece index -> Promise of {url, words}
let wordTimer = null;
let lastUserScroll = 0;

const $ = (id) => document.getElementById(id);
const text = $('text');

// ---------- chapter ----------

function setChapter(ch) {
  for (const c of clips.values()) c.then((x) => URL.revokeObjectURL(x.url)).catch(() => {});
  clips = new Map();
  st.chapter = ch;
  st.items = [];
  ch.paras.forEach((t, p) => {
    let cursor = 0;
    for (const c of chunks(t)) {
      let at = t.indexOf(c, cursor);
      if (at < 0) at = t.indexOf(c.slice(0, 20), cursor);
      st.items.push({ p, text: c, offset: at });
      if (at >= 0) cursor = at + c.length;
    }
  });
  st.idx = 0;
  renderText();
}

async function loadChapter(url, autoplay, startPara = 0) {
  if (!url || st.loading) return;
  const wasPlaying = st.playing || autoplay;
  stopAudio();
  st.playing = false;
  st.loading = true;
  setStatus('Loading chapter…');
  try {
    const page = await ipcRenderer.invoke('fetch', url);
    const ch = parseChapter(page.html, page.url);
    if (!ch) throw new Error('no story text was found on that page');
    setChapter(ch);
    if (startPara > 0) {
      const at = st.items.findIndex((it) => it.p >= startPara);
      if (at >= 0) st.idx = at;
      highlight(st.items[st.idx].p);
    }
    st.loading = false;
    setStatus('');
    if (wasPlaying) play(); else push();
  } catch (e) {
    st.loading = false;
    setStatus("Couldn't read that page: " + String(e.message || e).replace(/^Error invoking remote method '\w+': (Error: )?/, ''));
  }
}

// ---------- speech ----------

function clip(i) {
  if (clips.has(i)) return clips.get(i);
  const item = st.items[i];
  const job = ipcRenderer.invoke('synth', {
    text: item.text, engine: settings.engine, voice: settings.edgeVoice, rate: settings.rate,
  }).then((r) => ({ url: URL.createObjectURL(new Blob([r.audio], { type: r.mime })), words: r.words }));
  clips.set(i, job);
  job.catch(() => clips.delete(i));
  return job;
}

function resetClips() {
  for (const c of clips.values()) c.then((x) => URL.revokeObjectURL(x.url)).catch(() => {});
  clips = new Map();
}

function stopAudio() {
  token++;
  clearInterval(wordTimer);
  if (audio) { audio.onended = null; audio.onerror = null; audio.pause(); audio = null; }
}

async function resume() {
  setStatus('Finding where you left off\u2026');
  await syncPull();
  setStatus('');
  const r = settings.resume;
  if (r && r.url) loadChapter(r.url, true, r.para || 0);
}

function saveProgress() {
  if (!st.chapter) return;
  const p = (st.items[st.idx] || {}).p || 0;
  const title = st.chapter.heading || st.chapter.title;
  settings.resume = { url: st.chapter.url, para: p, title, updated: Date.now() };
  ipcRenderer.send('settings-set', { resume: settings.resume });
  clearTimeout(pushTimer);
  pushTimer = setTimeout(syncPush, 4000);
}

function play() {
  if (!st.chapter && !st.loading) { resume(); return; }
  if (!st.chapter || st.loading || !st.items.length) return;
  if (st.idx >= st.items.length) st.idx = 0;
  st.playing = true;
  speak();
  push();
}

function pause() {
  if (st.chapter) { clearTimeout(pushTimer); syncPush(); }
  st.playing = false;
  stopAudio();
  push();
}

async function speak() {
  stopAudio();
  const my = token;
  if (st.idx >= st.items.length) { finished(); return; }
  const i = st.idx;
  const item = st.items[i];
  highlight(item.p);
  push();
  let c;
  try {
    c = await clip(i);
  } catch (e) {
    if (my !== token) return;
    pause();
    setStatus('Voice problem: ' + String(e.message || e).replace(/^Error invoking remote method '\w+': (Error: )?/, '') +
      (settings.engine === 'edge' ? ' Try the Cori voice in ⚙ settings.' : ''));
    return;
  }
  if (my !== token || !st.playing) return;
  // Prepare the next sentences while this one plays, so there's no gap
  for (let k = 1; k <= 2; k++) if (st.items[i + k]) clip(i + k).catch(() => {});
  audio = new Audio(c.url);
  audio.onended = () => { if (my !== token) return; st.idx = i + 1; speak(); };
  audio.onerror = () => { if (my !== token) return; st.idx = i + 1; speak(); };
  audio.play().catch(() => {});
  wordTimer = setInterval(() => followWord(item, c.words), 70);
}

function finished() {
  const next = st.chapter && st.chapter.next;
  if (settings.auto && next) {
    loadChapter(next, true);
  } else {
    st.playing = false;
    st.idx = 0;
    setStatus('End of chapter');
  }
}

/** Highlights the word being spoken: exact for Microsoft voices, estimated for Cori. */
function followWord(item, words) {
  if (!audio || item.offset < 0) return;
  let s = -1, e = -1;
  if (words && words.length) {
    const ms = audio.currentTime * 1000;
    for (const w of words) { if (w.ms <= ms + 40) { s = w.start; e = w.end; } else break; }
  } else if (audio.duration && isFinite(audio.duration)) {
    const t = item.text;
    const pos = Math.min(t.length - 1, Math.floor((audio.currentTime / audio.duration) * t.length));
    s = t.lastIndexOf(' ', pos) + 1;
    e = t.indexOf(' ', pos); if (e < 0) e = t.length;
  }
  if (s >= 0 && e > s) markWord(item.p, item.offset + s, item.offset + e);
}

function jumpPara(delta) {
  if (!st.items.length) return;
  const cur = (st.items[st.idx] || st.items[0]).p;
  const target = Math.max(0, cur + delta);
  const i = st.items.findIndex((it) => it.p >= target);
  if (i < 0) return;
  st.idx = i;
  if (st.playing) speak(); else { highlight(st.items[i].p); push(); }
}

function startAt(p) {
  const i = st.items.findIndex((it) => it.p >= p);
  if (i < 0) return;
  st.idx = i;
  if (st.playing) speak(); else play();
}

function setRate(r) {
  const rate = Math.round(Math.min(4, Math.max(0.5, r)) * 10) / 10;
  settings.rate = rate;
  ipcRenderer.send('settings-set', { rate });
  resetClips();
  if (st.playing) speak(); else push();
}

// ---------- view ----------

let paraEls = [];
let current = -1;

function renderText() {
  text.innerHTML = '';
  paraEls = [];
  current = -1;
  const ch = st.chapter;
  if (ch.heading) {
    const h = document.createElement('h2');
    h.textContent = ch.heading;
    text.appendChild(h);
  }
  ch.paras.forEach((t, i) => {
    const p = document.createElement('p');
    p.textContent = t;
    p.addEventListener('click', () => startAt(i));
    text.appendChild(p);
    paraEls.push(p);
  });
  text.scrollTop = 0;
}

function following() { return Date.now() - lastUserScroll > 5000; }

function highlight(i) {
  if (i === current) return;
  const old = paraEls[current];
  if (old) { old.classList.remove('cur'); delete old.dataset.ws; old.textContent = st.chapter.paras[current]; }
  current = i;
  const el = paraEls[i];
  if (!el) return;
  el.classList.add('cur');
  if (following()) el.scrollIntoView({ block: 'center' });
  saveProgress();
}

function markWord(p, s, e) {
  if (p !== current) highlight(p);
  const el = paraEls[p];
  if (!el) return;
  const t = st.chapter.paras[p];
  if (el.dataset.ws === String(s)) return;
  el.dataset.ws = String(s);
  el.textContent = '';
  el.append(t.slice(0, s));
  const w = document.createElement('span');
  w.className = 'w';
  w.textContent = t.slice(s, e);
  el.append(w);
  el.append(t.slice(e));
  // Keep the line being read on screen in long paragraphs
  if (following()) {
    const r = w.getBoundingClientRect();
    const box = text.getBoundingClientRect();
    if (r.top < box.top + box.height / 6 || r.bottom > box.bottom - box.height / 3) {
      text.scrollTop += r.top - (box.top + box.height / 3);
    }
  }
}

['wheel', 'touchmove', 'keydown'].forEach((ev) =>
  text.addEventListener(ev, () => { lastUserScroll = Date.now(); }, { passive: true }));
text.addEventListener('mousedown', (e) => { if (e.offsetX > text.clientWidth) lastUserScroll = Date.now(); });

function setStatus(msg) {
  st.status = msg;
  $('status').textContent = msg;
  $('status').classList.toggle('show', !!msg);
  push();
}

function progressText() {
  if (!st.chapter) return '';
  const total = st.chapter.paras.length;
  const p = Math.min(total, ((st.items[st.idx] || {}).p ?? total - 1) + 1);
  const pct = st.items.length ? Math.round((st.idx / st.items.length) * 100) : 0;
  return `Paragraph ${Math.max(1, p)} of ${total} · ${pct}%`;
}

/** Updates this window and tells the bubble what's going on. */
function push() {
  const title = st.chapter ? (st.chapter.heading || st.chapter.title) : 'Chapter Reader';
  $('title').textContent = title;
  $('progress').textContent = progressText();
  $('bar').firstElementChild.style.width = (st.items.length ? (st.idx / st.items.length) * 100 : 0) + '%';
  $('play').innerHTML = st.loading ? '…' : st.playing ? '&#10074;&#10074;' : '&#9654;';
  $('rate').textContent = settings.rate.toFixed(1) + '×';
  document.title = 'Chapter Reader' + (st.chapter ? ' – ' + title : '');
  ipcRenderer.send('state', {
    hasChapter: !!st.chapter, playing: st.playing, loading: st.loading,
    hasResume: !st.chapter && !!(settings.resume && settings.resume.url),
    resumeTitle: settings.resume ? settings.resume.title : '',
    rate: settings.rate, title: st.chapter ? title : '', status: st.status, progress: progressText(),
  });
}

// ---------- controls ----------

function command(c) {
  switch (c.type) {
    case 'toggle': st.playing ? pause() : play(); break;
    case 'nextch': if (st.chapter) loadChapter(st.chapter.next, false); break;
    case 'prevch': if (st.chapter) loadChapter(st.chapter.prev, false); break;
    case 'back': jumpPara(-1); break;
    case 'fwd': jumpPara(1); break;
    case 'faster': setRate(settings.rate + 0.1); break;
    case 'slower': setRate(settings.rate - 0.1); break;
    case 'load': loadChapter(c.url, c.play); break;
  }
}

document.querySelectorAll('footer button[data-cmd]').forEach((b) =>
  b.addEventListener('click', () => command({ type: b.dataset.cmd })));

function setTextSize(n) {
  settings.textSize = Math.min(32, Math.max(13, n));
  document.documentElement.style.setProperty('--text-size', settings.textSize + 'px');
  ipcRenderer.send('settings-set', { textSize: settings.textSize });
}
$('smaller').addEventListener('click', () => setTextSize(settings.textSize - 1));
$('bigger').addEventListener('click', () => setTextSize(settings.textSize + 1));
$('follow').addEventListener('click', () => {
  lastUserScroll = 0;
  const el = paraEls[current];
  if (el) el.scrollIntoView({ block: 'center' });
});
$('gear').addEventListener('click', () => $('settings').classList.toggle('show'));

$('engine').addEventListener('change', (e) => {
  settings.engine = e.target.value;
  ipcRenderer.send('settings-set', { engine: settings.engine });
  resetClips();
  if (st.playing) speak();
});
$('edgeVoice').addEventListener('change', (e) => {
  settings.edgeVoice = e.target.value;
  ipcRenderer.send('settings-set', { edgeVoice: settings.edgeVoice });
  resetClips();
  if (st.playing && settings.engine === 'edge') speak();
});
$('auto').addEventListener('change', (e) => {
  settings.auto = e.target.checked;
  ipcRenderer.send('settings-set', { auto: settings.auto });
});
function readLink() {
  const v = $('link').value.trim();
  if (!v) return;
  const url = /^https?:\/\//i.test(v) ? v : 'https://' + v;
  $('link').value = '';
  loadChapter(url, true);
}
$('go').addEventListener('click', readLink);
$('link').addEventListener('keydown', (e) => { if (e.key === 'Enter') readLink(); });

document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
  if (e.code === 'Space') { e.preventDefault(); command({ type: 'toggle' }); }
  else if (e.key === 'ArrowRight') command({ type: 'fwd' });
  else if (e.key === 'ArrowLeft') command({ type: 'back' });
});

ipcRenderer.on('cmd', (_e, c) => command(c));
ipcRenderer.on('status', (_e, msg) => setStatus(msg));
ipcRenderer.on('settings', (_e, s) => { Object.assign(settings, s); push(); });

ipcRenderer.invoke('settings-get').then((s) => {
  Object.assign(settings, s);
  $('engine').value = settings.engine;
  $('edgeVoice').value = settings.edgeVoice;
  $('auto').checked = settings.auto !== false;
  document.documentElement.style.setProperty('--text-size', (settings.textSize || 19) + 'px');
  $('syncUser').value = settings.syncUser || '';
  $('syncPin').value = settings.syncPin || '';
  if (!syncOn()) $('syncRow').style.display = 'none';
  showResume();
  push();
  syncPull().then((changed) => { if (changed) { showResume(); push(); } });
});

$('syncSave').addEventListener('click', () => {
  settings.syncUser = $('syncUser').value.trim();
  settings.syncPin = $('syncPin').value.trim();
  ipcRenderer.send('settings-set', { syncUser: settings.syncUser, syncPin: settings.syncPin });
  setStatus(settings.syncUser ? 'Syncing as "' + settings.syncUser + '". Use the same name on your phone.' : 'Sync turned off');
  syncPull().then((changed) => { if (changed) { showResume(); push(); } });
  setTimeout(() => { if (st.status.startsWith('Sync')) setStatus(''); }, 5000);
});

/** On the empty screen, offers to carry on from where you left off. */
function showResume() {
  const r = settings.resume;
  const old = document.getElementById('resumeBox');
  if (old) old.remove();
  if (st.chapter || !r || !r.url) return;
  const box = document.createElement('div');
  box.id = 'resumeBox';
  box.className = 'empty';
  box.style.marginTop = '18px';
  const b = document.createElement('button');
  b.textContent = '\u25B6 Continue: ' + (r.title || 'your last chapter');
  b.style.cssText = 'width:100%;min-height:48px;background:var(--accent);font-weight:600';
  b.addEventListener('click', resume);
  box.appendChild(b);
  text.appendChild(box);
}
