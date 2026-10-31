// Chat rules: decides which chat messages need a staff member's attention.
// The phone app uses the same rules (ported to Kotlin), so both flag the same things.

const DEFAULT_RULES = {
  advertising: true,   // server addresses, IPs, Discord invites
  words: true,         // your blocked words list
  spam: true,          // the same message again and again, or lots of messages very fast
  caps: true,          // SHOUTING IN ALL CAPS
  flood: true,         // characters or words repeated many times (aaaaaaaa, lol lol lol lol lol)
  blockedWords: [],    // add your server's banned words here (one per line in the app)
  allowDomains: ['youtube.com', 'youtu.be', 'twitch.tv', 'imgur.com'],
  watchPlayers: [],    // every message from these players is shown
  ignorePlayers: [],   // never flag these (e.g. other staff, bots)
};

const LEET = { 0: 'o', 1: 'i', 3: 'e', 4: 'a', 5: 's', 7: 't', 8: 'b', 9: 'g', '@': 'a', $: 's', '!': 'i', '|': 'i', '+': 't', '€': 'e' };

/** Removes Minecraft colour codes like §a. */
function stripColors(s) { return String(s || '').replace(/§[0-9a-fk-orx]/gi, ''); }

/** Pulls the chat text out of one line of the game's log, or null if it isn't chat. */
function chatFromLog(line) {
  const m = /\[CHAT\]\s?(.*)$/.exec(line);
  return m ? stripColors(m[1]).trim() : null;
}

/** Which server the game is joining, from the log ("Connecting to play.example.net, 25565"). */
function serverFromLog(line) {
  const m = /Connecting to ([^\s,]+),?\s*(\d+)?/.exec(line);
  return m ? m[1].toLowerCase() : null;
}

/** Splits "[Rank] Name: hello" / "<Name> hello" / "Name » hello" into who said it and what. */
function splitSender(text) {
  let m = /^<([^>]{1,40})>\s?(.*)$/.exec(text);
  if (m) return { player: lastName(m[1]) || m[1].trim(), message: m[2] };
  m = /(?::|»|>>|>|➤|→|»)\s/.exec(text.slice(0, 80));
  if (m && m.index > 0) {
    const player = lastName(text.slice(0, m.index));
    if (player) return { player, message: text.slice(m.index + m[0].length) };
  }
  return { player: null, message: text };
}

function lastName(prefix) {
  const names = prefix.replace(/\[[^\]]*\]|\([^)]*\)|\{[^}]*\}/g, ' ').match(/[A-Za-z0-9_]{2,16}/g);
  return names ? names[names.length - 1] : null;
}

/** Lower case, undoes l33t speak and stretched letters, so "Fr33eeee" matches "free". */
function normalize(s) {
  return String(s).toLowerCase()
    .replace(/[0-9@$!|+€]/g, (c) => LEET[c] || c)
    .replace(/[^a-z\s]/g, '')
    .replace(/(.)\1{2,}/g, '$1$1');
}

const TLD = '(?:net|com|org|gg|me|io|co|uk|club|xyz|fun|world|pro|us|eu|tk|ml|ga|cf|online|live|games?|network|mc|land|host|cc)';
const DOMAIN_RE = new RegExp('\\b((?:[a-z0-9-]+\\.)+' + TLD + ')\\b', 'i');
const IP_RE = /\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{2,5})?\b/;
const INVITE_RE = /discord(?:\.gg|app\.com\/invite|\.com\/invite)\s*\/?\s*[a-z0-9-]+/i;

