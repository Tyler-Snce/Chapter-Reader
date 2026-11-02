// Chapter Reader helper: talks to the Chapter Reader app on this PC (only on this computer, never
// over the internet). When the app asks, it hands over the page you're reading, or opens the next
// chapter in your tab and hands that over. Your own browser does the loading, so site checks,
// logins and ads work exactly as they do for you.
const PORT = 47321;
let ws = null;
let readerTab = null;

function connect() {
  if (ws && ws.readyState <= 1) return;
  try { ws = new WebSocket('ws://127.0.0.1:' + PORT); } catch (_) { ws = null; return; }
  ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', version: chrome.runtime.getManifest().version }));
  ws.onmessage = async (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch (_) { return; }
    if (!m.id) return;
    try {
      const r = await handle(m);
      send({ id: m.id, ok: true, ...r });
    } catch (err) {
      send({ id: m.id, ok: false, error: String((err && err.message) || err) });
    }
  };
  ws.onclose = () => { ws = null; };
  ws.onerror = () => {};
}
function send(o) { try { ws && ws.readyState === 1 && ws.send(JSON.stringify(o)); } catch (_) {} }

// Keep in touch with the app (and reconnect when it starts)
chrome.alarms.create('keep', { periodInMinutes: 0.5 });
chrome.alarms.onAlarm.addListener(() => { if (!ws || ws.readyState > 1) connect(); else send({ type: 'ping' }); });
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(connect);
connect();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const CHECK = /just a moment|attention required|checking your browser|verify you are human|are you a robot|security check|captcha/i;

async function grab(tabId) {
  const [res] = await chrome.scripting.executeScript({
    target: { tabId },
    func: () => ({ html: document.documentElement.outerHTML, url: location.href, title: document.title, len: document.body ? document.body.innerText.length : 0 }),
  });
  return res.result;
}

/** Waits until the page has loaded, any site check has passed, and the text has stopped changing. */
async function ready(tabId, timeout) {
  const start = Date.now();
  let last = -1;
  while (Date.now() - start < timeout) {
    try {
      const tab = await chrome.tabs.get(tabId);
      if (tab.status === 'complete') {
        const r = await grab(tabId);
        if (!CHECK.test(r.title) && r.len > 300) {
          if (r.len === last || Date.now() - start > 20000) return r;
          last = r.len;
        }
      }
    } catch (e) {
      if (/No tab/i.test(String(e))) throw new Error('the tab was closed');
    }
    await sleep(1000);
  }
  throw new Error('the page didn\'t finish loading (if the site asked you to confirm you\'re human, do that in your browser)');
}

async function handle(m) {
  if (m.type === 'current') {
    // The page you're looking at right now
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (!tab || !/^https?:/.test(tab.url || '')) throw new Error('no web page is open in this browser');
    readerTab = tab.id;
    return ready(tab.id, 30000);
  }
  if (m.type === 'load') {
    if (m.quiet) {
      // Background check (e.g. new chapter alerts): a hidden tab that's closed afterwards
      const tab = await chrome.tabs.create({ url: m.url, active: false });
      try { return await ready(tab.id, 60000); } finally { chrome.tabs.remove(tab.id).catch(() => {}); }
    }
    // Next chapter: your reading tab moves on to it
    if (readerTab !== null) {
      try { await chrome.tabs.update(readerTab, { url: m.url }); } catch (_) { readerTab = null; }
    }
    if (readerTab === null) {
      const tab = await chrome.tabs.create({ url: m.url, active: false });
      readerTab = tab.id;
    }
    await sleep(500);
    return ready(readerTab, 90000);
  }
  if (m.type === 'ping') return {};
  throw new Error('unknown request');
}
