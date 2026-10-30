// Microsoft "Natural" online voices (the same ones Edge's Read Aloud uses).
// Returns MP3 audio plus the timing of each word, for word highlighting.
const WebSocket = require('ws');
const crypto = require('crypto');

const TOKEN = '6A5AA1D4EAFF4E9FB37E23D68491D6F4';
const CHROMIUM = '130.0.2849.68';
const GEC_VERSION = '1-' + CHROMIUM;
const BASE = 'wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1';

function secMsGec() {
  // Windows file-time ticks, rounded down to 5 minutes, hashed with the token
  let secs = BigInt(Math.floor(Date.now() / 1000)) + 11644473600n;
  secs -= secs % 300n;
  const ticks = secs * 10000000n;
  return crypto.createHash('sha256').update(ticks.toString() + TOKEN, 'ascii').digest('hex').toUpperCase();
}

function stamp() {
  const d = new Date();
  const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const p = (n) => String(n).padStart(2, '0');
  return `${days[d.getUTCDay()]} ${months[d.getUTCMonth()]} ${p(d.getUTCDate())} ${d.getUTCFullYear()} ` +
    `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())} GMT+0000 (Coordinated Universal Time)`;
}

function escapeXml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}

function uuid() { return crypto.randomUUID().replace(/-/g, ''); }

/**
 * @param {string} text  what to say
 * @param {string} voice e.g. "en-GB-SoniaNeural"
 * @param {number} rate  1 = normal, 1.5 = 50% faster
 * @returns {Promise<{audio: Buffer, mime: string, words: {ms:number,start:number,end:number}[]}>}
 */
function synth(text, voice, rate) {
  return new Promise((resolve, reject) => {
    const url = `${BASE}?TrustedClientToken=${TOKEN}&Sec-MS-GEC=${secMsGec()}` +
      `&Sec-MS-GEC-Version=${GEC_VERSION}&ConnectionId=${uuid()}`;
    const major = CHROMIUM.split('.')[0];
    const ws = new WebSocket(url, {
      headers: {
        'Pragma': 'no-cache',
        'Cache-Control': 'no-cache',
        'Origin': 'chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold',
        'Accept-Encoding': 'gzip, deflate, br',
        'Accept-Language': 'en-US,en;q=0.9',
        'User-Agent': `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36 Edg/${major}.0.0.0`,
        'Cookie': 'muid=' + crypto.randomBytes(16).toString('hex').toUpperCase() + ';',
      },
    });

    const chunks = [];
    const marks = [];
    let done = false;
    const timer = setTimeout(() => fail(new Error('Microsoft voice timed out')), 30000);

    function fail(err) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try { ws.terminate(); } catch (_) {}
      reject(err);
    }

    ws.on('open', () => {
      ws.send(
        `X-Timestamp:${stamp()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n` +
        '{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"true"},' +
        '"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}\r\n'
      );
      const pct = Math.round((rate - 1) * 100);
      const lang = voice.split('-').slice(0, 2).join('-');
      const ssml =
        `<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${lang}'>` +
        `<voice name='Microsoft Server Speech Text to Speech Voice (${lang}, ${voice.split('-').slice(2).join('-')})'>` +
        `<prosody pitch='+0Hz' rate='${pct >= 0 ? '+' : ''}${pct}%' volume='+0%'>${escapeXml(text)}</prosody></voice></speak>`;
      ws.send(
        `X-RequestId:${uuid()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${stamp()}Z\r\nPath:ssml\r\n\r\n` + ssml
      );
    });

    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        const buf = Buffer.from(data);
        const headLen = buf.readUInt16BE(0);
        const head = buf.subarray(2, 2 + headLen).toString();
        if (head.includes('Path:audio')) chunks.push(buf.subarray(2 + headLen));
        return;
      }
      const msg = data.toString();
      const split = msg.indexOf('\r\n\r\n');
      const head = msg.slice(0, split);
      const body = msg.slice(split + 4);
      if (head.includes('Path:audio.metadata')) {
        try {
          for (const m of JSON.parse(body).Metadata || []) {
            if (m.Type === 'WordBoundary') marks.push({ ticks: m.Data.Offset, word: m.Data.text.Text });
          }
        } catch (_) {}
      } else if (head.includes('Path:turn.end')) {
        if (done) return;
        done = true;
        clearTimeout(timer);
        try { ws.close(); } catch (_) {}
        if (!chunks.length) { reject(new Error('Microsoft voice returned no audio')); return; }
        // Match each spoken word to its place in the text
        const words = [];
        let cursor = 0;
        for (const m of marks) {
          const at = text.indexOf(m.word, cursor);
          if (at < 0) continue;
          words.push({ ms: m.ticks / 10000, start: at, end: at + m.word.length });
          cursor = at + m.word.length;
        }
        resolve({ audio: Buffer.concat(chunks), mime: 'audio/mpeg', words });
      }
    });

    ws.on('unexpected-response', (_req, res) => fail(new Error('Microsoft voice refused the connection (' + res.statusCode + ')')));
    ws.on('error', (e) => fail(e));
    ws.on('close', () => { if (!done) fail(new Error('Microsoft voice connection closed')); });
  });
}

module.exports = { synth };
