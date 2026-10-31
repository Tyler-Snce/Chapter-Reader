// Finds the chat log Minecraft is writing (vanilla, Lunar, Orbit and other launchers)
// and follows it live, handing each new line to [onLine].
const fs = require('fs');
const path = require('path');
const os = require('os');

const home = os.homedir();
const appData = process.env.APPDATA || path.join(home, 'AppData', 'Roaming');
const localAppData = process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');

/** Folders where launchers keep their game logs. */
function candidateRoots(extra) {
  const roots = [
    path.join(appData, '.minecraft'),
    path.join(home, '.lunarclient'),
    path.join(home, '.minecraft'),
  ];
  // Launchers with their own folder, e.g. Orbit: look for anything named like a launcher
  const launcherName = /orbit|lunar|minecraft|feather|badlion|prism|multimc|tlauncher|modrinth|curseforge|atlauncher|gdlauncher|labymod/i;
  for (const base of [appData, localAppData, home]) {
    try {
      for (const d of fs.readdirSync(base)) {
        if (launcherName.test(d)) roots.push(path.join(base, d));
      }
    } catch (_) {}
  }
  for (const p of extra || []) if (p) roots.push(p);
  return [...new Set(roots)].filter((p) => { try { return fs.existsSync(p); } catch (_) { return false; } });
}

/** All log files under [root] (looks a few folders deep, only inside folders named "logs"). */
function findLogs(root, depth = 0, inLogs = false, out = []) {
  if (depth > 6 || out.length > 500) return out;
  let entries;
  try { entries = fs.readdirSync(root, { withFileTypes: true }); } catch (_) { return out; }
  for (const e of entries) {
    const p = path.join(root, e.name);
    if (e.isFile()) {
      if ((inLogs || /\.log$/i.test(root)) && /\.(log|txt)$/i.test(e.name) && !/debug|crash|launcher/i.test(e.name)) out.push(p);
      else if (e.name === 'latest.log') out.push(p);
    } else if (e.isDirectory() && !/^(assets|libraries|versions|natives|jre|runtime|cache|node_modules|textures|screenshots|resourcepacks|shaderpacks|saves|mods|config)$/i.test(e.name)) {
      findLogs(p, depth + 1, inLogs || /^logs?$/i.test(e.name), out);
    }
  }
  return out;
}

class LogWatcher {
  /** [getExtra] returns extra folders/files to look in; [onLine](line, file) gets each new line. */
  constructor(getExtra, onLine, onStatus) {
    this.getExtra = getExtra;
    this.onLine = onLine;
    this.onStatus = onStatus || (() => {});
    this.files = [];
    this.lastScan = 0;
    this.current = null;     // file being followed
    this.offset = 0;
    this.partial = '';
    this.timer = null;
  }

  start() {
    if (this.timer) return;
    this.tick();
    this.timer = setInterval(() => this.tick(), 1000);
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
    this.current = null;
    this.onStatus({ watching: null });
  }

  rescan() { this.lastScan = 0; }

  tick() {
    const now = Date.now();
    if (now - this.lastScan > 30000) {
      this.lastScan = now;
      const extra = this.getExtra();
      const files = [];
      for (const root of candidateRoots(extra)) {
        try {
          if (fs.statSync(root).isFile()) files.push(root);
          else findLogs(root, 0, /logs?$/i.test(root), files);
        } catch (_) {}
      }
      this.files = [...new Set(files)];
    }
    // Follow whichever log was written to most recently (the game that's running)
    let newest = null, newestTime = 0;
    for (const f of this.files) {
      try { const t = fs.statSync(f).mtimeMs; if (t > newestTime) { newest = f; newestTime = t; } } catch (_) {}
    }
    if (!newest) { this.onStatus({ watching: null, found: 0 }); return; }
    if (newest !== this.current) {
      this.current = newest;
      // Start from the end: old messages from before you started watching aren't flagged
      try { this.offset = fs.statSync(newest).size; } catch (_) { this.offset = 0; }
      this.partial = '';
      this.onStatus({ watching: newest, found: this.files.length, updated: newestTime });
      return;
    }
    this.readNew();
  }

  readNew() {
    let size;
    try { size = fs.statSync(this.current).size; } catch (_) { return; }
    if (size < this.offset) { this.offset = 0; this.partial = ''; }   // the game started a new log
    if (size === this.offset) return;
    const len = Math.min(size - this.offset, 4 * 1024 * 1024);
    const buf = Buffer.alloc(len);
    let fd;
    try {
      fd = fs.openSync(this.current, 'r');
      fs.readSync(fd, buf, 0, len, this.offset);
    } catch (_) { return; } finally { if (fd !== undefined) try { fs.closeSync(fd); } catch (_) {} }
    this.offset += len;
    const text = this.partial + buf.toString('utf8');
    const lines = text.split(/\r?\n/);
    this.partial = lines.pop();
    for (const line of lines) if (line) this.onLine(line, this.current);
  }
}

module.exports = { LogWatcher, candidateRoots, findLogs };
