// The cat's behaviour engine. Runs in the main process at ~30 ticks/sec.
// Each behaviour is a generator that yields once per tick, so behaviours read top-to-bottom
// and can be interrupted by swapping in a new one (missions, typing, petting).
const MANIFEST = require('./renderer/sprites/manifest.json');

const rand = (a, b) => a + Math.random() * (b - a);
const pick = arr => arr[Math.floor(Math.random() * arr.length)];
const animSecs = name => MANIFEST.anims[name].frames / MANIFEST.anims[name].fps;

// Left-hand keys by uiohook keycode (scan codes); everything else counts as right-hand.
const LEFT_KEYS = new Set([1, 2, 3, 4, 5, 6, 15, 16, 17, 18, 19, 20, 29, 30, 31, 32, 33, 34, 41, 42, 44, 45, 46, 47, 48, 56, 58, 3675]);
const KEY_ENTER = 28, KEY_SPACE = 57;

class Brain {
  constructor({ displays, settings, onCloseTab, onBubbleAnswer, perch = () => null }) {
    this.perch = perch;                // () => { x0, x1, y } top edge of the active window, or null
    this.displays = displays;          // () => [{ bounds, workArea }]
    this.settings = settings;          // () => settings object
    this.onCloseTab = onCloseTab;      // (tabId, reason) => void
    this.onBubbleAnswer = onBubbleAnswer; // (bubble, answer) => void
    const d = this.displays()[0].workArea;
    this.x = d.x + d.width - 140;
    this.y = d.y + d.height;
    this.facing = -1;
    this.anim = 'idle'; this.seq = 0; this.frame = null;
    this.fx = []; this.ball = null; this.bubble = null;
    this.t = 0; this.dt = 0;
    this.keys = [];                    // recent keystroke times
    this.lastKey = -1e9;
    this.lastNudgeAt = -1e9;
    this.pendingNudge = null;
    this.userIdleSecs = 0;
    this.task = this.life();
    this.mode = 'life';                // life | typing | mission | nudge | pet | away
  }

  // ---------- plumbing ----------
  setAnim(name, frame = null) {
    if (name !== this.anim) { this.anim = name; this.seq++; }
    this.frame = frame;
  }
  addFx(type, extra = {}) { this.fx.push({ type, t0: this.t, ...extra }); }
  // Any interruption ends ball play (a leftover ball would block typing mode and nudges forever).
  interrupt(gen, mode) { this.task = gen; this.mode = mode; this.ball = null; this.tilt = 0; }
  speedMul() { return this.settings().speed || 1; }

  displayAt(x) {
    const ds = this.displays();
    return ds.find(d => x >= d.workArea.x && x < d.workArea.x + d.workArea.width)
      || ds.reduce((best, d) => (Math.abs(d.workArea.x + d.workArea.width / 2 - x) < Math.abs(best.workArea.x + best.workArea.width / 2 - x) ? d : best));
  }
  ground(x) { const w = this.displayAt(x).workArea; return w.y + w.height; }
  // Keeps x inside the union of displays (so the cat never walks into a gap between monitors).
  clampX(x, margin = 40) {
    const w = this.displayAt(x).workArea;
    return Math.min(Math.max(x, w.x + margin), w.x + w.width - margin);
  }

  tick(dt, userIdleSecs) {
    this.dt = Math.min(dt, 0.1);
    this.t += this.dt;
    this.userIdleSecs = userIdleSecs;
    this.fx = this.fx.filter(f => this.t - f.t0 < (f.life || 1.6));
    this.stepBall();

    // Step away from the desk -> nap. Come back -> wake up.
    const awayAfter = (this.settings().sleepAfterIdleMinutes || 4) * 60;
    if (this.mode === 'life' && userIdleSecs > awayAfter) this.interrupt(this.away(), 'away');

    // Typing buddy.
    if (this.mode === 'typing' && this.t - this.lastKey > 6) this.interrupt(this.afterTyping(), 'life');

    // Deliver a queued "close this tab?" nudge at a calm moment.
    if (this.pendingNudge && this.mode === 'life' && this.t - this.lastKey > 8 && !this.ball) {
      const n = this.pendingNudge; this.pendingNudge = null;
      this.interrupt(this.nudge(n), 'nudge');
    }

    const r = this.task.next();
    if (r.done) { this.task = this.life(); this.mode = 'life'; }
  }

