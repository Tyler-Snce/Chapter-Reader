// Uploads captures to Catbox (free file host, gives a link) and/or posts to a Discord channel.
const fs = require('fs');
const path = require('path');

const CATBOX_LIMIT = 200 * 1024 * 1024;
const DISCORD_LIMIT = 10 * 1024 * 1024;   // largest file a Discord webhook accepts without boosts

async function fileBlob(file) {
  const type = /\.png$/i.test(file) ? 'image/png' : /\.jpe?g$/i.test(file) ? 'image/jpeg' : 'video/mp4';
  if (fs.openAsBlob) return fs.openAsBlob(file, { type });
  return new Blob([fs.readFileSync(file)], { type });
}

/** Uploads to Catbox and returns the link. */
async function toCatbox(file) {
  const size = fs.statSync(file).size;
  if (size > CATBOX_LIMIT) throw new Error('the file is over Catbox\'s 200 MB limit (trim it first)');
  const form = new FormData();
  form.append('reqtype', 'fileupload');
  form.append('fileToUpload', await fileBlob(file), path.basename(file));
  const r = await fetch('https://catbox.moe/user/api.php', { method: 'POST', body: form });
  const text = (await r.text()).trim();
  if (!r.ok || !/^https:\/\//.test(text)) throw new Error('Catbox said: ' + text.slice(0, 120));
  return text;
}

function validWebhook(url) {
  return /^https:\/\/(?:ptb\.|canary\.)?discord(?:app)?\.com\/api\/webhooks\/\d+\/[\w-]+/.test(String(url || '').trim());
}

/**
 * Posts to a Discord channel through its webhook. Attaches [file] when it's small enough,
 * and includes [link] (e.g. the Catbox link) in the message. Returns the attachment's link if any.
 */
async function toDiscord(webhook, { content = '', file = null, embed = null } = {}) {
  if (!validWebhook(webhook)) throw new Error('the Discord webhook link isn\'t valid (Channel settings > Integrations > Webhooks > Copy URL)');
  const payload = { content: content.slice(0, 1900), username: 'Staff Toolkit', allowed_mentions: { parse: [] } };
  if (embed) payload.embeds = [embed];
  const url = webhook.trim() + (webhook.includes('?') ? '&' : '?') + 'wait=true';
  let r;
  if (file && fs.statSync(file).size <= DISCORD_LIMIT) {
    const form = new FormData();
    form.append('payload_json', JSON.stringify(payload));
    form.append('files[0]', await fileBlob(file), path.basename(file));
    r = await fetch(url, { method: 'POST', body: form });
  } else {
    r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
  }
  if (!r.ok) throw new Error('Discord said ' + r.status + ': ' + (await r.text()).slice(0, 120));
  const msg = await r.json().catch(() => null);
  return msg && msg.attachments && msg.attachments[0] ? msg.attachments[0].url : null;
}

module.exports = { toCatbox, toDiscord, validWebhook, CATBOX_LIMIT, DISCORD_LIMIT };
