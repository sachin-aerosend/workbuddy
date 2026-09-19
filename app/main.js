// WorkBuddy: a pixel cat that lives on your taskbar, keeps you company while you type,
// swats away time-wasting tabs and nudges you to close stale ones.
const path = require('path');
const fs = require('fs');
const { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, screen, powerMonitor, shell, Notification } = require('electron');
const settings = require('./settings');
const reminders = require('./reminders');
const { Brain } = require('./brain');
const { Bridge } = require('./bridge');
const { fullscreenMonitor, foregroundWindow, mouseButtonDown } = require('./win32');

const WIN_W = 300, WIN_H = 340; // cat window (DIP): room for the cat, ball and a bubble/menu above
const ICON = path.join(__dirname, '..', 'assets', 'icon.ico');
const EXTENSION_DIR = app.isPackaged ? path.join(process.resourcesPath, 'extension') : path.join(__dirname, '..', 'extension');

if (!app.requestSingleInstanceLock()) app.quit();
app.setAppUserModelId('com.workbuddy.cat');
// Keep crash dumps locally (never uploaded) so a crash can be diagnosed afterwards.
require('electron').crashReporter.start({ uploadToServer: false });

// Diagnostic log (%APPDATA%\WorkBuddy\workbuddy.log): event types and tab ids only, never URLs or keys.
const LOG = path.join(app.getPath('userData'), 'workbuddy.log');
function log(...parts) {
  try {
    if (fs.existsSync(LOG) && fs.statSync(LOG).size > 512e3) fs.renameSync(LOG, LOG + '.old');
    fs.appendFileSync(LOG, `${new Date().toISOString()} ${parts.join(' ')}\n`);
  } catch {}
}

let win, tray, brain, bridge;
let quitting = false;
const timers = [];
const alive = () => !quitting && win && !win.isDestroyed();

// Unexpected errors go to the log instead of a crash dialog popping up mid-call.
process.on('uncaughtException', err => log('uncaught:', String(err && err.stack || err).split('\n').slice(0, 4).join(' | ')));
process.on('unhandledRejection', err => log('unhandled rejection:', String(err && err.stack || err).split('\n').slice(0, 4).join(' | ')));
let pausedUntil = 0, hidden = false, fullscreenHide = false, browserConnected = false;
let lastBounds = '', lastState = '', focusable = false;

function displays() {
  return screen.getAllDisplays().sort((a, b) => a.bounds.x - b.bounds.x);
}

function createWindow() {
  win = new BrowserWindow({
    width: WIN_W, height: WIN_H, show: false, frame: false, transparent: true, resizable: false,
    skipTaskbar: true, alwaysOnTop: true, focusable: false, hasShadow: false, icon: ICON,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, backgroundThrottling: false },
  });
  win.setAlwaysOnTop(true, 'screen-saver');   // above the taskbar
  applyScreenShare();
  win.setIgnoreMouseEvents(true, { forward: true });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.on('console-message', (e) => { if (e.level === 'error' || e.level === 'warning') log('renderer console:', e.level, String(e.message).slice(0, 300)); });
  // If the drawing process dies or hangs, reload it so the cat comes back instead of vanishing.
  win.webContents.on('render-process-gone', (_e, d) => { log('renderer gone:', d.reason, `exit=${d.exitCode}`); reviveRenderer('gone'); });
  win.webContents.on('unresponsive', () => { log('renderer unresponsive'); reviveRenderer('unresponsive'); });
  win.webContents.on('did-finish-load', () => { lastBeat = Date.now(); lastState = ''; lastBounds = ''; });
  win.once('ready-to-show', () => win.showInactive());
  // Something closed the cat's window (an installer/updater, Windows shutting down, Alt+F4):
  // exit cleanly instead of running on without a window.
  win.on('close', () => { if (!quitting) { log('cat window closed from outside: quitting'); quitting = true; app.quit(); } });
}

// Screen-share visibility: hidden from shares/recordings unless the user turns it off.
// (WB_CAPTURE=1 always shows it, for screenshots while developing.)
function applyScreenShare() {
  if (win && !win.isDestroyed()) win.setContentProtection(!process.env.WB_CAPTURE && settings.get().hideFromScreenShare !== false);
}
function toggleScreenShare() {
  const hide = settings.get().hideFromScreenShare === false; // flip
  settings.save({ hideFromScreenShare: hide });
  applyScreenShare();
  log('screen share', hide ? 'hidden' : 'visible');
  brain.say(hide ? 'hidden from screen share again 🙈' : 'people on your call can see me now 📺', 2.8);
  refreshTray();
}