  state() {
    return {
      anim: this.anim, seq: this.seq, frame: this.frame, facing: this.facing,
      fx: this.fx.map(f => ({ type: f.type, age: this.t - f.t0, dx: f.dx || 0 })),
      ball: this.ball && { dx: this.ball.x - this.x, dy: this.ball.y - this.y, spin: this.ball.spin },
      tilt: this.mode === 'held' ? Math.round((this.tilt || 0) * 100) / 100 : 0,
      bubble: this.bubble,
    };
  }

  // ---------- inputs ----------
  onKey(keycode) {
    if (!this.settings().typingBuddy) return;
    this.lastKey = this.t;
    this.keys = this.keys.filter(k => this.t - k < 2).concat(this.t);
    if (this.mode === 'away') this.interrupt(this.wake(), 'life');
    if (this.mode === 'life' && this.keys.length >= 4 && !this.ball) this.interrupt(this.typing(), 'typing');
    if (this.mode === 'typing') {
      const f = keycode === KEY_ENTER ? 3 : keycode === KEY_SPACE ? pick([1, 2]) : LEFT_KEYS.has(keycode) ? 1 : 2;
      this.setAnim('typing', f);
      this.keyUpAt = this.t + 0.12;
    }
  }

  pet() {
    if (this.mode === 'mission' || this.mode === 'nudge') return;
    this.interrupt(this.petted(), 'pet');
  }

  // A blocked page was opened. target = screen point of the tab (or null if unknown).
  mission(tabId, target) {
    this.bubble = null;
    this.hold = null;
    this.interrupt(this.closeTabMission(tabId, target), 'mission');
  }

  queueNudge(n) { this.pendingNudge = n; }

  // ---------- drag & drop ----------
  // Picked up: the cat hangs from the cursor by its scruff (hx,hy = cursor in screen DIP).
  grab(hx, hy, catH) {
    this.bubble = null; this.ball = null;
    this.catH = catH;
    this.hold = { x: hx, y: hy, vx: 0, vy: 0 };
    this.interrupt(this.held(), 'held');
  }
  holdAt(hx, hy) {
    const h = this.hold;
    if (!h || this.mode !== 'held') return;
    const dt = Math.max(this.dt, 1 / 60);
    // smoothed velocity so a flick of the mouse throws the cat
    h.vx = h.vx * 0.5 + ((hx - h.x) / dt) * 0.5;
    h.vy = h.vy * 0.5 + ((hy - h.y) / dt) * 0.5;
    h.x = hx; h.y = hy;
  }
  release() {
    if (this.mode !== 'held' || !this.hold) return;
    const { vx, vy } = this.hold;
    this.hold = null;
    this.interrupt(this.falling(Math.max(-1500, Math.min(1500, vx)), Math.max(-1200, Math.min(1200, vy))), 'life');
  }
  *held() {
    this.setAnim('dangle');
    while (this.hold) {
      this.x = this.hold.x;
      this.y = this.hold.y + this.catH * 0.85; // scruff is near the top of the head
      this.tilt = Math.max(-0.35, Math.min(0.35, -this.hold.vx / 2500));
      yield;
    }
  }
  // Gravity, a little air drag, bounce off screen sides; lands on the taskbar or on the top
  // edge of the active window if it falls onto one.
  *falling(vx, vy) {
    this.tilt = 0;
    const p = this.perch();
    this.setAnim('pounce', 4);
    const startY = this.y;
    while (true) {
      const prevY = this.y;
      vy += 2600 * this.dt;
      vx *= Math.pow(0.6, this.dt);
      this.x += vx * this.dt;
      this.y += vy * this.dt;
      if (Math.abs(vx) > 40) this.facing = Math.sign(vx);
      const w = this.displayAt(this.x).workArea;
      // Thrown past the outer edge of all screens: bounce back (between monitors it just flies across).
      const margin = 20;
      if (!this.displays().some(d => this.x >= d.workArea.x + margin && this.x < d.workArea.x + d.workArea.width - margin)
          && (this.x < w.x + margin || this.x > w.x + w.width - margin)) {
        vx = -vx * 0.5; this.x = Math.min(Math.max(this.x, w.x + margin), w.x + w.width - margin);
      }
      const ceiling = w.y + (this.catH || 80);
      if (this.y < ceiling) { this.y = ceiling; vy = Math.abs(vy) * 0.3; }
      const onPerch = p && vy > 0 && prevY <= p.y && this.y >= p.y && this.x > p.x0 + 20 && this.x < p.x1 - 20;
      const g = this.ground(this.x);
      if (onPerch || this.y >= g) {
        this.y = onPerch ? p.y : g;
        break;
      }
      yield;
    }
    const drop = this.y - startY;
    this.setAnim('pounce', 6);
    yield* this.wait(0.25);
    if (drop > 350) { this.addFx('bang', { life: 0.8 }); yield* this.play('surprised', 0.6); yield* this.play('groom', animSecs('groom')); }
    else yield* this.play('happy', 0.8);
    if (!this.onGround()) yield* this.stroll(p);   // landed on a window: have a look around up there
  }

