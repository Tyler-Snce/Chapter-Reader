// Finds the story text and next/previous chapter links in a page.
// Same rules as the phone app, so both behave the same on a site.

function clean(t) { return (t || '').replace(/\s+/g, ' ').trim(); }

function paragraphs(doc) {
  const groups = new Map();
  doc.querySelectorAll('p').forEach((p) => {
    const par = p.parentElement; if (!par) return;
    const t = clean(p.textContent); if (!t) return;
    groups.set(par, (groups.get(par) || 0) + t.length);
  });
  let best = null; let bestLen = 0;
  groups.forEach((len, el) => { if (len > bestLen) { bestLen = len; best = el; } });
  if (best && bestLen > 300) {
    return Array.from(best.children)
      .filter((c) => c.tagName === 'P')
      .map((c) => clean(c.textContent))
      .filter(Boolean);
  }
  const brGroups = new Map();
  doc.querySelectorAll('br').forEach((br) => {
    const par = br.parentElement; if (par) brGroups.set(par, (brGroups.get(par) || 0) + 1);
  });
  let bestBr = null; let n = 0;
  brGroups.forEach((c, el) => { if (c > n) { n = c; bestBr = el; } });
  if (bestBr && n >= 5) {
    const clone = bestBr.cloneNode(true);
    clone.querySelectorAll('script,style,noscript').forEach((x) => x.remove());
    clone.querySelectorAll('br').forEach((br) => br.replaceWith('\n'));
    return clone.textContent.split('\n').map(clean).filter(Boolean);
  }
  return [];
}

function heading(doc) {
  const hs = doc.querySelectorAll('h1,h2,h3,h4');
  for (const h of hs) {
    const t = clean(h.textContent);
    if (/chap|ch\.?\s*\d|episode|part\s*\d/i.test(t) && t.length < 150) return t;
  }
  return '';
}

function links(doc, base) {
  const res = { next: null, prev: null };
  const best = { next: 0, prev: 0 };
  const baseNoHash = base.split('#')[0];
  function consider(kind, href, sc) {
    if (sc <= best[kind]) return;
    try {
      const u = new URL(href, base).href;
      if (u.split('#')[0] === baseNoHash || !/^https?:/.test(u)) return;
      res[kind] = u; best[kind] = sc;
    } catch (_) {}
  }
  doc.querySelectorAll('link[rel][href]').forEach((l) => {
    const rel = (l.getAttribute('rel') || '').toLowerCase();
    if (/\bnext\b/.test(rel)) consider('next', l.getAttribute('href'), 3);
    if (/\bprev(ious)?\b/.test(rel)) consider('prev', l.getAttribute('href'), 3);
  });
  doc.querySelectorAll('a[href]').forEach((a) => {
    const href = a.getAttribute('href');
    if (!href || href.charAt(0) === '#' || /^javascript:/i.test(href)) return;
    const cls = (a.getAttribute('class') || '').toLowerCase();
    if (/\bdisabled\b/.test(cls) || a.getAttribute('aria-disabled') === 'true') return;
    const t = (clean(a.textContent) + ' ' + (a.getAttribute('title') || '') + ' ' +
      (a.getAttribute('aria-label') || '') + ' ' + (a.id || '') + ' ' + cls).toLowerCase();
    const rel = (a.getAttribute('rel') || '').toLowerCase();
    const chap = /chap|\bch\b/.test(t) ? 1 : 0;
    const sn = (/\bnext\b/.test(rel) ? 3 : 0) + (/next/.test(t) ? 2 : 0);
    if (sn) consider('next', href, sn + chap);
    const sp = (/\bprev/.test(rel) ? 3 : 0) + (/prev/.test(t) ? 2 : 0);
    if (sp) consider('prev', href, sp + chap);
  });
  return res;
}

/** Splits a paragraph into short pieces the voice handles well. */
function chunks(text, max = 300) {
  const parts = text.match(/[^.!?…]+[.!?…]+["'”’)\]]*\s*|[^.!?…]+$/g) || [text];
  const sentences = [];
  for (let s of parts) {
    s = s.trim(); if (!s) continue;
    while (s.length > 350) {
      let cut = s.lastIndexOf(', ', 350);
      if (cut < 100) cut = s.lastIndexOf(' ', 350);
      if (cut < 100) cut = 350;
      sentences.push(s.slice(0, cut + 1).trim());
      s = s.slice(cut + 1).trim();
    }
    if (s) sentences.push(s);
  }
  const out = []; let cur = '';
  for (const s of sentences) {
    if (cur && cur.length + s.length + 1 > max) { out.push(cur); cur = s; } else cur = cur ? cur + ' ' + s : s;
  }
  if (cur) out.push(cur);
  return out;
}

/** Reads a fetched page into a chapter object, or null if no story text was found. */
function parseChapter(html, url) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const paras = paragraphs(doc);
  if (!paras.length) return null;
  const l = links(doc, url);
  return { url, title: clean(doc.title), heading: heading(doc), paras, next: l.next, prev: l.prev };
}

module.exports = { parseChapter, chunks, clean };
