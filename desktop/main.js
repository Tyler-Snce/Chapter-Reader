// Chapter Reader for PC: a floating bubble that reads web novel chapters aloud.
const {
  app, BrowserWindow, ipcMain, screen, Tray, Menu, nativeImage, globalShortcut, clipboard, Notification, session,
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
  engine: 'edge',                  // 'piper' (Cori, offline) or 'edge' (Microsoft Natural, online)
  edgeVoice: 'en-US-AvaMultilingualNeural',
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

// New chapter alerts: a Windows notification; clicking it carries on with that series
const alerts = [];
ipcMain.on('alert', (_e, { title, body, url, para }) => {
  if (!Notification.isSupported()) return;
  const n = new Notification({ title, body, icon: path.join(__dirname, 'icon.png') });
  n.on('click', () => { openReader(); send(reader, 'open-save', { url, para }); });
  alerts.push(n);   // keep it alive until clicked
  if (alerts.length > 20) alerts.shift();
  n.show();
});

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
      // With the helper, take the page straight from your browser (no download, no site checks)
      if (helperOn()) {
        try {
          const page = await helperAsk({ type: 'current' }, 35000);
          handedOver.set(page.url, { html: page.html, url: page.url, t: Date.now() });
          send(reader, 'cmd', { type: 'load', url: page.url, play: true });
          break;
        } catch (e) {
          send(reader, 'status', 'Browser helper: ' + e.message);
        }
      }
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
// ---------- browser helper (extension in Edge/Chrome) ----------
// The helper hands over the page you're reading straight from your own browser, and loads next
// chapters in your tab, so site checks, logins and ads behave exactly as they do for you.
const { WebSocketServer } = require('ws');
const HELPER_PORT = 47321;
let helperSock = null;
const helperWaiting = new Map();
let helperNext = 1;

function startHelperServer() {
  let wss;
  try { wss = new WebSocketServer({ host: '127.0.0.1', port: HELPER_PORT }); } catch (_) { return; }
  wss.on('error', () => {});
  wss.on('connection', (sock, req) => {
    // Only the browser extension may connect (not web pages)
    const origin = String(req.headers.origin || '');
    if (!/^(chrome|moz)-extension:\/\//.test(origin)) { sock.close(); return; }
    helperSock = sock;
    send(reader, 'helper', true);
    sock.on('message', (data) => {
      let m;
      try { m = JSON.parse(String(data)); } catch (_) { return; }
      const w = m.id && helperWaiting.get(m.id);
      if (!w) return;
      helperWaiting.delete(m.id);
      clearTimeout(w.timer);
      m.ok ? w.resolve(m) : w.reject(new Error(m.error || 'the browser helper had a problem'));
    });
    sock.on('close', () => { if (helperSock === sock) { helperSock = null; send(reader, 'helper', false); } });
    sock.on('error', () => {});
  });
}
const helperOn = () => !!(helperSock && helperSock.readyState === 1);
function helperAsk(msg, timeout = 90000) {
  return new Promise((resolve, reject) => {
    if (!helperOn()) { reject(new Error('the browser helper isn\'t connected')); return; }
    const id = helperNext++;
    const timer = setTimeout(() => { helperWaiting.delete(id); reject(new Error('the browser helper took too long')); }, timeout);
    helperWaiting.set(id, { resolve, reject, timer });
    helperSock.send(JSON.stringify({ ...msg, id }));
  });
}
// Pages handed over by the helper, used straight away instead of downloading them again
const handedOver = new Map();
ipcMain.handle('helper-status', () => helperOn());
ipcMain.on('helper-folder', () => require('electron').shell.openPath(res('helper')));

// Pages are fetched through Chromium's own networking (like a real browser), in a session that keeps
// sites' cookies. If a site refuses (bot protection) or the story only appears after its scripts run,
// the page is opened in a hidden browser window instead. Ads and pop-ups are blocked there.
const browserHosts = new Set();
const hostOf = (u) => { try { return new URL(u).hostname; } catch (_) { return ''; } };
const AD_HOSTS = /(^|\.)(doubleclick\.net|googlesyndication\.com|googleadservices\.com|adservice\.google\.[a-z.]+|google-analytics\.com|googletagmanager\.com|adnxs\.com|taboola\.com|outbrain\.com|popads\.net|popcash\.net|propellerads\.com|adsterra\.com|exoclick\.com|juicyads\.com|hilltopads\.net|admaven\.com|onclickads\.net|clickadu\.com|mgid\.com|revcontent\.com|criteo\.(com|net)|pubmatic\.com|rubiconproject\.com|amazon-adsystem\.com|adsrvr\.org|moatads\.com|scorecardresearch\.com|quantserve\.com|zedo\.com|yieldmo\.com|33across\.com|sharethrough\.com|media\.net|monetag\.com|a-ads\.com|coinzilla\.com|bidvertiser\.com|adcash\.com|realsrv\.com|tsyndicate\.com|clksite\.com|dtscout\.com|histats\.com|pushwhy\.com|richpush\.co)$/i;
let sitesSession = null;
function sites() {
  if (sitesSession) return sitesSession;
  sitesSession = session.fromPartition('persist:sites');
  // Same as Chrome of this version, without the app's own name (sites check these match)
  const plain = app.userAgentFallback.replace(/\s(Electron|chapter-reader-pc|Chapter Reader)\/\S+/gi, '');
  sitesSession.setUserAgent(plain, 'en-GB,en');
  sitesSession.webRequest.onBeforeRequest({ urls: ['*://*/*'] }, (d, cb) => {
    let h = '';
    try { h = new URL(d.url).hostname; } catch (_) {}
    cb({ cancel: AD_HOSTS.test(h) });
  });
  return sitesSession;
}

function browserFetch(url) {
  return new Promise((resolve, reject) => {
    const w = new BrowserWindow({
      show: false, width: 1100, height: 850, title: 'Chapter Reader \u2013 loading the page',
      webPreferences: { session: sites(), backgroundThrottling: false },
    });
    w.setMenuBarVisibility(false);
    w.webContents.setAudioMuted(true);
    w.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));   // no pop-up ads
    const started = Date.now();
    let shown = false, done = false, stable = 0, lastLen = -1;
    const end = (err, result) => {
      if (done) return;
      done = true;
      clearInterval(timer);
      try { w.destroy(); } catch (_) {}
      err ? reject(err) : resolve(result);
    };
    const timer = setInterval(async () => {
      if (w.isDestroyed()) { end(new Error('the page window was closed')); return; }
      try {
        const [title, len] = await w.webContents.executeJavaScript('[document.title, document.body ? document.body.innerText.length : 0]');
        const challenge = /just a moment|attention required|checking your browser|verify you are human|are you a robot|captcha|security check|ddos/i.test(title);
        // Wait until the text stops growing (pages that load the chapter with scripts)
        if (!challenge && len > 300) {
          stable = len === lastLen ? stable + 1 : 0;
          lastLen = len;
          if (stable >= 1 || Date.now() - started > 20000) {
            const html = await w.webContents.executeJavaScript('document.documentElement.outerHTML');
            end(null, { html, url: w.webContents.getURL() });
            return;
          }
        }
        // Still on a check after a few seconds: it wants a click, so show it
        if (challenge && !shown && Date.now() - started > 5000) {
          shown = true;
          w.setTitle('Chapter Reader \u2013 please confirm you\'re human');
          w.show(); w.focus();
          new Notification({ title: 'Chapter Reader', body: 'This site wants you to confirm you\'re human. Tick the box in the window that opened, then it carries on by itself.' }).show();
        }
      } catch (_) {}
      if (Date.now() - started > 120000) end(new Error('the site didn\'t finish loading'));
    }, 1000);
    w.on('closed', () => end(new Error('the page window was closed')));
    w.loadURL(url).catch(() => {});
  });
}

