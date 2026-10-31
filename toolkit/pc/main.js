// Staff Toolkit for PC: watches Minecraft chat for rule-breaking messages, and captures
// screenshots, clips of the last few seconds, and full recordings, with easy upload links.
const {
  app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, globalShortcut, clipboard, Notification,
  desktopCapturer, session, shell, screen, dialog,
} = require('electron');
const path = require('path');
const fs = require('fs');
const { ChatRules, DEFAULT_RULES, chatFromLog, serverFromLog } = require('./rules.js');
const { LogWatcher } = require('./logwatch.js');
const upload = require('./upload.js');
const ff = require('./ffmpeg.js');

if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0); }
if (process.platform === 'win32') app.setAppUserModelId('com.stafftoolkit.pc');

// ---------- settings ----------

const settingsFile = () => path.join(app.getPath('userData'), 'settings.json');
const DEFAULTS = {
  watcherOn: true,
  notifyFlags: true,
  rules: DEFAULT_RULES,
  logPaths: [],
  discordWebhook: '',
  flagsToDiscord: false,
  uploadTo: 'catbox',          // 'catbox', 'discord', 'both' or 'none'
  afterCapture: 'upload',      // 'upload' (and copy the link) or 'save'
  autoClipFlags: false,
  source: '',                  // screen or window to capture ('' = main screen)
  fps: 60,
  resolution: 'native',        // 'native', '1440', '1080', '720'
  quality: 'high',             // 'standard', 'high', 'max'
  audio: {
    desktop: { on: true, volume: 1 },
    mic: { on: false, id: 'default', volume: 1, noise: true },
    extra: [],                 // [{ id, label, on, volume }]
    muted: false,
  },
  replayOn: false,
  replaySeconds: 30,
  hotkeys: { screenshot: 'Ctrl+Shift+F1', clip: 'Ctrl+Shift+F2', record: 'Ctrl+Shift+F3' },
  folder: '',
};
let settings = JSON.parse(JSON.stringify(DEFAULTS));

function loadSettings() {
  try {
    const s = JSON.parse(fs.readFileSync(settingsFile(), 'utf8'));
    settings = { ...settings, ...s, audio: { ...DEFAULTS.audio, ...(s.audio || {}) }, hotkeys: { ...DEFAULTS.hotkeys, ...(s.hotkeys || {}) }, rules: { ...DEFAULT_RULES, ...(s.rules || {}) } };
  } catch (_) {}
}
function saveSettings() {
  try { fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 2)); } catch (_) {}
}
// Where captures are saved. A drive root like "T:\" already exists and can't be "created",
// so only missing folders are made; if a folder can't be used, fall back and say so.
let warnedFolder = '';
function usable(f) {
  try {
    if (!fs.existsSync(f)) {
      if (fs.existsSync(path.dirname(f))) fs.mkdirSync(f);
      else fs.mkdirSync(f, { recursive: true });
    }
    fs.accessSync(f, fs.constants.W_OK);
    return true;
  } catch (_) { return false; }
}
function folder() {
  const choices = [settings.folder, path.join(app.getPath('videos'), 'Staff Toolkit'), path.join(app.getPath('documents'), 'Staff Toolkit')];
  for (const f of choices) {
    if (!f || !usable(f)) continue;
    if (settings.folder && f !== settings.folder && warnedFolder !== settings.folder) {
      warnedFolder = settings.folder;
      send('toast', 'Couldn\'t save to ' + settings.folder + ' \u2013 saving to ' + f + ' instead. You can pick another folder in Settings.');
    }
    return f;
  }
  return app.getPath('temp');
}
const stamp = () => new Date().toISOString().replace(/[:T]/g, '-').replace(/\..+/, '');

// ---------- history (flags and captures), kept between runs ----------

const dataFile = (name) => path.join(app.getPath('userData'), name + '.json');
function loadList(name) { try { return JSON.parse(fs.readFileSync(dataFile(name), 'utf8')); } catch (_) { return []; } }
let flags = [];
let captures = [];
let saveTimer = null;
function saveLists() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try { fs.writeFileSync(dataFile('flags'), JSON.stringify(flags.slice(0, 500))); } catch (_) {}
    try { fs.writeFileSync(dataFile('captures'), JSON.stringify(captures.slice(0, 300))); } catch (_) {}
  }, 500);
}

// ---------- windows and tray ----------