let lastBeat = Date.now(), lastRevive = 0;
function reviveRenderer(why) {
  if (!win || win.isDestroyed() || Date.now() - lastRevive < 3000) return;
  lastRevive = Date.now();
  log('reviving renderer:', why);
  lastState = ''; lastBounds = ''; hitboxes = { cat: null, bubble: null };
  setTimeout(() => { if (!win.isDestroyed()) win.webContents.reload(); }, 300);
}
// Renderer heartbeat: if the page goes silent while the cat should be showing, reload it.
function watchdog() {
  if (alive() && win.isVisible() && Date.now() - lastBeat > 8000) reviveRenderer('no heartbeat');
}

// Bring the cat to the screen the mouse is on (used when relaunched and when an alarm rings).
function summonCat(say) {
  hidden = false; pausedUntil = 0;
  const c = screen.getCursorScreenPoint();
  const w = screen.getDisplayNearestPoint(c).workArea;
  if (brain.displayAt(brain.x).workArea.x !== w.x || brain.x < w.x || brain.x > w.x + w.width) {
    brain.x = Math.min(Math.max(c.x, w.x + 80), w.x + w.width - 80);
    brain.y = w.y + w.height;
  }
  if (say) brain.say(say, 2.5);
  refreshTray();
}

// Where the tab sits on screen, estimated from the browser window's bounds and tab index.
function tabPoint({ win: w, tabIndex = 0, tabCount = 1 }) {
  if (!w || w.state === 'minimized') return null;
  const maximized = w.state === 'maximized' || w.state === 'fullscreen';
  const inset = maximized ? 8 : 0;
  const stripW = Math.max(200, w.width - 2 * inset - 260);
  const tabW = Math.min(240, stripW / Math.max(1, tabCount));
  const x = w.left + inset + 90 + tabW * (tabIndex + 0.5);
  const d = screen.getDisplayNearestPoint({ x: Math.round(x), y: Math.round(w.top + inset + 20) });
  // Feet on the toolbar just under the tab strip, so the whole cat stays on screen while it swipes.
  const y = Math.max(d.bounds.y + 44, w.top + inset + 40) + 76;
  return { x: Math.round(x), y: Math.round(y) };
}

const catHeight = () => 32 * (settings.get().catScale || 2.5);

// Top edge of the active window (DIP) for tab-strip strolls, refreshed twice a second.
let perchCache = null;
function updatePerch() {
  const fg = foregroundWindow();
  if (!fg) { perchCache = null; return; }
  const r = screen.screenToDipRect(null, { x: fg.left, y: fg.top, width: fg.right - fg.left, height: fg.bottom - fg.top });
  const d = screen.getDisplayMatching(r).workArea;
  const x0 = Math.max(r.x, d.x), x1 = Math.min(r.x + r.width, d.x + d.width);
  // Stand on the window's top edge; if that would push the cat off the screen (maximized windows),
  // stand just low enough that the whole cat shows, walking over the tab strip.
  const y = Math.round(Math.max(r.y + 1, d.y + catHeight() + 6));
  perchCache = x1 - x0 >= 240 && y < d.y + d.height - 160 ? { x0, x1, y } : null;
}

const catVisible = () => !hidden && !fullscreenHide && Date.now() > pausedUntil;

function onCloseTab(tabId, reason) { log('-> closeTab', tabId, reason); bridge.broadcast({ type: 'closeTab', tabId, reason }); }

// ---------- right-click menu + reminders ----------
const fmtLeft = ms => {
  const s = Math.max(0, Math.round(ms / 1000));
  return s >= 3600 ? `${Math.floor(s / 3600)}h${String(Math.floor(s / 60) % 60).padStart(2, '0')}` : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};
