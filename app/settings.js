// Settings + stats persisted as JSON in the app's userData folder.
const fs = require('fs');
const path = require('path');
const { app } = require('electron');

const DEFAULTS = {
  // URL fragments the cat will close on sight. `on: false` keeps a rule handy without enforcing it.
  blockRules: [
    { name: 'Instagram Reels', match: 'instagram.com/reels', on: true },
    { name: 'Instagram Reel', match: 'instagram.com/reel/', on: true },
    { name: 'YouTube Shorts', match: 'youtube.com/shorts', on: true },
    { name: 'Facebook Reels', match: 'facebook.com/reel', on: true },
    { name: 'TikTok', match: 'tiktok.com', on: false },
  ],
  idleTabMinutes: 60,          // a tab untouched this long gets a "close it?" bubble
  nudgeCooldownMinutes: 25,    // at most one bubble per this many minutes
  safeDomains: [               // never suggest closing these
    'mail.google.com', 'calendar.google.com', 'meet.google.com', 'docs.google.com',
    'app.hubspot.com', 'notion.so', 'app.slack.com', 'teams.microsoft.com',
    'zoom.us', 'claude.ai', 'outlook.office.com', 'outlook.live.com',
  ],
  neverAskDomains: [],         // filled when you answer "nope" to a bubble
  catScale: 2.5,               // 32px art * 2.5 = 80px cat
  speed: 1,                    // walking speed multiplier
  typingBuddy: true,           // tiny keyboard when you type
  sleepAfterIdleMinutes: 4,    // naps when you step away
  hideInFullscreen: true,
  hideFromScreenShare: true,   // invisible in screen shares and recordings (you still see it)
  startWithWindows: true,
  waterEnabled: true,          // the cat drinks and asks if you had a glass of water
  waterEveryMinutes: 30,       // ...every this many minutes at the desk (time away doesn't count)
  bridgePort: 47821,
};

const dir = () => app.getPath('userData');
const file = name => path.join(dir(), name);

function readJson(name, fallback) {
  try { return JSON.parse(fs.readFileSync(file(name), 'utf8')); } catch { return fallback; }
}
function writeJson(name, data) {
  fs.mkdirSync(dir(), { recursive: true });
  fs.writeFileSync(file(name), JSON.stringify(data, null, 2));
}

let settings = { ...DEFAULTS, ...readJson('settings.json', {}) };
const STATS0 = { day: '', blockedToday: 0, tidiedToday: 0, waterToday: 0, blockedTotal: 0, tidiedTotal: 0, waterTotal: 0 };
let stats = { ...STATS0, ...readJson('stats.json', {}) };

function rollDay() {
  const d = new Date(); // local calendar day, so "today" resets at the user's midnight
  const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  if (stats.day !== today) Object.assign(stats, { day: today, blockedToday: 0, tidiedToday: 0, waterToday: 0 });
}

module.exports = {
  get: () => settings,
  path: () => file('settings.json'),
  save(patch = {}) { settings = { ...settings, ...patch }; writeJson('settings.json', settings); return settings; },
  reload() { settings = { ...DEFAULTS, ...readJson('settings.json', {}) }; return settings; },
  ensureFile() { if (!fs.existsSync(file('settings.json'))) writeJson('settings.json', settings); },
  stats() { rollDay(); return stats; },
  bump(kind) {
    rollDay();
    stats[`${kind}Today`] = (stats[`${kind}Today`] || 0) + 1;
    stats[`${kind}Total`] = (stats[`${kind}Total`] || 0) + 1;
    writeJson('stats.json', stats);
  },
};