let win = null;
let tray = null;
let quitting = false;
let recState = { recording: false, replay: false, since: 0 };

function send(channel, data) { if (win && !win.isDestroyed()) win.webContents.send(channel, data); }

function notify(title, body, onClick) {
  if (!Notification.isSupported()) return;
  const n = new Notification({ title, body, icon: path.join(__dirname, 'icon.png'), silent: false });
  if (onClick) n.on('click', onClick);
  n.show();
}

function createWindow() {
  win = new BrowserWindow({
    width: 980, height: 720, minWidth: 720, minHeight: 520,
    title: 'Staff Toolkit', backgroundColor: '#111317', icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: { nodeIntegration: true, contextIsolation: false, backgroundThrottling: false },
  });
  win.loadFile('index.html');
  win.on('close', (e) => {
    if (quitting) return;
    e.preventDefault();
    win.hide();
    if (!settings.toldTray) {
      settings.toldTray = true; saveSettings();
      notify('Staff Toolkit is still running', 'It keeps watching chat and your shortcuts work. Right-click the tray icon to quit.');
    }
  });
}

function showWindow(tab) {
  if (!win) return;
  win.show(); win.focus();
  if (tab) send('tab', tab);
}

function updateTray() {
  if (!tray) return;
  const rec = recState.recording;
  tray.setToolTip('Staff Toolkit' + (rec ? ' – recording' : '') + (recState.replay ? ' – replay on' : ''));
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open Staff Toolkit', click: () => showWindow() },
    { type: 'separator' },
    { label: 'Take screenshot\t' + settings.hotkeys.screenshot, click: () => screenshot() },
    { label: 'Save last ' + settings.replaySeconds + 's\t' + settings.hotkeys.clip, click: () => send('rec', 'clip'), enabled: recState.replay },
    { label: (rec ? 'Stop recording' : 'Start recording') + '\t' + settings.hotkeys.record, click: () => send('rec', 'toggle') },
    { label: 'Replay buffer', type: 'checkbox', checked: !!settings.replayOn, click: (i) => setSettings({ replayOn: i.checked }) },
    { label: 'Watch chat', type: 'checkbox', checked: !!settings.watcherOn, click: (i) => setSettings({ watcherOn: i.checked }) },
    { type: 'separator' },
    { label: 'Open captures folder', click: () => shell.openPath(folder()) },
    { label: 'Quit', click: () => { quitting = true; app.quit(); } },
  ]));
}

function createTray() {
  tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'tray.png')));
  tray.on('click', () => showWindow());
  updateTray();
}

// ---------- shortcuts ----------

function registerHotkeys() {
  globalShortcut.unregisterAll();
  const failed = [];
  const add = (key, fn) => {
    if (!key) return;
    try { if (!globalShortcut.register(key, fn)) failed.push(key); } catch (_) { failed.push(key); }
  };
  add(settings.hotkeys.screenshot, () => screenshot());
  add(settings.hotkeys.clip, () => send('rec', 'clip'));
  add(settings.hotkeys.record, () => send('rec', 'toggle'));
  send('hotkeys-failed', failed);
}

function setSettings(patch) {
  const before = JSON.stringify(settings.hotkeys);
  Object.assign(settings, patch);
  if ('startup' in patch) {
    try { app.setLoginItemSettings({ openAtLogin: !!patch.startup, args: ['--hidden'] }); } catch (_) {}
  }
  saveSettings();
  if (patch.rules) rules.set(settings.rules);
  if ('watcherOn' in patch) settings.watcherOn ? watcher.start() : watcher.stop();
  if ('logPaths' in patch) watcher.rescan();
  if (JSON.stringify(settings.hotkeys) !== before) registerHotkeys();
  send('settings', settings);
  updateTray();
}

// ---------- chat watcher ----------

const rules = new ChatRules(DEFAULT_RULES);
let currentServer = null;
let watchStatus = { watching: null };

const watcher = new LogWatcher(
  () => settings.logPaths,
  (line) => {
    const srv = serverFromLog(line);
    if (srv) { currentServer = srv; send('server', srv); return; }
    const chat = chatFromLog(line);
    if (!chat) return;
    send('chat', { time: Date.now(), text: chat, server: currentServer });
    const hit = rules.check(chat, Date.now(), currentServer);
    if (hit) addFlag({ ...hit, server: currentServer });
  },
  (s) => { watchStatus = { ...watchStatus, ...s }; send('watch-status', watchStatus); },
);

