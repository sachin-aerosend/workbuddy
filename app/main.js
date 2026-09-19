// WorkBuddy: a pixel cat that lives on your taskbar, keeps you company while you type,
// swats away time-wasting tabs and nudges you to close stale ones.
const path = require('path');
const fs = require('fs');
const { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, screen, powerMonitor, shell } = require('electron');
const settings = require('./settings');
const { Brain } = require('./brain');
const { Bridge } = require('./bridge');
const { fullscreenMonitor, foregroundWindow, mouseButtonDown } = require('./win32');

const WIN_W = 280, WIN_H = 230; // cat window (DIP): room for the cat, ball and a bubble above
const ICON = path.join(__dirname, '..', 'assets', 'icon.ico');
const EXTENSION_DIR = app.isPackaged ? path.join(process.resourcesPath, 'extension') : path.join(__dirname, '..', 'extension');

if (!app.requestSingleInstanceLock()) app.quit();
app.setAppUserModelId('com.workbuddy.cat');

// Diagnostic log (%APPDATA%\WorkBuddy\workbuddy.log): event types and tab ids only, never URLs or keys.
const LOG = path.join(app.getPath('userData'), 'workbuddy.log');
function log(...parts) {
  try {
    if (fs.existsSync(LOG) && fs.statSync(LOG).size > 512e3) fs.renameSync(LOG, LOG + '.old');
    fs.appendFileSync(LOG, `${new Date().toISOString()} ${parts.join(' ')}\n`);
  } catch {}
}

let win, tray, brain, bridge;
let pausedUntil = 0, hidden = false, fullscreenHide = false, browserConnected = false;
let lastBounds = '', lastState = '';

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
  // Invisible in screen shares and recordings (WB_CAPTURE=1 turns this off for screenshots while developing).
  if (!process.env.WB_CAPTURE) win.setContentProtection(true);
  win.setIgnoreMouseEvents(true, { forward: true });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.on('console-message', (e) => { if (e.level === 'error' || e.level === 'warning') log('renderer console:', e.level, String(e.message).slice(0, 300)); });
  win.webContents.on('render-process-gone', (_e, d) => log('renderer gone:', d.reason));
  win.once('ready-to-show', () => win.showInactive());
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

function onBubbleAnswer(b, answer) {
  log('bubble answer', b.kind, answer, b.tabId ?? '', `clients=${bridge.clients.size}`);
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
  const now = Date.now();
  const dt = (now - lastTick) / 1000;
  lastTick = now;
  if (brain.mode === 'held') {
    const c = screen.getCursorScreenPoint();
    brain.holdAt(c.x, c.y);
    if (mouseButtonDown() === false) brain.release();
  }
  brain.tick(dt, powerMonitor.getSystemIdleTime());

  const visible = catVisible();
  if (visible !== win.isVisible()) visible ? win.showInactive() : win.hide();
  if (!visible) return;

  // Near the top of a screen there's no room for a bubble above the cat: put the cat at the top of
  // its window and the bubble underneath instead.
  const d = screen.getDisplayNearestPoint({ x: Math.round(brain.x), y: Math.round(brain.y - 10) }).bounds;
  const below = !!brain.bubble && brain.y - catHeight() - 120 < d.y;
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
    { label: 'Play with the ball', click: () => brain.interrupt(brain.grounded(brain.ballPlay()), 'life') },
    { label: 'Walk on my tabs', click: () => brain.interrupt(brain.grounded(brain.tabPatrol()), 'life') },
    { label: 'Nap time', click: () => brain.interrupt(brain.nap(60), 'life') },
    { label: 'Zoomies!', click: () => brain.interrupt(brain.grounded(brain.zoomies()), 'life') },
    { type: 'separator' },
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
  setInterval(updatePerch, 500);

  bridge = new Bridge(settings.get().bridgePort);
  bridge.on('message', onBridgeMessage);
  bridge.on('status', c => { browserConnected = c; log('browser', c ? 'connected' : 'disconnected', `clients=${bridge.clients.size}`); refreshTray(); });
  log('start', app.getVersion());

  ipcMain.on('hitboxes', (_e, h) => { hitboxes = h; });
  ipcMain.on('pet', () => { log('pet'); brain.pet(); });
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

  fs.watchFile(settings.path(), { interval: 2000 }, () => { settings.reload(); sendConfig(); refreshTray(); });
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

  // Dev: WB_DEMO=<behaviour> starts a specific behaviour, e.g. WB_DEMO=visitMonitor.
  if (process.env.WB_DEMO && typeof brain[process.env.WB_DEMO] === 'function') {
    setTimeout(() => brain.interrupt(brain.grounded(brain[process.env.WB_DEMO]()), 'life'), 1500);
  }

  setInterval(loop, 33);
  setInterval(checkFullscreen, 1500);
  setInterval(refreshTray, 60e3);
});

app.on('window-all-closed', e => e.preventDefault());
app.on('before-quit', () => bridge && bridge.close());