function openCatMenu() {
  const items = [
    { id: 'remind', label: '⏰ Set a reminder…' },
    { id: 'focus', label: '🍅 Focus for 25 min' },
    ...reminders.all().map(r => ({ id: `cancel:${r.id}`, label: `✕ ${fmtLeft(r.due - Date.now())} · ${r.text.slice(0, 22)}`, kind: 'cancel' })),
    { id: 'ball', label: '🧶 Play with the ball' },
    { id: 'patrol', label: '🐾 Walk on my tabs' },
    { id: 'nap', label: '😴 Nap time' },
    settings.get().hideFromScreenShare === false
      ? { id: 'share', label: '🙈 Hide me from screen share' }
      : { id: 'share', label: '📺 Show me on screen share' },
    { id: 'away', label: '💤 Hide for 1 hour' },
  ];
  brain.openMenu(items);
}
function addReminder(text, minutes) {
  const r = reminders.add(text, minutes);
  log('reminder set', r.id, `${minutes}min`);
  return r;
}
function checkReminders() {
  for (const r of reminders.takeDue()) {
    log('reminder due', r.id);
    summonCat(); // come to whichever screen you're looking at
    brain.alarm(r);
    if (Notification.isSupported()) {
      const n = new Notification({ title: 'WorkBuddy ⏰', body: r.text, icon: ICON, silent: false });
      n.on('click', () => { hidden = false; });
      n.show();
    }
  }
  const next = reminders.next();
  brain.setCountdown(next ? Math.max(0, (next.due - Date.now()) / 1000) : null);
}
function onMenuChoice(id) {
  if (id === 'remind') return brain.openForm();
  if (id === 'focus') { addReminder('Focus session done! Stretch and take 5 🐾', 25); return brain.say('focus mode! 25:00 🍅', 2.5); }
  if (id.startsWith('cancel:')) { reminders.remove(id.slice(7)); return brain.say('okay, cancelled', 2); }
  if (id === 'ball') return brain.interrupt(brain.grounded(brain.ballPlay()), 'life');
  if (id === 'patrol') return brain.interrupt(brain.grounded(brain.tabPatrol()), 'life');
  if (id === 'nap') return brain.interrupt(brain.nap(60), 'life');
  if (id === 'share') return toggleScreenShare();
  if (id === 'away') { pausedUntil = Date.now() + 3600e3; refreshTray(); }
}

function onBubbleAnswer(b, answer) {
  log('bubble answer', b.kind, typeof answer === 'object' ? 'form' : answer, b.tabId ?? '', `clients=${bridge.clients.size}`);
  if (b.kind === 'menu' && typeof answer === 'string' && answer !== 'close') return onMenuChoice(answer);
  if (b.kind === 'form' && answer && typeof answer === 'object') {
    const minutes = Math.round(Number(answer.minutes));
    if (!(minutes >= 1 && minutes <= 1440)) return brain.say('pick 1 min to 24 h ⏰', 2.5);
    addReminder(answer.text, minutes);
    return brain.say(`got it! I’ll remind you in ${minutes >= 60 ? `${+(minutes / 60).toFixed(1)} h` : `${minutes} min`} ⏰`, 2.8);
  }
  if (b.kind === 'alarm') {
    if (answer === 'snooze') { addReminder(b.remText, 5); brain.say('5 more minutes… 😴', 2); }
    return;
  }
  if (b.kind === 'undo' && answer === 'undo') bridge.broadcast({ type: 'undo', sessionId: b.sessionId });
  if (b.kind !== 'ask') return;
  if (answer === 'yes') bridge.broadcast({ type: 'closeTab', tabId: b.tabId, reason: 'nudge' });
  if (answer === 'later') bridge.broadcast({ type: 'snooze', tabId: b.tabId, minutes: 120 });
  if (answer === 'no' && b.domain) {
    const s = settings.get();
    if (!s.neverAskDomains.includes(b.domain)) settings.save({ neverAskDomains: [...s.neverAskDomains, b.domain] });
    sendConfig();
  }
}

function sendConfig(ws) {
  const s = settings.get();
  const cfg = {
    type: 'config',
    rules: s.blockRules.filter(r => r.on).map(r => r.match.toLowerCase()),
    idleTabMinutes: s.idleTabMinutes,
    skipDomains: [...s.safeDomains, ...s.neverAskDomains],
  };
  ws ? bridge.send(ws, cfg) : bridge.broadcast(cfg);
}