  answerBubble(answer) {
    if (!this.bubble) return;
    const b = this.bubble;
    this.bubble = null;
    this.bubbleAnswer = { b, answer };
    this.onBubbleAnswer(b, answer);
  }

  showUndo(text, sessionId) {
    this.bubble = { kind: 'undo', text, sessionId, buttons: [{ id: 'undo', label: 'undo' }], until: this.t + 7 };
  }
  say(text, secs = 2.5) { this.bubble = { kind: 'say', text, until: this.t + secs }; }

  // ---------- building blocks ----------
  *wait(secs) { const end = this.t + secs; while (this.t < end) { this.expireBubble(); yield; } }
  expireBubble() { if (this.bubble && this.bubble.until && this.t > this.bubble.until) this.bubble = null; }
  *play(anim, secs = animSecs(anim)) { this.setAnim(anim); yield* this.wait(secs); }

  // Walk along the taskbar, or along a perch when `floor` (a fixed y) is given.
  *walkTo(tx, { speed = 45, anim = 'walk', floor = null, abort = null } = {}) {
    if (floor == null) tx = this.clampX(tx);
    this.facing = tx > this.x ? 1 : -1;
    this.setAnim(anim);
    while (Math.abs(tx - this.x) > 2) {
      if (abort && abort()) return;
      const step = Math.min(Math.abs(tx - this.x), speed * this.speedMul() * this.dt);
      this.x += step * Math.sign(tx - this.x);
      // Follow the taskbar edge; hop smoothly if the next monitor's edge is at a different height.
      const g = floor ?? this.ground(this.x);
      this.y += (g - this.y) * Math.min(1, this.dt * 12);
      this.expireBubble();
      yield;
    }
    this.y = floor ?? this.ground(this.x);
  }
  *grounded(gen) { yield* this.dropDown(); yield* gen; }
  onGround() { return Math.abs(this.y - this.ground(this.x)) < 4; }
  // Hop back down to the taskbar from wherever the cat is perched.
  *dropDown() {
    if (this.onGround()) return;
    const x = this.clampX(this.x);
    yield* this.jumpTo(x, this.ground(x), 0.55, 25);
    yield* this.play('idle', 0.4);
  }

  *jumpTo(tx, ty, dur = 0.45, arc = 60) {
    const x0 = this.x, y0 = this.y;
    this.facing = tx >= x0 ? 1 : -1;
    this.setAnim('pounce', 4);
    let t = 0;
    while (t < dur) {
      t += this.dt;
      const k = Math.min(1, t / dur);
      this.x = x0 + (tx - x0) * k;
      this.y = y0 + (ty - y0) * k - Math.sin(Math.PI * k) * arc;
      yield;
    }
    this.x = tx; this.y = ty;
    this.setAnim('pounce', 6);
    yield* this.wait(0.15);
  }