ipcMain.handle('fetch', async (_e, arg) => {
  const url = typeof arg === 'string' ? arg : arg.url;
  const h = handedOver.get(url);
  if (h && Date.now() - h.t < 120000) { handedOver.delete(url); return { html: h.html, url: h.url }; }
  // The helper loads it in your own browser (the reading tab moves on; background checks use a hidden tab)
  if (helperOn()) {
    try {
      const page = await helperAsk({ type: 'load', url, quiet: !!(arg && arg.quiet) });
      return { html: page.html, url: page.url };
    } catch (e) {
      if (arg && arg.quiet) throw e;
    }
  }
  if ((arg && arg.render) || browserHosts.has(hostOf(url))) return browserFetch(url);
  let r;
  try {
    r = await sites().fetch(url, { headers: { 'Accept-Language': 'en-GB,en;q=0.9' }, redirect: 'follow' });
  } catch (_) {
    return browserFetch(url);
  }
  // Refused (bot protection, rate limits): use the real browser for this site from now on
  if (r.status === 403 || r.status === 429 || r.status === 503 || r.status === 401) {
    browserHosts.add(hostOf(url));
    return browserFetch(url);
  }
  if (!r.ok) throw new Error('the site answered ' + r.status);
  const html = await r.text();
  if (/just a moment|cf-chl|challenge-platform|captcha/i.test(html.slice(0, 20000)) && html.length < 60000) {
    browserHosts.add(hostOf(url));
    return browserFetch(url);
  }
  return { html, url: r.url };
});
// Manga: download a page image (sites often only allow it when it looks like it came from their page)
ipcMain.handle('image', async (_e, { url, referer }) => {
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': referer, 'Accept': 'image/avif,image/webp,image/*,*/*;q=0.8' }, redirect: 'follow' });
  if (!r.ok) throw new Error('image ' + r.status);
  return { data: Buffer.from(await r.arrayBuffer()), mime: r.headers.get('content-type') || 'image/jpeg' };
});

