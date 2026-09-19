// Local WebSocket link to the WorkBuddy browser extension (Chrome / Edge / Brave).
// Only extension origins may connect, so ordinary web pages can't talk to the cat.
const { WebSocketServer } = require('ws');
const { EventEmitter } = require('events');

class Bridge extends EventEmitter {
  constructor(port) {
    super();
    this.clients = new Set();
    this.wss = new WebSocketServer({
      host: '127.0.0.1',
      port,
      // Chromium extension origins (Edge shows them as extension://, but the origin is chrome-extension://).
      verifyClient: ({ origin }) => /^(chrome-)?extension:\/\/[a-p]{32}$/.test(origin || ''),
    });
    this.wss.on('error', err => console.warn('[workbuddy] bridge error:', err.message));
    this.wss.on('connection', ws => {
      this.clients.add(ws);
      this.emit('status', this.connected);
      ws.on('message', raw => {
        let msg;
        try { msg = JSON.parse(raw); } catch { return; }
        if (msg && typeof msg.type === 'string') this.emit('message', msg, ws);
      });
      ws.on('close', () => { this.clients.delete(ws); this.emit('status', this.connected); });
      ws.on('error', () => {});
    });
    // Regular pings keep the extension's service worker awake.
    this.pinger = setInterval(() => this.broadcast({ type: 'ping' }), 20000);
  }
  get connected() { return this.clients.size > 0; }
  send(ws, msg) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(msg)); }
  broadcast(msg) { for (const ws of this.clients) this.send(ws, msg); }
  close() { clearInterval(this.pinger); this.wss.close(); }
}

module.exports = { Bridge };