function onBridgeMessage(msg, ws) {
  const s = settings.get();
  if (msg.type !== 'hello') log('<-', msg.type, msg.tabId ?? '', msg.reason ?? '', msg.idleMinutes ? `${msg.idleMinutes}min` : '');
  switch (msg.type) {
    case 'hello': sendConfig(ws); break;
    case 'blocked': {
      settings.bump('blocked');
      if (!catVisible()) { bridge.send(ws, { type: 'closeTab', tabId: msg.tabId, reason: 'blocked' }); break; }
      bridge.send(ws, { type: 'hold', tabId: msg.tabId, ms: 7000 }); // the cat is on its way
      brain.mission(msg.tabId, tabPoint(msg));
      break;
    }
    case 'idleTab': {
      const cooled = brain.t - brain.lastNudgeAt > s.nudgeCooldownMinutes * 60;
      if (catVisible() && cooled && !brain.pendingNudge && brain.mode !== 'nudge') brain.queueNudge(msg);
      break;
    }
    case 'closed':
      if (msg.reason === 'nudge') { settings.bump('tidied'); brain.showUndo('tidied up! 🧹', msg.sessionId); }
      break;
    case 'closeFailed':
      log('close failed', msg.tabId, msg.why || '');
      if (msg.reason === 'nudge') brain.say(msg.why === 'gone' ? 'hm, that tab is already gone' : 'couldn’t close that one 😿', 3);
      break;
  }
  refreshTray();
}

// ---------- main loop ----------
let lastTick = Date.now();
function loop() {
  if (!alive()) return;
  const now = Date.now();
  const dt = (now - lastTick) / 1000;
  lastTick = now;
  if (brain.mode === 'held') {
    const c = screen.getCursorScreenPoint();
    brain.holdAt(c.x, c.y);
    if (mouseButtonDown() === false) brain.release();
  }
  checkReminders();
  brain.tick(dt, powerMonitor.getSystemIdleTime());

  // The reminder form needs keyboard focus; everything else stays non-focusable so it never steals it.
  const wantFocus = brain.bubble?.kind === 'form';
  if (wantFocus !== focusable) {
    focusable = wantFocus;
    win.setFocusable(wantFocus);
    win.setSkipTaskbar(true); // becoming focusable makes Windows add a taskbar button; keep it hidden
    if (wantFocus) { win.focus(); win.webContents.focus(); }
  }

  const visible = catVisible();
  if (visible !== win.isVisible()) {
    if (visible) { lastBeat = Date.now(); win.showInactive(); } else win.hide();
  }
  if (!visible) return;

  // Near the top of a screen there's no room for a bubble above the cat: put the cat at the top of
  // its window and the bubble underneath instead.
  const d = screen.getDisplayNearestPoint({ x: Math.round(brain.x), y: Math.round(brain.y - 10) }).bounds;
  const bubbleH = { menu: 60 + 27 * (brain.bubble?.buttons?.length || 0), form: 190 }[brain.bubble?.kind] || 120;
  const below = !!brain.bubble && brain.y - catHeight() - bubbleH < d.y;
  const catBottom = below ? Math.ceil(catHeight()) + 4 : WIN_H;
  const b = { x: Math.round(brain.x - WIN_W / 2), y: Math.round(brain.y - catBottom), width: WIN_W, height: WIN_H };
  const key = `${b.x},${b.y}`;
  if (key !== lastBounds) { win.setBounds(b); lastBounds = key; }  // fixed size guards against DPI resizes
  updateClickable(b);
  // How much of the window hangs off the screen's sides, so the bubble can slide back into view.
  const offL = Math.max(0, d.x - b.x), offR = Math.max(0, b.x + WIN_W - (d.x + d.width));
  const st = JSON.stringify({ ...brain.state(), scale: settings.get().catScale || 2.5, catBottom, below, offL, offR });
  if (st !== lastState) { win.webContents.send('state', st); lastState = st; }
}

// The window is click-through except over the cat and its bubble. Decided here from the OS cursor
// position (reliable across mixed-DPI monitors) against hit boxes the renderer reports.
let hitboxes = { cat: null, bubble: null }, clickable = false;
const inside = (p, r) => r && p.x >= r.x && p.x < r.x + r.w && p.y >= r.y && p.y < r.y + r.h;
function updateClickable(b) {
  const c = screen.getCursorScreenPoint();
  const p = { x: c.x - b.x, y: c.y - b.y };
  const want = brain.mode === 'held' || !!inside(p, hitboxes.bubble) || !!inside(p, hitboxes.cat);
  if (process.env.WB_PROBE && hitboxes.bubble && Date.now() % 1000 < 40) log('probe p', JSON.stringify(p), 'bubble', JSON.stringify(hitboxes.bubble), 'win', JSON.stringify(b));
  // Clicking anywhere else closes an open menu / form, like a normal popup.
  const k = brain.bubble?.kind;
  if ((k === 'menu' || k === 'form') && !want && mouseButtonDown()) brain.closeBubble();
  if (want !== clickable) {
    clickable = want; win.setIgnoreMouseEvents(!want, { forward: true });
    if (process.env.WB_PROBE) log('clickable', want);
  }
}

