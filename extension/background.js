// WorkBuddy extension: watches tab URLs and idle tabs, talks to the desktop cat over a local WebSocket.
const PORT = 47821;
const DEFAULT_CONFIG = {
  rules: ['instagram.com/reels', 'instagram.com/reel/', 'youtube.com/shorts', 'facebook.com/reel'],
  idleTabMinutes: 60,
  skipDomains: [],
};
const IDLE_SCAN_MS = 5 * 60e3;

let ws = null;
const handling = new Map(); // tabId -> fallback timer while the cat is on its way

// ---------- config ----------
async function config() {
  const { config } = await chrome.storage.local.get('config');
  return { ...DEFAULT_CONFIG, ...(config || {}) };
}
const bare = url => url.toLowerCase().replace(/^https?:\/\/(www\.|m\.)?/, '');
async function blockedRule(url) {
  if (!/^https?:/i.test(url || '')) return null;
  const u = bare(url);
  return (await config()).rules.find(r => u.startsWith(r)) || null; // prefix only, so a search *for* reels isn't blocked
}

// ---------- connection to the cat ----------
function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  try { ws = new WebSocket(`ws://127.0.0.1:${PORT}`); } catch { ws = null; return; }
  ws.onopen = () => { send({ type: 'hello', browser: navigator.userAgent }); badge(true); };
  ws.onclose = () => { ws = null; badge(false); };
  ws.onerror = () => {};
  ws.onmessage = e => { try { onMessage(JSON.parse(e.data)); } catch {} };
}
const connected = () => ws && ws.readyState === WebSocket.OPEN;
function send(msg) { if (connected()) ws.send(JSON.stringify(msg)); }
function badge(on) {
  chrome.action.setBadgeText({ text: on ? '' : 'off' });
  chrome.action.setBadgeBackgroundColor({ color: '#8a7a70' });
  chrome.storage.session.set({ connected: on });
}

async function onMessage(msg) {
  switch (msg.type) {
    case 'config': await chrome.storage.local.set({ config: msg }); break;
    case 'closeTab': await closeTab(msg.tabId, msg.reason); break;
    case 'hold': hold(msg.tabId, msg.ms); break;
    case 'snooze': {
      const { snoozed = {} } = await chrome.storage.session.get('snoozed');
      snoozed[msg.tabId] = Date.now() + msg.minutes * 60e3;
      await chrome.storage.session.set({ snoozed });
      break;
    }
    case 'undo': if (msg.sessionId) chrome.sessions.restore(msg.sessionId).catch(() => {}); break;
  }
}

// ---------- blocking ----------
// Pause any video and blur the page while the cat walks over.
function freezePage() {
  if (window.__wbFrozen) return;
  window.__wbFrozen = true;
  const pause = () => document.querySelectorAll('video, audio').forEach(m => { try { m.pause(); m.muted = true; } catch {} });
  pause();
  window.__wbTimer = setInterval(pause, 250);
  const o = document.createElement('div');
  o.id = '__wb_overlay';
  o.style.cssText = 'position:fixed;inset:0;z-index:2147483647;backdrop-filter:blur(16px);background:rgba(255,250,242,.6);display:flex;align-items:center;justify-content:center;font:650 22px "Segoe UI",system-ui,sans-serif;color:#212121;';
  o.textContent = '🐾 psst… the cat is coming for this tab';
  document.documentElement.appendChild(o);
}
function unfreezePage() {
  clearInterval(window.__wbTimer);
  document.getElementById('__wb_overlay')?.remove();
  window.__wbFrozen = false;
}

function hold(tabId, ms) {
  clearTimeout(handling.get(tabId));
  handling.set(tabId, setTimeout(() => closeTab(tabId, 'blocked'), ms));
}

async function onBlocked(tab) {
  if (handling.has(tab.id)) return;
  handling.set(tab.id, null);
  chrome.scripting.executeScript({ target: { tabId: tab.id }, func: freezePage }).catch(() => {});
  connect();
  if (!connected()) { hold(tab.id, 900); return; }  // no cat around: close it ourselves
  const win = await chrome.windows.get(tab.windowId).catch(() => null);
  const siblings = await chrome.tabs.query({ windowId: tab.windowId });
  send({
    type: 'blocked', tabId: tab.id, url: tab.url,
    win: win && { left: win.left, top: win.top, width: win.width, height: win.height, state: win.state },
    tabIndex: tab.index, tabCount: siblings.length,
  });
  hold(tab.id, 2500); // extended by the app's "hold" reply
}