  // Typing frames spring back to "rest" shortly after each key.
  *typing() {
    this.setAnim('typing', 0);
    while (true) {
      if (this.keyUpAt && this.t > this.keyUpAt) { this.setAnim('typing', 0); this.keyUpAt = 0; }
      yield;
    }
  }
  *afterTyping() { yield* this.play('stretch'); yield* this.play('idle', 2); }

  // ---------- life: the idle routine ----------
  *life() {
    while (true) {
      yield* this.dropDown(); // e.g. after typing or a mission up on a window
      const hour = new Date().getHours();
      const october = new Date().getMonth() === 9;
      const ds = this.displays();
      const choices = [
        [30, () => this.sit()],
        [25, () => this.wander()],
        [ds.length > 1 ? 8 : 0, () => this.visitMonitor()],
        [10, () => this.tabPatrol()],
        [8, () => this.groom()],
        [5, () => this.play('yawn')],
        [5, () => this.play('stretch', animSecs('stretch') * 2)],
        [8, () => this.loaf()],
        [hour >= 22 || hour < 6 ? 15 : 4, () => this.nap(rand(30, 90))],
        [5, () => this.boxTime()],
        [4, () => this.zoomies()],
        [8, () => this.ballPlay()],
        [4, () => this.rollAround()],
        [october ? 6 : 0.5, () => this.play('dracula', rand(8, 15))],
      ];
      const total = choices.reduce((a, [w]) => a + w, 0);
      let r = Math.random() * total;
      const [, behaviour] = choices.find(([w]) => (r -= w) < 0) || choices[0];
      yield* behaviour();
    }
  }