function checkFullscreen() {
  if (!settings.get().hideInFullscreen) { fullscreenHide = false; return; }
  const m = fullscreenMonitor();
  if (!m) { fullscreenHide = false; return; }
  const p = screen.dipToScreenPoint({ x: Math.round(brain.x), y: Math.round(brain.y - 20) });
  fullscreenHide = p.x >= m.left && p.x < m.right && p.y >= m.top && p.y < m.bottom;
}

// ---------- tray ----------
function refreshTray() {
  if (!tray) return;
  const s = settings.get(), st = settings.stats();
  const paused = Date.now() < pausedUntil;
  tray.setToolTip(`WorkBuddy 🐾  blocked today: ${st.blockedToday} · tabs tidied: ${st.tidiedToday}`);
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: `WorkBuddy 🐾`, enabled: false },
    { label: `Blocked today: ${st.blockedToday}   ·   Tabs tidied: ${st.tidiedToday}`, enabled: false },
    { label: browserConnected ? 'Browser: connected ✓' : 'Browser: not connected (install the extension)', enabled: false },
    { type: 'separator' },
    { label: hidden ? 'Show cat' : 'Hide cat', click: () => { hidden = !hidden; refreshTray(); } },
    paused
      ? { label: 'Resume cat now', click: () => { pausedUntil = 0; refreshTray(); } }
      : { label: 'Send cat away for 1 hour', click: () => { pausedUntil = Date.now() + 3600e3; refreshTray(); setTimeout(refreshTray, 3600e3 + 1000); } },
    { label: 'Set a reminder…', click: () => { hidden = false; pausedUntil = 0; brain.openForm(); } },
    { label: 'Play with the ball', click: () => brain.interrupt(brain.grounded(brain.ballPlay()), 'life') },
    { label: 'Walk on my tabs', click: () => brain.interrupt(brain.grounded(brain.tabPatrol()), 'life') },
    { label: 'Nap time', click: () => brain.interrupt(brain.nap(60), 'life') },
    { label: 'Zoomies!', click: () => brain.interrupt(brain.grounded(brain.zoomies()), 'life') },
    { type: 'separator' },
    { label: 'Hide from screen share', type: 'checkbox', checked: s.hideFromScreenShare !== false, click: () => toggleScreenShare() },
    { label: 'Typing buddy', type: 'checkbox', checked: s.typingBuddy, click: m => { settings.save({ typingBuddy: m.checked }); } },
    { label: 'Start with Windows', type: 'checkbox', checked: s.startWithWindows, click: m => { settings.save({ startWithWindows: m.checked }); applyLogin(); } },
    { label: 'Edit settings (blocked sites, timings)…', click: () => { settings.ensureFile(); shell.openPath(settings.path()); } },
    { label: 'Install browser extension…', click: () => showExtensionHelp() },
    { type: 'separator' },
    { label: 'Quit WorkBuddy', click: () => app.quit() },
  ]));
}

function showExtensionHelp() {
  shell.openPath(EXTENSION_DIR);
  const help = path.join(app.getPath('userData'), 'install-extension.txt');
  fs.writeFileSync(help, [
    'Install the WorkBuddy browser extension (one time):',
    '',
    '1. Open edge://extensions  (or chrome://extensions / brave://extensions)',
    '2. Turn on "Developer mode"',
    '3. Click "Load unpacked" and choose this folder:',
    `   ${EXTENSION_DIR}`,
    '4. Done. The tray menu will show "Browser: connected ✓".',
  ].join('\r\n'));
  shell.openPath(help);
}

// Only the installed app registers itself to start with Windows (a dev run must not hijack the entry).
function applyLogin() {
  if (!app.isPackaged) return;
  app.setLoginItemSettings({ openAtLogin: !!settings.get().startWithWindows, path: process.execPath, args: [] });
}

