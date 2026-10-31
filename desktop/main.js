// Chapter Reader for PC: a floating bubble that reads web novel chapters aloud.
const {
  app, BrowserWindow, ipcMain, screen, Tray, Menu, nativeImage, globalShortcut, clipboard,
} = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFile, spawn } = require('child_process');
const edgeTts = require('./edge-tts');

if (!app.requestSingleInstanceLock()) {
  app.quit();
}

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0';
const BUBBLE = 72;      // bubble window size with the menu closed
const MENU_W = 312;
const MENU_H = 300;

let bubble = null;
let reader = null;
let tray = null;
let quitting = false;
let menuOpen = false;
let dragStart = null;

// ---------- settings ----------

const settingsFile = () => path.join(app.getPath('userData'), 'settings.json');
let settings = {
  engine: 'piper',                 // 'piper' (Cori, offline) or 'edge' (Microsoft Natural, online)
  edgeVoice: 'en-GB-SoniaNeural',
  rate: 1,
  auto: true,
  textSize: 19,
  bubbleX: null,
  bubbleY: null,
  bubbleHidden: false,
};

function loadSettings() {
  try { Object.assign(settings, JSON.parse(fs.readFileSync(settingsFile(), 'utf8'))); } catch (_) {}
}
function saveSettings() {
  try { fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 2)); } catch (_) {}
}

function res(...p) {
  return app.isPackaged ? path.join(process.resourcesPath, ...p) : path.join(__dirname, ...p);
}

// ---------- windows ----------

function createBubble() {
  const wa = screen.getPrimaryDisplay().workArea;
  let x = settings.bubbleX ?? wa.x + wa.width - BUBBLE - 24;
  let y = settings.bubbleY ?? wa.y + wa.height - BUBBLE - 120;
  bubble = new BrowserWindow({
    width: BUBBLE, height: BUBBLE, x, y,
    frame: false, transparent: true, resizable: false, maximizable: false,
    alwaysOnTop: true, skipTaskbar: true, focusable: false, hasShadow: false, show: false,
    webPreferences: { nodeIntegration: true, contextIsolation: false },
  });
  bubble.setAlwaysOnTop(true, 'screen-saver');
  bubble.loadFile('bubble.html');
  bubble.once('ready-to-show', () => { if (!settings.bubbleHidden) bubble.showInactive(); });
  keepOnScreen();
}

function createReader() {
  reader = new BrowserWindow({
    width: 580, height: 860, show: false,
    title: 'Chapter Reader',
    backgroundColor: '#121016',
    autoHideMenuBar: true,
    webPreferences: { nodeIntegration: true, contextIsolation: false, backgroundThrottling: false },
  });
  reader.loadFile('reader.html');
  reader.on('close', (e) => {
    if (!quitting) { e.preventDefault(); reader.hide(); }
  });
}

/** Keeps the bubble (and its menu) inside the screen. */
function keepOnScreen() {
  if (!bubble) return;
  const [x, y] = bubble.getPosition();
  const [w, h] = bubble.getSize();
  const wa = screen.getDisplayNearestPoint({ x, y }).workArea;
  const nx = Math.min(Math.max(x, wa.x), wa.x + wa.width - w);
  const ny = Math.min(Math.max(y, wa.y), wa.y + wa.height - h);
  if (nx !== x || ny !== y) bubble.setPosition(nx, ny);
}

function setMenu(open) {
  if (!bubble) return;
  menuOpen = open;
  const [x, y] = bubble.getPosition();
  if (open) {
    bubble.setBounds({ x, y, width: MENU_W, height: MENU_H });
  } else {
    bubble.setBounds({ x, y, width: BUBBLE, height: BUBBLE });
  }
  keepOnScreen();
  bubble.webContents.send('menu', open);
}

function showBubble() {
  settings.bubbleHidden = false; saveSettings();
  if (!bubble) createBubble(); else bubble.showInactive();
}

function hideBubble() {
  setMenu(false);
  settings.bubbleHidden = true; saveSettings();
  bubble?.hide();
}

function openReader() {
  setMenu(false);
  reader.show();
  reader.focus();
}

// ---------- reading the browser's address ----------

