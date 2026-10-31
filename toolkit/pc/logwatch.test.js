// Tests following a log file that grows and gets replaced (run in the build)
const assert = require('assert');
const fs = require('fs'); const os = require('os'); const path = require('path');
const { LogWatcher } = require('./logwatch.js');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mc-'));
const logs = path.join(dir, 'game', 'logs'); fs.mkdirSync(logs, { recursive: true });
const f = path.join(logs, 'latest.log');
fs.writeFileSync(f, '[10:00:00] [Render thread/INFO]: [CHAT] old message\n');
const got = [];
const w = new LogWatcher(() => [dir], (l) => got.push(l));
w.tick();                                  // finds the file, starts at the end
fs.appendFileSync(f, '[10:00:01] [Render thread/INFO]: [CHAT] Steve: hello\n[10:00:02] part');
w.tick();
fs.appendFileSync(f, 'ial line\n');
w.tick();
assert.deepStrictEqual(got, ['[10:00:01] [Render thread/INFO]: [CHAT] Steve: hello', '[10:00:02] partial line']);
fs.writeFileSync(f, 'new\n');              // game restarted: log replaced with a shorter one
w.tick();
assert.strictEqual(got[got.length - 1], 'new');
console.log('log watcher: all tests passed');