function addFlag(hit) {
  const flag = { id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), time: Date.now(), ...hit, status: 'new' };
  flags.unshift(flag);
  if (flags.length > 500) flags.length = 500;
  saveLists();
  send('flag', flag);
  if (settings.notifyFlags) notify(flag.reasons.join(', '), (flag.player ? flag.player + ': ' : '') + flag.message, () => showWindow('chat'));
  if (settings.flagsToDiscord && settings.discordWebhook) {
    upload.toDiscord(settings.discordWebhook, {
      embed: {
        title: flag.reasons.join(', ').slice(0, 250),
        description: ('**' + (flag.player || 'Unknown') + '**: ' + flag.message).slice(0, 3900),
        color: 0xe5484d,
        footer: { text: (flag.server || 'Unknown server') + ' · Staff Toolkit' },
        timestamp: new Date(flag.time).toISOString(),
      },
    }).catch((e) => send('toast', 'Couldn\'t post the flag to Discord: ' + e.message));
  }
  if (settings.autoClipFlags && recState.replay) send('rec', { clip: true, flagId: flag.id });
}

// ---------- captures ----------

/** Records a finished capture, then uploads it if that's turned on. */
async function captured(file, kind, flagId) {
  const item = { id: Date.now().toString(36), file, kind, time: Date.now(), size: fs.statSync(file).size, link: null, flagId: flagId || null };
  captures.unshift(item);
  saveLists();
  send('capture', item);
  if (settings.afterCapture === 'upload' && settings.uploadTo !== 'none') await doUpload(item);
  else notify(kind === 'screenshot' ? 'Screenshot saved' : 'Video saved', path.basename(file), () => shell.showItemInFolder(file));
}

async function doUpload(item) {
  const to = settings.uploadTo === 'none' ? 'catbox' : settings.uploadTo;
  const label = item.kind === 'screenshot' ? 'Screenshot' : item.kind === 'clip' ? 'Clip' : 'Recording';
  item.uploading = true; send('capture-update', item);
  try {
    let link = null;
    if (to === 'catbox' || to === 'both') link = await upload.toCatbox(item.file);
    if (to === 'discord' || to === 'both') {
      let file = item.file;
      // Too big for Discord? Post the Catbox link, or make a smaller copy if only Discord is used
      if (item.size > upload.DISCORD_LIMIT) {
        if (link) file = null;
        else if (item.kind !== 'screenshot') {
          send('toast', 'Making a smaller copy for Discord…');
          const small = item.file.replace(/(\.\w+)$/, '-discord$1');
          await ff.shrink(item.file, small, upload.DISCORD_LIMIT * 0.95);
          file = small;
        }
      }
      const flag = item.flagId ? flags.find((f) => f.id === item.flagId) : null;
      const text = (flag ? '**' + (flag.player || '?') + '**: ' + flag.message + ' (' + flag.reasons.join(', ') + ')\n' : label + ' from Staff Toolkit\n') + (link || '');
      const att = await upload.toDiscord(settings.discordWebhook, { content: text, file });
      link = link || att;
    }
    item.link = link;
    if (link) {
      clipboard.writeText(link);
      notify(label + ' uploaded – link copied', link, () => shell.openExternal(link));
    } else {
      notify(label + ' posted to Discord', path.basename(item.file));
    }
    if (item.flagId) {
      const f = flags.find((x) => x.id === item.flagId);
      if (f) { f.evidence = link; send('flag-update', f); }
    }
  } catch (e) {
    item.error = e.message;
    notify('Upload failed', e.message + ' – the file is still saved.', () => shell.showItemInFolder(item.file));
  } finally {
    item.uploading = false;
    saveLists();
    send('capture-update', item);
  }
}

async function pickSource() {
  const sources = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: { width: 0, height: 0 } });
  return sources.find((s) => s.id === settings.source) || sources.find((s) => s.id.startsWith('screen')) || sources[0];
}

async function screenshot() {
  try {
    const d = screen.getPrimaryDisplay();
    const size = { width: Math.round(d.size.width * d.scaleFactor), height: Math.round(d.size.height * d.scaleFactor) };
    const sources = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: size });
    const src = sources.find((s) => s.id === settings.source) || sources.find((s) => s.id.startsWith('screen'));
    if (!src || src.thumbnail.isEmpty()) throw new Error('nothing to capture (is the window minimised?)');
    const file = path.join(folder(), 'Screenshot ' + stamp() + '.png');
    fs.writeFileSync(file, src.thumbnail.toPNG());
    captured(file, 'screenshot');
  } catch (e) {
    notify('Screenshot failed', e.message);
  }
}

