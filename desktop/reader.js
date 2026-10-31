// The reader: loads chapters, speaks them, and shows the text with highlighting.
// Runs in the (sometimes hidden) reading window, so it keeps going in the background.
const { ipcRenderer } = require('electron');
const { parseChapter, chunks } = require('./extract.js');
const nodeCrypto = require('crypto');

// Online sync: continue on your phone where you stopped on the PC, and the other way round
const SYNC_DB = 'https://chapter-reader-default-rtdb.europe-west1.firebasedatabase.app';
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
  mangaGen++;
  seenEdgeText = new Map();
  pageUrls.forEach((u) => URL.revokeObjectURL(u));
  pageUrls = [];
  bubbles = [];
  if (ch.images && ch.images.length) {
    st.idx = 0;
    pendingStart = -1;
    renderManga(ch);
    startManga(ch);
    return;
  }
  mangaDone = true; waitingText = false; pendingStart = -1;
  $('caption').classList.remove('show');
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
    if (isManga()) {
      pendingStart = startPara > 0 ? startPara : -1;
    } else if (startPara > 0) {
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
  if (!st.chapter || st.loading) return;
  if (!st.items.length && !isManga()) return;
  if (st.idx >= st.items.length && (!isManga() || mangaDone)) st.idx = 0;
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
  const newBubble = isManga() && item.p !== current;
  highlight(item.p);
  push();
  const clipJob = clip(i);
  // Manga: glide down through the artwork to the next speech bubble before reading it
  if (newBubble) {
    await glideToBubble(item.p, my);
    if (my !== token || !st.playing) return;
  }
  let c;
  try {
    c = await clipJob;
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
  // Manga: the pictures are still being read, so wait for more speech bubbles
  if (isManga() && (!mangaDone || pendingStart >= 0)) {
    waitingText = true;
    setStatus('Reading the pictures\u2026');
    return;
  }
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

// ---------- manga / webtoon ----------
// Page images are downloaded and read one by one; speech bubbles become the
// "paragraphs", so reading starts while later pages are still loading.

let mangaGen = 0;
let mangaDone = true;
let waitingText = false;
let pendingStart = -1;
let bubbles = [];
let pageUrls = [];
let pageEls = [];
const isManga = () => !!(st.chapter && st.chapter.images && st.chapter.images.length);

function renderManga(ch) {
  text.innerHTML = '';
  paraEls = [];
  current = -1;
  pageEls = [];
  if (ch.heading) {
    const h = document.createElement('h2');
    h.textContent = ch.heading;
    text.appendChild(h);
  }
  ch.images.forEach(() => {
    const d = document.createElement('div');
    d.className = 'pg';
    d.innerHTML = '<div class="wait">Loading page\u2026</div>';
    text.appendChild(d);
    pageEls.push(d);
  });
  $('caption').textContent = 'Reading the pictures\u2026';
  $('caption').classList.add('show');
  text.scrollTop = 0;
}

async function startManga(ch) {
  const gen = mangaGen;
  mangaDone = false;
  waitingText = false;
  // Download and show the pictures on their own (a few at a time), ahead of the text reading
  const pages = new Array(ch.images.length);
  let nextDownload = 0;
  const downloadOne = async () => {
    while (nextDownload < ch.images.length && gen === mangaGen) {
      const i = nextDownload++;
      pages[i] = ipcRenderer.invoke('image', { url: ch.images[i], referer: ch.url })
        .then((r) => {
          const blob = new Blob([r.data], { type: r.mime });
          if (gen !== mangaGen) return null;
          const url = URL.createObjectURL(blob);
          pageUrls[i] = url;
          const d = pageEls[i];
          if (d) { const w = d.querySelector('.wait'); if (w) w.remove(); const im = document.createElement('img'); im.src = url; d.insertBefore(im, d.firstChild); }
          push();
          return blob;
        })
        .catch(() => {
          const d = pageEls[i];
          if (d) { const w = d.querySelector('.wait'); if (w) w.textContent = "This picture couldn't be loaded"; }
          return null;
        });
      await pages[i];
    }
  };
  for (let k = 0; k < 4; k++) downloadOne();

  let ocrError = '';
  for (let i = 0; i < ch.images.length; i++) {
    if (gen !== mangaGen) return;
    while (!pages[i] && gen === mangaGen) await new Promise((r) => setTimeout(r, 100));
    const blob = await pages[i];
    if (gen !== mangaGen) return;
    if (!blob) continue;
    let found = [];
    try {
      found = await readPage(blob, i);
    } catch (e) {
      const msg = String(e.message || e).replace(/^Error invoking remote method '\w+': (Error: )?/, '');
      console.warn('OCR failed', msg);
      if (msg !== ocrError) { ocrError = msg; setStatus('Text recognition problem: ' + msg); }
    }
    if (gen !== mangaGen) return;
    addBubbles(found);
    push();
  }
  if (gen !== mangaGen) return;
  mangaDone = true;
  if (pendingStart >= 0) pendingStart = -1;
  if (!bubbles.length) setStatus('No text found in these pictures');
  if (waitingText) { waitingText = false; if (bubbles.length) setStatus(''); if (st.playing) speak(); }
  push();
}

/** Reads the text in one page, slicing tall webtoon strips so small text stays readable. */
async function readPage(blob, imgIndex) {
  const bmp = await createImageBitmap(blob);
  const W = bmp.width, H = bmp.height;
  // Windows text recognition handles pictures up to about 2600px on a side
  // Small pictures are enlarged (up to 2x) so small lettering can still be read
  const scale = Math.min(2, 1400 / W);
  const tileH = Math.min(H, Math.floor(2400 / scale));
  const overlap = Math.min(Math.floor(tileH / 4), Math.floor(300 / scale));
  const found = [];
  for (let top = 0; top < H;) {
    const bottom = Math.min(H, top + tileH);
    const cw = Math.round(W * scale), ch = Math.round((bottom - top) * scale);
    const canvas = new OffscreenCanvas(cw, ch);
    const ctx = canvas.getContext('2d');
    ctx.drawImage(bmp, 0, top, W, bottom - top, 0, 0, cw, ch);
    const png = await canvas.convertToBlob({ type: 'image/png' });
    const res = await ipcRenderer.invoke('ocr', new Uint8Array(await png.arrayBuffer()));
    for (const line of res) for (const b of lineSegments(line)) {
      found.push({ l: b.l / scale, t: b.t / scale + top, r: b.r / scale, b: b.b / scale + top, text: b.text, lineH: Math.max(1, b.lineH / scale) });
    }
    if (bottom >= H) break;
    top = bottom - overlap;
  }
  bmp.close();
  // Remove text seen twice where slices overlap
  const share = (a, b) => {
    const iw = Math.min(a.r, b.r) - Math.max(a.l, b.l), ih = Math.min(a.b, b.b) - Math.max(a.t, b.t);
    if (iw <= 0 || ih <= 0) return 0;
    return (iw * ih) / Math.max(1, Math.min((a.r - a.l) * (a.b - a.t), (b.r - b.l) * (b.b - b.t)));
  };
  const unique = [];
  for (const b of found.sort((x, y) => y.text.length - x.text.length)) if (!unique.some((u) => share(u, b) > 0.5)) unique.push(b);
  // Join pieces of the same bubble that were read separately
  for (let merged = true; merged;) {
    merged = false;
    outer: for (let i = 0; i < unique.length; i++) for (let j = 0; j < unique.length; j++) {
      if (i === j) continue;
      const a = unique[i], c = unique[j];
      const gap = c.t - a.b, lh = Math.max(a.lineH, c.lineH);
      const horiz = Math.min(a.r, c.r) - Math.max(a.l, c.l);
      if (gap >= -lh && gap <= lh * 0.7 && horiz > Math.min(a.r - a.l, c.r - c.l) / 3 && c.t >= a.t) {
        a.text += ' ' + c.text; a.l = Math.min(a.l, c.l); a.r = Math.max(a.r, c.r); a.b = Math.max(a.b, c.b);
        unique.splice(j, 1); merged = true; break outer;
      }
    }
  }
  // Reading order: rows top to bottom, left to right within a row
  unique.sort((a, c) => {
    const sameRow = Math.min(a.b, c.b) - Math.max(a.t, c.t) > Math.min(a.b - a.t, c.b - c.t) * 0.4;
    return sameRow ? a.l - c.l : a.t - c.t;
  });
  return unique.map((b) => {
    const t = tidy(b.text);
    return t ? { img: imgIndex, x: b.l / W, y: b.t / H, w: (b.r - b.l) / W, h: (b.b - b.t) / H, text: t } : null;
  }).filter(Boolean);
}

// A word that is (part of) a site stamp such as "I ♥ MANGA"
const MARK_WORD = /^(?:i[vy]?|i?love|i?luv)?(?:manga|manhwa|manhua|webtoons?)$/;
const MARK_LEAD = /^(?:|i|1|l|3|lt3|v|y|love|luv|heart)$/;

/**
 * Splits one recognised line where there's a wide gap between words (e.g. the end of a
 * speech bubble and a stamp beside it), and drops watermark words, so the rest of the
 * line still joins up with its own bubble.
 */
function lineSegments(line) {
  const words = (line.words && line.words.length) ? line.words.filter((w) => w && w.text)
    : [{ text: line.text, l: line.l, t: line.t, r: line.r, b: line.b }];
  if (!words.length) return [];
  const H = Math.max(1, line.b - line.t);
  const keys = words.map((w) => norm(w.text));
  const keep = words.map(() => true);
  for (let k = 0; k < words.length; k++) {
    if (!MARK_WORD.test(keys[k])) continue;
    keep[k] = false;
    for (let p = k - 1; p >= Math.max(0, k - 2) && MARK_LEAD.test(keys[p]); p--) keep[p] = false;
  }
  const learned = learnedMarks();
  if (learned.size) {
    for (let i = 0; i < words.length; i++) {
      let acc = '', end = -1;
      for (let j = i; j < Math.min(words.length, i + 6); j++) {
        acc += keys[j];
        if (acc.length > 30) break;
        if (acc.length >= 4 && learned.has(acc)) end = j;
      }
      for (let j = i; j <= end; j++) keep[j] = false;
    }
  }
  if (keep.some((k) => !k)) learnMark(words.filter((_, i) => !keep[i]).map((w) => w.text).join(' '));
  // Group words into pieces separated by wide gaps, then leave out the watermark words
  const pieces = [];
  let cur = [];
  words.forEach((w, i) => {
    if (cur.length && w.l - words[i - 1].r > H * 1.5) { pieces.push(cur); cur = []; }
    cur.push(i);
  });
  pieces.push(cur);
  const out = [];
  for (const piece of pieces) {
    const ws = piece.filter((i) => keep[i]).map((i) => words[i]);
    if (!ws.length) continue;
    out.push({
      l: Math.min(...ws.map((w) => w.l)), t: Math.min(...ws.map((w) => w.t)),
      r: Math.max(...ws.map((w) => w.r)), b: Math.max(...ws.map((w) => w.b)),
      text: ws.map((w) => w.text).join(' '), lineH: H,
    });
  }
  return out;
}

/** Fixes letters misread as digits in comic lettering: "1 HEARD" -> "I HEARD", "N0" -> "NO". */
function fixDigits(t) {
  const tokens = t.split(/(\s+)/);
  const isWord = (w) => !!w && /\p{L}{2,}/u.test(w) && !/\d/.test(w);
  const map = { '0': 'O', '1': 'I', '5': 'S', '8': 'B', '|': 'I' };
  const words = tokens.map((x, i) => (i % 2 === 0 ? x : null));
  for (let i = 0; i < tokens.length; i += 2) {
    const tok = tokens[i];
    if (!tok) continue;
    const m = tok.match(/^([^\p{L}\p{N}|]*)(.*?)([^\p{L}\p{N}|]*)$/u);
    let [, pre, core, post] = m;
    if (!core) continue;
    const next = words[i + 2], prev = words[i - 2];
    // A lone 1 or | used as the word "I" (also "1'm", "1'll")
    if (/^[1|l](['’](m|ll|d|ve))?$/i.test(core) && (isWord(next) || /^['’]/.test(core.slice(1)))) {
      if (!(core === 'l' && !isWord(next))) core = 'I' + core.slice(1);
    } else if (core === '0' && (isWord(next) || isWord(prev))) {
      core = 'O';
    } else if (/\p{L}/u.test(core) && /[0158|]/.test(core) &&
               !(/^\d+(st|nd|rd|th|s|am|pm|k|m|kg|km|x|h)$/i.test(core) && !/^[01](h|k|x|m|s)$/i.test(core))) {
      // Digits mixed into a word are letters that were misread
      core = core.replace(/[0158|]/g, (d) => map[d]);
    }
    tokens[i] = pre + core + post;
  }
  return tokens.join('');
}

/** Cleans recognised text, or returns null for scraps and site watermarks. */
function tidy(raw) {
  let t = fixDigits(String(raw || '').replace(/\s+/g, ' ').trim());
  t = t.replace(/(\p{L})- (\p{L})/gu, '$1$2');
  if (/https?:\/\/|www\.|\.(com|net|org|io|gg|me)\b|discord|patreon|ko-fi/i.test(t)) return null;
  const letters = (t.match(/\p{L}/gu) || []).length;
  if (letters < 2 || letters < t.length / 3) return null;
  const upper = (t.match(/\p{Lu}/gu) || []).length;
  if (upper > letters * 0.7) {
    t = t.toLowerCase().replace(/(^|[.!?]\s+)(\p{Ll})/gu, (_, a, b) => a + b.toUpperCase());
    t = t.replace(/\bi\b/g, 'I').replace(/\bi'/g, "I'");
  }
  return t;
}

// Site watermarks stamped on the pictures (e.g. "I ♥ MANGA") shouldn't be read out
const WATERMARK_WORDS = /^(i\s*\S?\s*)?(love|luv|heart|<3|\u2665|\u2764|\u2661)?\s*(manga|manhwa|manhua|webtoons?|comics?|toon\w*|scans?)\s*(club|zone|world|online|hub|land)?$/i;
// "I ♥ MANGA" style stamps that got read together with a speech bubble
const INLINE_MARK = /\bi\s*(?:[^\p{L}\p{N}\s]{1,3}|<3|v|y|love|luv|heart)?\s*(?:manga|manhwa|manhua|webtoons?)(?:\s*(?:club|zone|world|online|hub|land))?\b/giu;
let seenEdgeText = new Map();   // short text near a page edge -> where it was seen
const norm = (t) => String(t).toLowerCase().replace(/[^a-z0-9]/g, '');
const learnedMarks = () => new Set(settings.learnedMarks || []);

/** Remembers a watermark so it's never read out again. */
function learnMark(text) {
  const key = norm(text);
  const set = learnedMarks();
  if (key.length < 4 || key.length > 30 || set.has(key)) return;
  const list = [...set, key].slice(-300);
  settings.learnedMarks = list;
  ipcRenderer.send('settings-set', { learnedMarks: list });
  showMarks();
}

/** Removes watermark words from inside a bubble's text, keeping the words around them. */
function stripMarks(text) {
  let t = text.replace(INLINE_MARK, (m) => { learnMark(m); return ' '; });
  const set = learnedMarks();
  if (set.size) {
    const words = t.split(/\s+/).filter(Boolean), keys = words.map(norm), out = [];
    for (let i = 0; i < words.length;) {
      let skip = 0;
      if (keys[i]) {
        let acc = '';
        for (let j = i; j < Math.min(words.length, i + 6); j++) {
          acc += keys[j];
          if (acc.length > 30) break;
          if (acc.length >= 4 && set.has(acc)) skip = j - i + 1;
        }
      }
      if (skip) i += skip; else out.push(words[i++]);
    }
    t = out.join(' ');
  }
  return t.replace(/\s+/g, ' ').replace(/^[^\p{L}\p{N}]+(?=\p{L})/u, '').trim();
}

function isWatermark(b) {
  const key = norm(b.text);
  if (!key || (b.text.match(/\p{L}/gu) || []).length < 2) return true;
  if (WATERMARK_WORDS.test(b.text.replace(/[^\p{L}\p{N}<♥❤♡ ]/gu, '').trim())) { learnMark(b.text); return true; }
  if (key.length > 25) return false;
  const nearEdge = b.y < 0.12 || b.y + b.h > 0.88 || b.x > 0.6 || b.x + b.w < 0.4;
  if (!nearEdge) return false;
  if (!seenEdgeText.has(key)) seenEdgeText.set(key, []);
  const seen = seenEdgeText.get(key);
  const same = seen.filter((s) => s.img !== b.img && Math.abs(s.x - b.x) < 0.08 && Math.abs(s.y - b.y) < 0.08);
  if (seen.length < 20) seen.push(b);
  // A stamp repeats in the same spot on other pages; ordinary repeated lines ("What?") don't
  if (!same.length) return false;
  if (new Set(same.map((s) => s.img)).size >= 2) learnMark(b.text);   // seen on 3+ pages: remember it
  return true;
}

function showMarks() {
  const n = (settings.learnedMarks || []).length;
  const btn = $('forgetMarks');
  if (btn) { btn.textContent = n ? 'Forget ' + n + ' learned watermark' + (n === 1 ? '' : 's') : 'No watermarks learned yet'; btn.disabled = !n; }
}

function addItems(pi, t) {
  let cursor = 0;
  for (const c of chunks(t)) {
    let at = t.indexOf(c, cursor);
    if (at < 0) at = t.indexOf(c.slice(0, 20), cursor);
    st.items.push({ p: pi, text: c, offset: at });
    if (at >= 0) cursor = at + c.length;
  }
}

/** Joins a bubble cut in half by the site's picture slices, if its first half hasn't been read yet. */
function joinSplit(b) {
  const ch = st.chapter, pi = bubbles.length - 1, prev = bubbles[pi];
  if (!prev || prev.img !== b.img - 1 || prev.y + prev.h < 0.94 || b.y > 0.06) return false;
  if (Math.min(prev.x + prev.w, b.x + b.w) - Math.max(prev.x, b.x) <= 0) return false;
  const first = st.items.findIndex((it) => it.p === pi);
  if (first < 0 || st.idx >= first || st.items.slice(first).some((it) => it.p !== pi)) return false;
  st.items.length = first;
  for (const k of [...clips.keys()]) if (k >= first) clips.delete(k);
  prev.text = prev.text + ' ' + b.text;
  ch.paras[pi] = prev.text;
  const o = text.querySelector('.bub[data-i="' + pi + '"]');
  if (o) o.title = prev.text;
  addItems(pi, prev.text);
  return true;
}

function addBubbles(found) {
  const ch = st.chapter;
  found = found.map((b) => ({ ...b, text: stripMarks(b.text) })).filter((b) => !isWatermark(b));
  for (const [n, b] of found.entries()) {
    if (n === 0 && joinSplit(b)) continue;
    const pi = ch.paras.length;
    ch.paras.push(b.text);
    bubbles.push(b);
    const d = pageEls[b.img];
    if (d) {
      const o = document.createElement('div');
      o.className = 'bub';
      o.style.left = (b.x * 100) + '%'; o.style.top = (b.y * 100) + '%';
      o.style.width = (b.w * 100) + '%'; o.style.height = (b.h * 100) + '%';
      o.dataset.i = pi;
      o.title = b.text;
      o.addEventListener('click', () => startAt(pi));
      d.appendChild(o);
    }
    addItems(pi, b.text);
  }
  if (pendingStart >= 0) {
    const i = st.items.findIndex((it) => it.p >= pendingStart);
    if (i >= 0) { st.idx = i; pendingStart = -1; }
  }
  if (waitingText && pendingStart < 0 && st.idx < st.items.length) {
    waitingText = false;
    setStatus('');
    if (st.playing) speak();
  }
}

function highlightBubble(i) {
  const old = text.querySelector('.bub.cur');
  if (old) old.classList.remove('cur');
  current = i;
  $('caption').textContent = (st.chapter.paras[i]) || (mangaDone ? '' : 'Reading the pictures\u2026');
  const o = text.querySelector('.bub[data-i="' + i + '"]');
  if (o) {
    o.classList.add('cur');
    // While reading, speak() glides here; otherwise (paused, tapped, Follow) move there quickly
    if (following() && !st.playing) glideToBubble(i, token, 400);
  }
  saveProgress();
}

/**
 * Scrolls smoothly so speech bubble [i] sits a third of the way down the view.
 * Long stretches of artwork scroll at the chosen "artwork pace" (seconds per screen),
 * so there's time to look at the pictures. Resolves when the scroll is done.
 */
function glideToBubble(i, my, fixedMs) {
  return new Promise((resolve) => {
    const o = text.querySelector('.bub[data-i="' + i + '"]');
    if (!o || !following()) { resolve(); return; }
    const box = text.getBoundingClientRect();
    const start = text.scrollTop;
    const target = start + o.getBoundingClientRect().top - (box.top + box.height / 3);
    const dist = target - start;
    if (Math.abs(dist) < 4) { resolve(); return; }
    const pace = Number(settings.artPace ?? 1);
    let ms;
    if (fixedMs) ms = fixedMs;
    else if (!pace || dist < 0) ms = 350;
    else if (dist < box.height * 0.5) ms = 600;
    // A steady speed: the same time for every screen of artwork, however long the gap
    else ms = Math.min(120000, (dist / box.height) * pace * 1000);
    const t0 = performance.now();
    text.style.scrollBehavior = 'auto';
    const ease = (k) => (k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2);
    const step = (now) => {
      // Stop if playback was paused/changed, or you started scrolling yourself
      if (my !== token || !following()) { text.style.scrollBehavior = ''; resolve(); return; }
      const k = Math.min(1, (now - t0) / ms);
      text.scrollTop = start + dist * (pace && ms > 1000 ? k : ease(k));
      if (k < 1) requestAnimationFrame(step);
      else { text.style.scrollBehavior = ''; resolve(); }
    };
    requestAnimationFrame(step);
  });
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
  if (isManga()) { highlightBubble(i); return; }
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
  if (isManga()) return;
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
  if (isManga()) {
    const n = bubbles.length;
    const loaded = pageUrls.filter(Boolean).length;
    const cur = Math.min(n, ((st.items[st.idx] || {}).p ?? n - 1) + 1);
    return (n ? `Bubble ${Math.max(1, cur)} of ${n}` : 'No speech bubbles yet') +
      (mangaDone ? '' : ` \u00b7 reading page ${loaded} of ${st.chapter.images.length}`);
  }
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
  if (isManga()) { glideToBubble(current, token, 500); return; }
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
function showPace() {
  const v = Number($('artPace').value);
  $('artPaceLabel').textContent = v === 0 ? 'Off (jump to each bubble)' : v.toFixed(1) + ' s per screen';
}
$('artPace').addEventListener('input', () => {
  showPace();
  settings.artPace = Number($('artPace').value);
});
$('artPace').addEventListener('change', () => {
  ipcRenderer.send('settings-set', { artPace: settings.artPace });
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
ipcRenderer.on('settings', (_e, s) => { Object.assign(settings, s); showMarks(); push(); });
$('forgetMarks').addEventListener('click', () => {
  settings.learnedMarks = [];
  ipcRenderer.send('settings-set', { learnedMarks: [] });
  showMarks();
});

ipcRenderer.invoke('settings-get').then((s) => {
  Object.assign(settings, s);
  $('engine').value = settings.engine;
  $('edgeVoice').value = settings.edgeVoice;
  $('auto').checked = settings.auto !== false;
  document.documentElement.style.setProperty('--text-size', (settings.textSize || 19) + 'px');
  $('artPace').value = String(settings.artPace ?? 1);
  showPace();
  showMarks();
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