  *sit() {
    const end = this.t + rand(4, 10);
    while (this.t < end) {
      yield* this.play('idle', rand(2, 4));
      if (Math.random() < 0.5) yield* this.play('blink');
    }
  }
  // Mostly heads for the corners so it stays out of the way.
  *wander() {
    const w = this.displayAt(this.x).workArea;
    const edge = Math.random() < 0.65;
    const tx = edge ? (Math.random() < 0.5 ? w.x + rand(40, 180) : w.x + w.width - rand(40, 180)) : w.x + rand(60, w.width - 60);
    yield* this.walkTo(tx);
    yield* this.play('idle', rand(1, 3));
  }
  *visitMonitor() {
    const others = this.displays().filter(d => d !== this.displayAt(this.x));
    if (!others.length) return;
    const w = pick(others).workArea;
    const tx = Math.random() < 0.5 ? w.x + rand(60, 200) : w.x + w.width - rand(60, 200);
    // Walk to the shared edge, hop across, then stroll on.
    const here = this.displayAt(this.x).workArea;
    const edgeX = tx > this.x ? here.x + here.width - 30 : here.x + 30;
    yield* this.walkTo(edgeX, { speed: 70 });
    const landX = tx > this.x ? w.x + 30 : w.x + w.width - 30;
    yield* this.jumpTo(landX, w.y + w.height, 0.5, 50);
    yield* this.walkTo(tx);
  }
  // Leap onto the active window's top edge / tab strip, stroll and investigate, then drop back down.
  *tabPatrol() {
    const p = this.perch();
    if (!p || p.x1 - p.x0 < 240) return;
    const inside = x => Math.min(Math.max(x, p.x0 + 30), p.x1 - 30);
    const start = inside(this.x);
    if (Math.abs(start - this.x) > 400) yield* this.walkTo(start, { speed: 90 }); // get roughly underneath first
    yield* this.play('pounce', 0.5);
    yield* this.jumpTo(inside(this.x), p.y, 0.6, 70);
    yield* this.stroll(p);
  }
  *stroll(p) {
    const inside = x => Math.min(Math.max(x, p.x0 + 30), p.x1 - 30);
    // Window closed, moved or switched: hop off rather than float in mid-air.
    const gone = () => { const now = this.perch(); return !now || Math.abs(now.y - p.y) > 20 || this.x < now.x0 || this.x > now.x1; };
    const end = this.t + rand(12, 25);
    while (this.t < end && !gone()) {
      const r = Math.random();
      if (r < 0.45) yield* this.walkTo(inside(this.x + pick([-1, 1]) * rand(80, 260)), { floor: p.y, abort: gone });
      else if (r < 0.65) { // look around, puzzled
        this.addFx('question', { life: 1.6 });
        yield* this.play('idle', 0.8); this.facing = -this.facing; yield* this.play('idle', 0.8);
      } else if (r < 0.8) { // playful pounce onto the next tab
        const tx = inside(this.x + this.facing * rand(40, 90));
        this.setAnim('pounce', 0);
        for (const f of [0, 1, 2, 3]) { this.frame = f; yield* this.wait(0.12); }
        yield* this.jumpTo(tx, p.y, 0.35, 25);
        yield* this.play('happy', 0.6);
      } else if (r < 0.9) yield* this.play('groom', animSecs('groom'));
      else yield* this.play('blink');
    }
    yield* this.dropDown();
  }
  *groom() { yield* this.play('groom', animSecs('groom') * 2); yield* this.play('idle', 1); }
  *loaf() { yield* this.play('loaf', rand(15, 40)); }
  *nap(secs) {
    yield* this.play('loaf', 3);
    this.setAnim('sleep');
    const end = this.t + secs;
    let nextZ = this.t;
    while (this.t < end) {
      if (this.t > nextZ) { this.addFx('z', { life: 2.4 }); nextZ = this.t + 1.3; }
      yield;
    }
    yield* this.play('stretch');
  }
  *boxTime() { yield* this.play('box', rand(12, 30)); }
  *zoomies() {
    const w = this.displayAt(this.x).workArea;
    const far = this.x - w.x > w.width / 2 ? w.x + 60 : w.x + w.width - 60;
    const home = this.x;
    yield* this.walkTo(far, { speed: 260, anim: 'run' });
    yield* this.play('surprised', 0.4);
    yield* this.walkTo(home, { speed: 260, anim: 'run' });
    yield* this.play('happy', 1.2);
  }
  *rollAround() {
    const dir = pick([-1, 1]);
    const tx = this.clampX(this.x + dir * 60);
    this.facing = dir;
    this.setAnim('roll');
    while (Math.abs(this.x - tx) > 1) { this.x += Math.sign(tx - this.x) * Math.min(Math.abs(tx - this.x), 50 * this.dt); yield; }
    yield* this.play('happy', 1);
  }

  // ---------- ball play ----------
  stepBall() {
    const b = this.ball;
    if (!b) return;
    const w = this.displayAt(b.x).workArea;
    b.x += b.vx * this.dt;
    b.spin += b.vx * this.dt / 4;
    b.vx -= Math.sign(b.vx) * Math.min(Math.abs(b.vx), 380 * this.dt);
    if (b.x < w.x + 12 || b.x > w.x + w.width - 12) { b.vx = -b.vx * 0.6; b.x = Math.min(Math.max(b.x, w.x + 12), w.x + w.width - 12); }
    b.y = w.y + w.height;
  }
  *ballPlay() {
    this.facing = this.x > this.displayAt(this.x).workArea.x + 200 ? -1 : 1;
    this.ball = { x: this.clampX(this.x + this.facing * 45), y: this.y, vx: 0, spin: 0 };
    this.addFx('sparkle', { dx: this.facing * 45 });
    yield* this.play('surprised', 0.5);
    for (let i = 0, n = Math.floor(rand(3, 6)); i < n; i++) {
      this.facing = this.ball.x > this.x ? 1 : -1;
      this.setAnim('pounce', 0);
      for (const f of [0, 1, 2, 3]) { this.frame = f; yield* this.wait(0.14); }
      yield* this.jumpTo(this.clampX(this.ball.x - this.facing * 14), this.ground(this.ball.x), 0.35, 30);
      this.ball.vx = this.facing * rand(170, 280);
      yield* this.wait(0.2);
      while (Math.abs(this.ball.vx) > 5) {           // chase while it rolls
        const gap = this.ball.x - this.x;
        if (Math.abs(gap) > 26) { this.facing = Math.sign(gap); this.setAnim('run'); this.x += this.facing * 150 * this.dt; this.y = this.ground(this.x); }
        else this.setAnim('idle');
        yield;
      }
      yield* this.walkTo(this.ball.x - Math.sign(this.ball.x - this.x) * 22, { speed: 80 });
    }
    yield* this.play('happy', 1.2);
    this.addFx('sparkle', { dx: this.ball.x - this.x });
    this.ball = null;
    yield* this.play('loaf', rand(5, 10));
  }