function toUrl(s) {
  s = (s || '').trim();
  if (!s || /\s/.test(s)) return null;
  if (/^https?:\/\//i.test(s)) return s;
  if (s.includes('.')) return 'https://' + s;
  return null;
}

function browserUrl() {
  return new Promise((resolve) => {
    execFile('powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', res('browser-url.ps1')],
      { windowsHide: true, timeout: 20000 },
      (_err, stdout) => resolve(toUrl(String(stdout || '').split(/\r?\n/)[0])));
  });
}

// ---------- voices ----------

let piperChain = Promise.resolve();

function piperSynth(text, rate) {
  const run = () => new Promise((resolve, reject) => {
    const exe = res('piper', 'piper.exe');
    const model = res('voices', 'en_GB-cori-high.onnx');
    const out = path.join(os.tmpdir(), `chapter-reader-${Date.now()}-${Math.random().toString(36).slice(2)}.wav`);
    const lengthScale = (1 / Math.max(0.5, Math.min(rate, 4))).toFixed(3);
    const p = spawn(exe, ['--model', model, '--output_file', out, '--length_scale', lengthScale, '--sentence_silence', '0.1'], {
      cwd: path.dirname(exe), windowsHide: true,
    });
    let err = '';
    p.stderr.on('data', (d) => { err += d; });
    p.on('error', (e) => reject(new Error('Could not start the Cori voice: ' + e.message)));
    p.on('close', () => {
      try {
        const audio = fs.readFileSync(out);
        fs.unlink(out, () => {});
        resolve({ audio, mime: 'audio/wav', words: null });
      } catch (_) {
        reject(new Error('The Cori voice failed. ' + err.slice(-200)));
      }
    });
    p.stdin.write(text.replace(/\r?\n/g, ' '));
    p.stdin.end();
  });
  // One sentence at a time, so prepared sentences don't fight over the CPU
  const job = piperChain.then(run, run);
  piperChain = job.catch(() => {});
  return job;
}

// ---------- messages from the windows ----------

function send(win, channel, data) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, data);
}

async function command(c) {
  switch (c.type) {
    case 'readBrowser': {
      setMenu(false);
      send(reader, 'status', 'Finding your page…');
      const url = await browserUrl();
      if (url) send(reader, 'cmd', { type: 'load', url, play: true });
      else {
        send(reader, 'status', "Couldn't see a page in Edge or Chrome. Open a chapter there first, or copy its link and use Paste link.");
        openReader();
      }
      break;
    }
    case 'paste': {
      setMenu(false);
      const url = toUrl(clipboard.readText());
      if (url) send(reader, 'cmd', { type: 'load', url, play: true });
      else { send(reader, 'status', 'Copy a chapter link first (Ctrl+L, Ctrl+C in your browser), then Paste link.'); openReader(); }
      break;
    }
    case 'openReader': openReader(); break;
    case 'hideBubble': hideBubble(); break;
    case 'menu': setMenu(c.open ?? !menuOpen); break;
    case 'quit': quitting = true; app.quit(); break;
    default: send(reader, 'cmd', c);
  }
}

ipcMain.on('cmd', (_e, c) => command(c));
ipcMain.on('state', (_e, st) => {
  send(bubble, 'state', st);
  if (tray) tray.setToolTip('Chapter Reader' + (st.title ? ' – ' + st.title : ''));
});
ipcMain.handle('settings-get', () => settings);
ipcMain.on('settings-set', (_e, patch) => {
  Object.assign(settings, patch); saveSettings();
  send(reader, 'settings', settings);
  send(bubble, 'settings', settings);
});
ipcMain.handle('fetch', async (_e, url) => {
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9' }, redirect: 'follow' });
  if (!r.ok) throw new Error('the site answered ' + r.status);
  return { html: await r.text(), url: r.url };
});
// Manga: download a page image (sites often only allow it when it looks like it came from their page)
ipcMain.handle('image', async (_e, { url, referer }) => {
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': referer, 'Accept': 'image/avif,image/webp,image/*,*/*;q=0.8' }, redirect: 'follow' });
  if (!r.ok) throw new Error('image ' + r.status);
  return { data: Buffer.from(await r.arrayBuffer()), mime: r.headers.get('content-type') || 'image/jpeg' };
});

// Manga: read the text in a slice of a page (offline text recognition)
let ocrWorker = null;
let ocrStarting = null;