/** Finds an advertised server or invite, including disguised ones like "play . server (dot) net". */
function findAdvert(message, allow) {
  const undisguised = message
    .replace(/\s*[([{]\s*(?:dot|d0t|\.)\s*[)\]}]\s*/gi, '.')
    .replace(/\s+(?:dot|d0t)\s+/gi, '.')
    .replace(/\s*\.\s*/g, '.');
  for (const s of [message, undisguised]) {
    if (INVITE_RE.test(s)) return 'Discord invite';
    if (IP_RE.test(s)) return 'IP address';
    const m = DOMAIN_RE.exec(s);
    if (m) {
      const d = m[1].toLowerCase();
      if (!allow.some((a) => d === a || d.endsWith('.' + a))) return 'Web/server address (' + d + ')';
    }
  }
  return null;
}

class ChatRules {
  constructor(rules) {
    this.set(rules);
    this.history = new Map();   // player -> recent [{t, norm}]
  }

  set(rules) {
    this.r = { ...DEFAULT_RULES, ...(rules || {}) };
    const clean = (list) => (list || []).map((w) => String(w).trim()).filter(Boolean);
    this.words = clean(this.r.blockedWords).map((w) => ({ word: w, norm: normalize(w).replace(/\s+/g, '') })).filter((w) => w.norm.length >= 2);
    this.allow = clean(this.r.allowDomains).map((d) => d.toLowerCase());
    this.watch = new Set(clean(this.r.watchPlayers).map((p) => p.toLowerCase()));
    this.ignore = new Set(clean(this.r.ignorePlayers).map((p) => p.toLowerCase()));
  }

  /**
   * Checks one chat message. Returns { player, message, reasons } if it should be flagged,
   * otherwise null. [server] is the server being played on (its own address isn't advertising).
   */
  check(text, time = Date.now(), server = null) {
    const { player, message } = splitSender(text);
    const key = (player || '').toLowerCase();
    if (key && this.ignore.has(key)) return null;
    const reasons = [];
    if (key && this.watch.has(key)) reasons.push('Watched player');

    // Only player messages are checked; server announcements are left alone
    if (player) {
      if (this.r.advertising) {
        const allow = server ? [...this.allow, server.split('.').slice(-2).join('.')] : this.allow;
        const ad = findAdvert(message, allow);
        if (ad) reasons.push('Advertising: ' + ad);
      }
      if (this.r.words && this.words.length) {
        const norm = normalize(message);
        const tokens = new Set(norm.split(/\s+/).filter(Boolean));
        const squashed = norm.replace(/\s+/g, '');
        const hit = this.words.find((w) => tokens.has(w.norm) || (w.norm.length >= 4 && squashed.includes(w.norm)));
        if (hit) reasons.push('Blocked word: ' + hit.word);
      }
      if (this.r.caps) {
        const letters = message.replace(/[^A-Za-z]/g, '');
        if (letters.length >= 8 && letters.replace(/[^A-Z]/g, '').length / letters.length > 0.7) reasons.push('Caps');
      }
      if (this.r.flood) {
        if (/(.)\1{7,}/.test(message.replace(/\s/g, ''))) reasons.push('Character flood');
        else {
          const words = message.toLowerCase().split(/\s+/).filter(Boolean);
          const counts = {};
          for (const w of words) counts[w] = (counts[w] || 0) + 1;
          if (words.length >= 5 && Object.values(counts).some((c) => c >= 5)) reasons.push('Word flood');
        }
      }
      if (this.r.spam) {
        const h = (this.history.get(key) || []).filter((x) => time - x.t < 30000);
        const norm = normalize(message).trim();
        h.push({ t: time, norm });
        this.history.set(key, h);
        const same = h.filter((x) => x.norm === norm && norm.length > 0).length;
        const fast = h.filter((x) => time - x.t < 10000).length;
        if (same >= 3) reasons.push('Repeated message (' + same + '×)');
        else if (fast >= 6) reasons.push('Chat spam (' + fast + ' in 10s)');
      }
      if (this.history.size > 2000) this.history.clear();
    }
    return reasons.length ? { player, message, reasons } : null;
  }
}

module.exports = { ChatRules, DEFAULT_RULES, chatFromLog, serverFromLog, splitSender, normalize, findAdvert, stripColors };
