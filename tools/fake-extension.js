// Dev helper: pretends to be the browser extension so missions/nudges can be tested without a browser.
// usage: node tools/fake-extension.js blocked|nudge|reject
const WebSocket = require('ws');
const mode = process.argv[2] || 'blocked';
const origin = mode === 'reject' ? 'https://evil.example' : 'chrome-extension://abcdefghijklmnopabcdefghijklmnop';
const ws = new WebSocket('ws://127.0.0.1:47821', { origin });
const t0 = Date.now();
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a);

ws.on('open', () => {
  log('connected');
  ws.send(JSON.stringify({ type: 'hello', browser: 'fake' }));
  if (mode === 'blocked') ws.send(JSON.stringify({
    type: 'blocked', tabId: 101, url: 'https://www.instagram.com/reels/abc',
    win: { left: -8, top: -8, width: 1936, height: 1048, state: 'maximized' }, tabIndex: 3, tabCount: 6,
  }));
  if (mode === 'nudge') ws.send(JSON.stringify({ type: 'idleTab', tabId: 202, title: 'LinkedIn | Feed', domain: 'linkedin.com', idleMinutes: 192 }));
});
ws.on('message', m => { const msg = JSON.parse(m); if (msg.type !== 'ping') log('got', JSON.stringify(msg)); });
ws.on('error', e => log('error', e.message));
ws.on('unexpected-response', (_q, res) => log('rejected with HTTP', res.statusCode));
ws.on('close', () => log('closed'));
setTimeout(() => process.exit(0), Number(process.argv[3] || 8000));