/** Files packed into the app can't be started as background workers, so use the unpacked copy. */
function unpacked(p) {
  return p.replace(/app\.asar([\\/])/, 'app.asar.unpacked$1');
}

function withTimeout(promise, ms, what) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(what + ' took too long')), ms)),
  ]);
}

async function getOcr() {
  if (ocrWorker) return ocrWorker;
  if (ocrStarting) return ocrStarting;
  const { createWorker } = require('tesseract.js');
  const workerPath = unpacked(path.join(path.dirname(require.resolve('tesseract.js')), 'worker-script', 'node', 'index.js'));
  ocrStarting = withTimeout(createWorker('eng', 1, {
    workerPath,
    langPath: res('tessdata'),
    cachePath: path.join(app.getPath('userData'), 'tessdata-cache'),
    gzip: true,
    errorHandler: (e) => console.error('OCR', e),
  }), 60000, 'Starting text recognition').then((w) => { ocrWorker = w; return w; })
    .catch((e) => { ocrStarting = null; throw e; });
  return ocrStarting;
}
ipcMain.handle('ocr', async (_e, png) => {
  const worker = await getOcr();
  const { data } = await withTimeout(worker.recognize(Buffer.from(png), {}, { blocks: true, text: false }), 45000, 'Reading a page');
  const out = [];
  for (const b of data.blocks || []) {
    for (const p of b.paragraphs || []) {
      if ((p.confidence || 0) < 45) continue;
      const lines = p.lines || [];
      const lineH = lines.length ? (p.bbox.y1 - p.bbox.y0) / lines.length : (p.bbox.y1 - p.bbox.y0);
      out.push({ l: p.bbox.x0, t: p.bbox.y0, r: p.bbox.x1, b: p.bbox.y1, text: p.text, lineH });
    }
  }
  return out;
});

ipcMain.handle('synth', (_e, { text, engine, voice, rate }) =>
  engine === 'edge' ? edgeTts.synth(text, voice, rate) : piperSynth(text, rate));

// Dragging the bubble
ipcMain.on('drag-start', () => { if (bubble) dragStart = bubble.getPosition(); });
ipcMain.on('drag', (_e, { dx, dy }) => {
  if (bubble && dragStart) bubble.setPosition(Math.round(dragStart[0] + dx), Math.round(dragStart[1] + dy));
});
ipcMain.on('drag-end', () => {
  dragStart = null;
  keepOnScreen();
  if (bubble) { const [x, y] = bubble.getPosition(); settings.bubbleX = x; settings.bubbleY = y; saveSettings(); }
});

// ---------- tray icon and shortcuts ----------

function createTray() {
  const icon = nativeImage.createFromPath(path.join(__dirname, 'tray.png'));
  tray = new Tray(icon);
  tray.setToolTip('Chapter Reader');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Show bubble', click: showBubble },
    { label: 'Read the page in my browser', click: () => command({ type: 'readBrowser' }) },
    { label: 'Open reading view', click: openReader },
    { type: 'separator' },
    { label: 'Quit Chapter Reader', click: () => { quitting = true; app.quit(); } },
  ]));
  tray.on('click', showBubble);
}

function registerShortcuts() {
  const keys = {
    'Control+Alt+B': () => (bubble?.isVisible() ? hideBubble() : showBubble()),
    'Control+Alt+Space': () => command({ type: 'toggle' }),
    'Control+Alt+R': () => command({ type: 'readBrowser' }),
    'MediaPlayPause': () => command({ type: 'toggle' }),
    'MediaNextTrack': () => command({ type: 'nextch' }),
    'MediaPreviousTrack': () => command({ type: 'prevch' }),
  };
  for (const [k, fn] of Object.entries(keys)) {
    try { globalShortcut.register(k, fn); } catch (_) {}
  }
}

// ---------- start ----------

app.whenReady().then(() => {
  loadSettings();
  createReader();
  createBubble();
  createTray();
  registerShortcuts();
  screen.on('display-metrics-changed', keepOnScreen);
});

app.on('second-instance', showBubble);
app.on('window-all-closed', () => { /* keep running in the tray */ });
app.on('will-quit', () => globalShortcut.unregisterAll());
app.on('before-quit', () => { quitting = true; });