// ---------- boot ----------
app.whenReady().then(() => {
  createWindow();
  brain = new Brain({ displays, settings: settings.get, onCloseTab, onBubbleAnswer, perch: () => perchCache });
  timers.push(setInterval(updatePerch, 500));
  brain.onError = err => log('behaviour crashed:', String(err && err.stack || err).split('\n').slice(0, 3).join(' | '));

  bridge = new Bridge(settings.get().bridgePort);
  bridge.on('message', onBridgeMessage);
  bridge.on('status', c => { browserConnected = c; log('browser', c ? 'connected' : 'disconnected', `clients=${bridge.clients.size}`); refreshTray(); });
  log('start', app.getVersion());

  ipcMain.on('hitboxes', (_e, h) => { hitboxes = h; });
  ipcMain.on('pet', () => { log('pet'); brain.pet(); });
  ipcMain.on('menu', () => openCatMenu());
  ipcMain.on('beat', () => { lastBeat = Date.now(); });
  // Opening WorkBuddy while it's already running brings the cat back to you.
  app.on('second-instance', () => { log('second launch: summoning cat'); summonCat('here I am! 🐾'); });
  timers.push(setInterval(watchdog, 2000));
  // Dev: WB_CRASHTEST=1 crashes the renderer after 6 s to prove it recovers.
  if (process.env.WB_CRASHTEST) setTimeout(() => { log('crash test: killing renderer'); win.webContents.forcefullyCrashRenderer(); }, 6000);
  ipcMain.on('log', (_e, msg) => log('renderer:', String(msg).slice(0, 200)));
  ipcMain.on('drag-start', () => {
    const c = screen.getCursorScreenPoint();
    brain.grab(c.x, c.y, catHeight());
  });
  ipcMain.on('drag-end', () => brain.release());
  ipcMain.on('answer', (_e, id) => brain.answerBubble(id));

  try {
    const { uIOhook } = require('uiohook-napi');
    uIOhook.on('keydown', e => brain.onKey(e.keycode)); // only which key position, never logged or stored
    uIOhook.start();
    app.on('will-quit', () => uIOhook.stop());
  } catch (err) { console.warn('[workbuddy] keyboard hook unavailable:', err.message); }

  tray = new Tray(nativeImage.createFromPath(ICON));
  tray.on('click', () => brain.pet());
  refreshTray();
  applyLogin();

  fs.watchFile(settings.path(), { interval: 2000 }, () => { settings.reload(); sendConfig(); applyScreenShare(); refreshTray(); });
  screen.on('display-removed', () => { brain.x = brain.clampX(brain.x); });

  // Dev: WB_PROBE=1 logs where the bubble's first button is on screen (physical px) for click tests.
  if (process.env.WB_PROBE) setInterval(async () => {
    if (!brain.bubble || !win.isVisible()) return;
    const r = await win.webContents.executeJavaScript(`(() => { const b = document.querySelector('#bubble button'); if (!b) return null; const r = b.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; })()`);
    if (!r) return;
    const wb = win.getBounds();
    const p = screen.dipToScreenPoint({ x: Math.round(wb.x + r.x), y: Math.round(wb.y + r.y) });
    log('probe yes-button', p.x, p.y);
  }, 1000);

  // Dev: WB_UI=menu|form|clock|alarm puts the cat on the primary screen and opens that UI (for screenshots).
  if (process.env.WB_UI) setTimeout(() => {
    const w = screen.getPrimaryDisplay().workArea;
    brain.x = w.x + w.width - 260; brain.y = w.y + w.height;
    brain.interrupt(brain.sit(), 'life');
    const ui = process.env.WB_UI;
    if (ui === 'clock' || ui === 'menu') addReminder('finish the proposal', 10);
    if (ui === 'menu') setTimeout(openCatMenu, 800);
    if (ui === 'form') brain.openForm();
    if (ui === 'alarm') brain.alarm({ id: 'demo', text: 'finish the proposal' });
  }, 1500);

  // Dev: WB_DEMO=<behaviour> starts a specific behaviour, e.g. WB_DEMO=visitMonitor.
  if (process.env.WB_DEMO && typeof brain[process.env.WB_DEMO] === 'function') {
    setTimeout(() => brain.interrupt(brain.grounded(brain[process.env.WB_DEMO]()), 'life'), 1500);
  }

  timers.push(setInterval(loop, 33), setInterval(checkFullscreen, 1500), setInterval(() => alive() && refreshTray(), 60e3));
});

app.on('window-all-closed', e => e.preventDefault());
app.on('before-quit', () => {
  quitting = true;
  timers.forEach(clearInterval);
  if (bridge) bridge.close();
});