// ---------- messages from the window ----------

ipcMain.handle('init', () => ({ settings, flags, captures, watchStatus, server: currentServer, recState, folder: folder() }));
ipcMain.on('set', (_e, patch) => setSettings(patch));
ipcMain.handle('sources', async () => {
  const sources = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: { width: 160, height: 90 } });
  return sources.map((s) => ({ id: s.id, name: s.name, thumb: s.thumbnail.toDataURL() }));
});
ipcMain.handle('temp-dir', () => { const d = path.join(app.getPath('temp'), 'staff-toolkit'); fs.mkdirSync(d, { recursive: true }); return d; });
ipcMain.handle('out-file', (_e, kind) => path.join(folder(), (kind === 'clip' ? 'Clip ' : 'Recording ') + stamp() + '.mp4'));
ipcMain.on('rec-state', (_e, s) => { recState = s; updateTray(); });
ipcMain.on('screenshot', () => screenshot());
ipcMain.on('notify', (_e, { title, body }) => notify(title, body));

// A recording finished: turn it into a normal .mp4
ipcMain.handle('finish-recording', async (_e, { raw, out }) => {
  send('busy', 'Saving recording…');
  try {
    await ff.finish(raw, out, (t) => send('busy', 'Saving recording… ' + Math.round(t) + 's'));
    fs.rmSync(raw, { force: true });
  } catch (e) {
    // Keep the original if it couldn't be converted
    const keep = out.replace(/\.mp4$/, path.extname(raw));
    try { fs.renameSync(raw, keep); out = keep; } catch (_) {}
  }
  send('busy', '');
  captured(out, 'recording');
  return out;
});

// Replay: join the last pieces into a clip
ipcMain.handle('finish-clip', async (_e, { pieces, seconds, out, flagId }) => {
  try {
    await ff.joinLast(pieces, seconds, out);
    captured(out, 'clip', flagId);
    return out;
  } catch (e) {
    notify('Couldn\'t save the clip', e.message);
    return null;
  } finally {
    for (const p of pieces) fs.rm(p, { force: true }, () => {});
  }
});