  // ---------- reacting to you ----------
  *away() {
    yield* this.play('yawn');
    yield* this.play('loaf', 2);
    this.setAnim('sleep');
    let nextZ = this.t;
    while (this.userIdleSecs > 5) {
      if (this.t > nextZ) { this.addFx('z', { life: 2.4 }); nextZ = this.t + 1.5; }
      yield;
    }
    this.mode = 'life';
    yield* this.wake();
  }
  *wake() { yield* this.play('stretch'); yield* this.play('yawn'); yield* this.play('happy', 1); }
  *petted() {
    this.setAnim('happy');
    for (let i = 0; i < 3; i++) { this.addFx('heart', { dx: rand(-14, 14), life: 1.4 }); yield* this.wait(0.35); }
    yield* this.wait(0.8);
  }

  *closeTabMission(tabId, target) {
    this.ball = null;
    this.addFx('bang', { life: 1 });
    yield* this.play('surprised', 0.45);
    let climbed = false;
    if (target) {
      // Race along the taskbar under the tab (always under ~1.6s), then leap up onto it.
      const tx = this.clampX(target.x, 30);
      const speed = Math.max(300, Math.abs(tx - this.x) / 1.4);
      if (this.displayAt(tx) !== this.displayAt(this.x)) yield* this.jumpTo(tx, this.ground(tx), 0.6, 120);
      else yield* this.walkTo(tx, { speed, anim: 'run' });
      yield* this.jumpTo(tx, target.y, 0.45, 40);
      climbed = true;
    }
    this.facing = 1;
    this.setAnim('swipe');
    yield* this.wait(0.38);
    this.onCloseTab(tabId, 'blocked');
    this.addFx('sparkle', { dx: 26, life: 0.8 });
    yield* this.wait(0.4);
    this.say(pick(['no reels!', 'nope. back to work', 'not today!', 'caught you', 'focus mode 🐾']), 2.2);
    yield* this.play('happy', 1.2);
    if (climbed) yield* this.jumpTo(this.x, this.ground(this.x), 0.5, 20);
  }

  // Ask about an idle tab with a thought bubble; the answer arrives via answerBubble().
  *nudge(n) {
    this.lastNudgeAt = this.t;
    yield* this.play('idle', 0.5);
    this.bubbleAnswer = null;
    this.bubble = {
      kind: 'ask', tabId: n.tabId, domain: n.domain,
      text: `close "${n.title}"?`, sub: `untouched for ${fmtIdle(n.idleMinutes)}`,
      buttons: [{ id: 'yes', label: 'yes' }, { id: 'no', label: 'nope' }, { id: 'later', label: 'later' }],
    };
    const end = this.t + 25;
    this.setAnim('idle');
    while (!this.bubbleAnswer && this.t < end) yield;
    if (!this.bubbleAnswer) { this.bubble = null; this.onBubbleAnswer({ kind: 'ask', tabId: n.tabId, domain: n.domain }, 'later'); return; }
    const { answer } = this.bubbleAnswer;
    if (answer === 'yes') { yield* this.play('swipe'); yield* this.play('happy', 1); }
    else if (answer === 'no') yield* this.play('blink');
    else yield* this.play('yawn');
  }
}

function fmtIdle(min) {
  if (min < 90) return `${Math.round(min)} min`;
  if (min < 60 * 36) return `${Math.round(min / 60)} h`;
  return `${Math.round(min / 1440)} days`;
}

module.exports = { Brain };