async function closeTab(tabId, reason) {
  clearTimeout(handling.get(tabId));
  handling.delete(tabId);
  const tab = await chrome.tabs.get(tabId).catch(() => null);
  if (!tab) { send({ type: 'closeFailed', tabId, reason, why: 'gone' }); return; }
  if (reason === 'blocked' && !(await blockedRule(tab.url))) {
    // You already left the page: stand down.
    chrome.scripting.executeScript({ target: { tabId }, func: unfreezePage }).catch(() => {});
    return;
  }
  const siblings = await chrome.tabs.query({ windowId: tab.windowId });
  if (siblings.length === 1) await chrome.tabs.create({ windowId: tab.windowId, active: true }); // keep the window open
  try { await chrome.tabs.remove(tabId); }
  catch (err) { send({ type: 'closeFailed', tabId, reason, why: String(err.message || err).slice(0, 120) }); return; }
  if (reason === 'nudge') {
    const [recent] = await chrome.sessions.getRecentlyClosed({ maxResults: 1 }).catch(() => []);
    send({ type: 'closed', tabId, reason, sessionId: recent?.tab?.sessionId, title: tab.title });
  }
}

chrome.tabs.onUpdated.addListener(async (tabId, info, tab) => {
  const url = info.url || (info.status === 'loading' ? tab.url : null);
  if (url && (await blockedRule(url))) onBlocked(tab);
});
chrome.tabs.onRemoved.addListener(tabId => { clearTimeout(handling.get(tabId)); handling.delete(tabId); });

// ---------- idle tabs ----------
async function scanIdleTabs() {
  const now = Date.now();
  const { lastScan = 0 } = await chrome.storage.session.get('lastScan');
  if (now - lastScan < IDLE_SCAN_MS || !connected()) return;
  await chrome.storage.session.set({ lastScan: now });
  const cfg = await config();
  const { snoozed = {} } = await chrome.storage.session.get('snoozed');
  const skip = host => cfg.skipDomains.some(d => host === d || host.endsWith('.' + d));
  const tabs = await chrome.tabs.query({});
  const stale = tabs.filter(t => {
    if (t.active || t.pinned || t.audible || t.incognito || !/^https?:/.test(t.url || '') || !t.lastAccessed) return false;
    if ((snoozed[t.id] || 0) > now) return false;
    if (skip(new URL(t.url).hostname.replace(/^www\./, ''))) return false;
    return now - t.lastAccessed > cfg.idleTabMinutes * 60e3;
  }).sort((a, b) => a.lastAccessed - b.lastAccessed);
  const t = stale[0];
  if (!t) return;
  send({
    type: 'idleTab', tabId: t.id, title: (t.title || t.url).slice(0, 60),
    domain: new URL(t.url).hostname.replace(/^www\./, ''), idleMinutes: Math.round((now - t.lastAccessed) / 60e3),
  });
}

// ---------- keep-alive ----------
chrome.alarms.create('tick', { periodInMinutes: 0.5 });
chrome.alarms.onAlarm.addListener(() => { connect(); scanIdleTabs(); });
// Also catch blocked pages that were already open before the extension started.
async function sweepOpenTabs() {
  for (const tab of await chrome.tabs.query({})) if (await blockedRule(tab.url)) onBlocked(tab);
}
chrome.runtime.onStartup.addListener(() => { connect(); setTimeout(sweepOpenTabs, 1500); });
chrome.runtime.onInstalled.addListener(() => { connect(); setTimeout(sweepOpenTabs, 1500); });
// Switching back to an already-open blocked tab counts too.
chrome.tabs.onActivated.addListener(async ({ tabId }) => {
  const tab = await chrome.tabs.get(tabId).catch(() => null);
  if (tab && (await blockedRule(tab.url))) onBlocked(tab);
});
connect();