// The editor: quick cut (no re-encode) when only trimming, otherwise a full edit
ipcMain.handle('edit', async (_e, { file, start, end, crop, volume, music, exact }) => {
  const out = file.replace(/( \(edited( \d+)?\))?(\.\w+)$/, '') + ' (edited ' + stamp().slice(11) + ').mp4';
  const len = Math.max(1, end - start);
  const progress = (t) => send('busy', 'Saving your edit\u2026 ' + Math.min(99, Math.round((t / len) * 100)) + '%');
  send('busy', 'Saving your edit\u2026');
  try {
    if (!crop && !music && volume === 1 && !exact) await ff.trim(file, out, start, end, false, settings.quality, progress);
    else await ff.edit(file, out, { start, end, crop, volume, music, quality: settings.quality }, progress);
  } catch (e) {
    send('busy', '');
    throw e;
  }
  send('busy', '');
  const kind = captures.find((c) => c.file === file)?.kind || 'clip';
  captured(out, kind);
  return out;
});
ipcMain.handle('choose-music', async () => {
  const r = await dialog.showOpenDialog(win, { title: 'Choose music or a sound', properties: ['openFile'], filters: [{ name: 'Audio', extensions: ['mp3', 'wav', 'm4a', 'aac', 'ogg', 'flac', 'opus', 'mp4', 'webm'] }] });
  return r.canceled ? null : r.filePaths[0];
});
ipcMain.handle('duration', (_e, file) => ff.duration(file));
ipcMain.handle('upload', async (_e, id) => {
  const item = captures.find((c) => c.id === id);
  if (item) await doUpload(item);
  return item;
});
ipcMain.on('copy', (_e, text) => clipboard.writeText(text));
// "Save a copy..." puts the actual file wherever you like
ipcMain.handle('save-copy', async (_e, file) => {
  const r = await dialog.showSaveDialog(win, { defaultPath: path.join(app.getPath('downloads'), path.basename(file)) });
  if (r.canceled || !r.filePath) return null;
  await fs.promises.copyFile(file, r.filePath);
  return r.filePath;
});
// Drag a capture out of the app (into Discord, a folder, an editor...)
ipcMain.on('drag-out', (e, file) => {
  try { e.sender.startDrag({ file, icon: nativeImage.createFromPath(path.join(__dirname, 'tray.png')) }); } catch (_) {}
});
// Copy the file itself, so it can be pasted into Discord or File Explorer
ipcMain.on('copy-file', (_e, file) => {
  if (/\.png$/i.test(file)) clipboard.writeImage(nativeImage.createFromPath(file));
  else clipboard.writeBuffer('FileNameW', Buffer.from(file + '\0', 'ucs2'));
});
ipcMain.on('open', (_e, file) => shell.openPath(file));
ipcMain.on('reveal', (_e, file) => shell.showItemInFolder(file));
ipcMain.on('open-link', (_e, url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
ipcMain.on('open-folder', () => shell.openPath(folder()));
ipcMain.handle('choose-folder', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openDirectory'] });
  if (!r.canceled && r.filePaths[0]) setSettings({ folder: r.filePaths[0] });
  return folder();
});
ipcMain.handle('choose-log', async () => {
  const r = await dialog.showOpenDialog(win, { title: 'Choose your game\'s log file or logs folder', properties: ['openFile', 'openDirectory'], filters: [{ name: 'Logs', extensions: ['log', 'txt'] }] });
  if (r.canceled || !r.filePaths[0]) return null;
  setSettings({ logPaths: [...new Set([...(settings.logPaths || []), r.filePaths[0]])] });
  return r.filePaths[0];
});
ipcMain.on('flag-status', (_e, { id, status }) => {
  const f = flags.find((x) => x.id === id);
  if (f) { f.status = status; saveLists(); send('flag-update', f); }
});
ipcMain.on('clear-flags', () => { flags = []; saveLists(); });
ipcMain.on('delete-capture', (_e, { id, file }) => {
  captures = captures.filter((c) => c.id !== id);
  if (file) fs.rm(file, { force: true }, () => {});
  saveLists();
});
ipcMain.handle('test-discord', async () => {
  await upload.toDiscord(settings.discordWebhook, { content: 'Staff Toolkit is connected to this channel.' });
  return true;
});

// ---------- start ----------

app.on('second-instance', () => showWindow());
app.on('before-quit', () => { quitting = true; });
app.on('will-quit', () => globalShortcut.unregisterAll());
app.on('window-all-closed', (e) => e.preventDefault());

app.whenReady().then(() => {
  loadSettings();
  flags = loadList('flags');
  captures = loadList('captures').filter((c) => { try { return fs.existsSync(c.file); } catch (_) { return false; } });
  rules.set(settings.rules);

  // Screen capture for recordings: the screen/window chosen in settings, with desktop sound if wanted
  session.defaultSession.setDisplayMediaRequestHandler(async (_req, callback) => {
    try {
      const src = await pickSource();
      const wantDesktop = settings.audio.desktop.on && !settings.audio.muted;
      callback(wantDesktop ? { video: src, audio: 'loopback' } : { video: src });
    } catch (_) { callback({}); }
  });
  // Allow the microphone for the app's own window
  session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) => cb(['media', 'display-capture', 'notifications'].includes(perm)));

  createWindow();
  // Build check: open the window, report any script errors, then quit
  if (process.argv.includes('--smoke')) {
    const errors = [];
    win.webContents.on('console-message', (_e, level, message) => { if (level >= 2 && !/Electron Security Warning/.test(message)) errors.push(message); });
    win.webContents.on('did-finish-load', () => setTimeout(async () => {
      const info = await win.webContents.executeJavaScript(
        "JSON.stringify({ mp4: MediaRecorder.isTypeSupported('video/mp4;codecs=avc1,mp4a.40.2'), webm: MediaRecorder.isTypeSupported('video/webm;codecs=h264,opus'), tabs: document.querySelectorAll('.tab').length })");
      fs.writeFileSync(path.join(process.cwd(), 'smoke.txt'), 'info ' + info + '\nerrors ' + JSON.stringify(errors));
      quitting = true; app.exit(errors.length ? 1 : 0);
    }, 5000));
  }
  createTray();
  registerHotkeys();
  if (settings.watcherOn) watcher.start();
  if (process.argv.includes('--hidden')) win.once('ready-to-show', () => win.hide());
});