// Manga: read the text in a slice of a page, using Windows' built-in text recognition (offline).
// One PowerShell helper stays running; requests and answers are matched by id. If it ever
// gets stuck it is restarted, so one bad picture can't stop the rest of the chapter.
let ocrProc = null;
let ocrReady = null;
let ocrJobs = new Map();
let ocrBuf = '';
let ocrNextId = 1;

function withTimeout(promise, ms, what) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(what + ' took too long')), ms)),
  ]);
}

function stopOcr(why) {
  const p = ocrProc;
  ocrProc = null; ocrReady = null; ocrBuf = '';
  const err = new Error(why);
  for (const j of ocrJobs.values()) j.reject(err);
  ocrJobs = new Map();
  try { p?.kill(); } catch (_) {}
}

function startOcr() {
  if (ocrReady) return ocrReady;
  ocrReady = new Promise((resolve, reject) => {
    const p = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', res('ocr.ps1')], { windowsHide: true });
    ocrProc = p;
    let started = false;
    p.stdout.setEncoding('utf8');
    p.stdout.on('data', (chunk) => {
      if (p !== ocrProc) return;
      ocrBuf += chunk;
      let nl;
      while ((nl = ocrBuf.indexOf('\n')) >= 0) {
        const line = ocrBuf.slice(0, nl).trim();
        ocrBuf = ocrBuf.slice(nl + 1);
        if (!line) continue;
        let msg;
        try { msg = JSON.parse(line); } catch (_) { continue; }
        if (!started) {
          started = true;
          if (msg.ready) resolve(); else reject(new Error(msg.error || 'Text recognition did not start'));
          continue;
        }
        const job = ocrJobs.get(String(msg.id));
        if (!job) continue;
        ocrJobs.delete(String(msg.id));
        msg.error ? job.reject(new Error(msg.error)) : job.resolve(msg.lines || []);
      }
    });
    let errText = '';
    p.stderr.on('data', (d) => { errText += d; });
    p.on('error', (e) => { if (!started) { started = true; reject(e); } if (p === ocrProc) stopOcr('Could not start Windows text recognition: ' + e.message); });
    p.on('exit', () => {
      if (!started) { started = true; reject(new Error('Windows text recognition stopped. ' + errText.slice(-200))); }
      if (p === ocrProc) stopOcr('Windows text recognition stopped');
    });
  });
  return ocrReady;
}

ipcMain.handle('ocr', async (_e, png) => {
  await withTimeout(startOcr(), 30000, 'Starting text recognition');
  const id = String(ocrNextId++);
  const file = path.join(os.tmpdir(), `chapter-reader-ocr-${id}-${Math.random().toString(36).slice(2)}.png`);
  fs.writeFileSync(file, Buffer.from(png));
  try {
    const lines = await withTimeout(new Promise((resolve, reject) => {
      ocrJobs.set(id, { resolve, reject });
      ocrProc.stdin.write(id + '\t' + file + '\n');
    }), 25000, 'Reading a page').catch((e) => {
      // Stuck: restart the helper so the next pages work
      if (/too long/.test(e.message)) stopOcr('restarting');
      throw e;
    });
    return lines.map((x) => ({ l: x.l, t: x.t, r: x.r, b: x.b, text: x.text, lineH: Math.max(1, x.b - x.t), words: Array.isArray(x.words) ? x.words : (x.words ? [x.words] : []) }));
  } finally {
    ocrJobs.delete(id);
    fs.unlink(file, () => {});
  }
});

app.on('will-quit', () => { try { ocrProc?.kill(); } catch (_) {} });

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

// Windows needs this for notifications to show the app's name
if (process.platform === 'win32') app.setAppUserModelId('com.chapterreader.pc');

app.whenReady().then(() => {
  startHelperServer();
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
