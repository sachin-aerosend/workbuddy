// Generates every cat animation sheet + manifest + preview GIFs from the CatPack source art.
// usage: node tools/build-sprites.js
const fs = require('fs');
const path = require('path');
const P = require('./pixel');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'assets', 'source');
const OUT = path.join(ROOT, 'app', 'renderer', 'sprites');
const PREVIEW = path.join(ROOT, 'assets', 'previews');
fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(PREVIEW, { recursive: true });

const idle = [...Array(10)].map((_, i) => P.loadGrid(path.join(SRC, 'Idle.png'), i * 32, 0, 32, 32));
const box = [...Array(4)].map((_, i) => P.loadGrid(path.join(SRC, 'Box3.png'), i * 32, 0, 32, 32, 'ofsdcbkwBDE'));
const dracula = [...Array(6)].map((_, i) => P.loadGrid(path.join(SRC, 'drculacat.png'), i * 32, 0, 32, 32, 'ofsdcbkwpyR'));

// ---------- Head + faces (crop coords: head is 17x15, taken from idle frame 0) ----------
const HEAD0 = P.plot(P.pad(P.crop(idle[0], 2, 4, 17, 14), 17, 15),
  [...Array(11)].map((_, i) => [3 + i, 14, 'o']));

const EYES = { L: [4, 8], R: [11, 8] }; // top-left of each 2x2 eye
function face(head, { eyes = 'open', mouth = 'cat' } = {}) {
  const pts = [];
  for (const [ex, ey] of Object.values(EYES)) {
    const clear = [[ex, ey, 'f'], [ex + 1, ey, 'f'], [ex, ey + 1, 'f'], [ex + 1, ey + 1, 'f']];
    if (eyes === 'closed') pts.push(...clear, [ex, ey + 1, 'k'], [ex + 1, ey + 1, 'k']);
    if (eyes === 'happy') pts.push(...clear, [ex, ey, 'k'], [ex + 1, ey, 'k'], [ex - 1, ey + 1, 'k'], [ex + 2, ey + 1, 'k']);
    if (eyes === 'wide') pts.push([ex, ey - 1, 'w'], [ex + 1, ey - 1, 'k'], [ex, ey, 'k']);
    if (eyes === 'focus') pts.push([ex, ey, 'k'], [ex + 1, ey, 'k']); // no highlight, determined
  }
  if (mouth === 'yawn') pts.push([7, 10, 'o'], [8, 10, 'b'], [9, 10, 'o'], [8, 9, 'o'], [7, 11, 'o'], [8, 11, 'o'], [9, 11, 'o'], [8, 11, 'b'], [8, 12, 'o']);
  if (mouth === 'o') pts.push([7, 10, 'c'], [9, 10, 'c'], [8, 10, 'k'], [8, 9, 'k']);
  if (mouth === 'tongue') pts.push([8, 10, 'b'], [8, 11, 'b']);
  return P.plot(head, pts);
}

// ---------- Front-facing sitting poses (built on idle frame 0) ----------
const SIT = idle[0];
// Re-face a sitting frame; when the head bobs down, clear the rows it vacates so the ears don't double up.
const withFace = (frame, opts, dy = 0, hx = 2) => {
  const cleared = dy > 0 ? P.plot(frame, [...Array((dy + 1) * 17)].map((_, i) => [hx + (i % 17), 4 + Math.floor(i / 17), '.'])) : frame;
  return P.paste(cleared, face(HEAD0, opts), hx, 4 + dy);
};
const pawGrid = P.outline(P.rect(P.blank(6, 5), 1, 1, 4, 3, 'c'));
// A raised front leg: 2px arm from the shoulder (sx,sy) to a 4x3 paw at (px,py), outlined as one piece.
function arm(sx, sy, px, py) {
  let g = P.blank(32, 32);
  const steps = Math.max(Math.abs(px + 1 - sx), Math.abs(py + 1 - sy), 1);
  for (let i = 0; i <= steps; i++) {
    const x = Math.round(sx + (px + 1 - sx) * i / steps), y = Math.round(sy + (py + 1 - sy) * i / steps);
    g = P.rect(g, x, y, 2, 2, 'c');
  }
  return P.outline(P.rect(g, px, py, 4, 3, 'c'));
}

function blinkFrames() {
  return [SIT, SIT, SIT, withFace(SIT, { eyes: 'closed' }), SIT];
}
function yawnFrames() {
  return [
    withFace(SIT, { eyes: 'closed' }),
    withFace(SIT, { eyes: 'closed', mouth: 'yawn' }, -1),
    withFace(SIT, { eyes: 'closed', mouth: 'yawn' }, -1),
    withFace(SIT, { eyes: 'closed', mouth: 'yawn' }, -1),
    withFace(SIT, { eyes: 'closed' }),
  ];
}
function happyFrames() {
  const a = withFace(SIT, { eyes: 'happy' });
  return [a, withFace(SIT, { eyes: 'happy' }, -1), a, a];
}
function surprisedFrames() {
  const a = withFace(P.shift(SIT, 0, -2), { eyes: 'wide', mouth: 'o' }, -2);
  return [a, withFace(SIT, { eyes: 'wide', mouth: 'o' })];
}
// Grooming: paw up at the mouth, tongue out, head tilted by a pixel.
function groomFrames() {
  const up = P.paste(withFace(SIT, { eyes: 'closed', mouth: 'tongue' }), arm(12, 22, 8, 16), 0, 0);
  const up2 = P.paste(withFace(SIT, { eyes: 'closed', mouth: 'tongue' }, 1), arm(12, 22, 8, 17), 0, 0);
  return [up, up2, up, up2, up, withFace(SIT, { eyes: 'closed' })];
}
// Swipe: the tab-closing move. Paw raised, then slammed down to the side.
// Built on the mirrored sit pose so the tail is out of the way of the swiping arm.
function swipeFrames() {
  const base = P.shift(P.flipX(SIT), -8, 0);
  const f = (eyes, px, py) => P.paste(withFace(base, { eyes }, 0, 5), arm(16, 21, px, py), 0, 0);
  return [f('focus', 21, 9), f('focus', 21, 9), f('focus', 24, 15), f('closed', 25, 24), f('closed', 25, 24), base];
}

// ---------- Water: a little glass, held and sipped (water reminder) ----------
// 7x9 tumbler (5x7 inside): shine down the left, `level` rows of water (0-7), darker bottom row.
function glass(level) {
  let g = P.rect(P.blank(7, 9), 1, 1, 5, 7, 'g');
  if (level > 0) g = P.rect(g, 1, 8 - level, 5, level, 'q');
  if (level > 1) g = P.rect(g, 1, 7, 5, 1, 'Q');
  g = P.plot(g, [[1, 2, 'w'], [1, 3, 'w'], [1, 4, 'w']]);
  return P.outline(g);
}
// Glass with its top-left at (gx,gy), both front paws hugging its sides (paws drawn over the rim).
// Raised glasses get arms reaching up from the shoulders (drawn behind the glass).
function withGlass(base, level, gx, gy, arms = false) {
  let g = base;
  if (arms) { g = P.paste(g, arm(6, 22, gx - 3, gy + 4), 0, 0); g = P.paste(g, arm(12, 22, gx + 6, gy + 4), 0, 0); }
  g = P.paste(g, glass(level), gx, gy);
  g = P.paste(g, pawGrid, gx - 4, gy + 3);
  return P.paste(g, pawGrid, gx + 5, gy + 3);
}
function drinkFrames() {
  const hold = (eyes, level) => withGlass(withFace(SIT, { eyes }), level, 7, 18);
  const lift = level => withGlass(withFace(SIT, { eyes: 'open' }), level, 7, 16, true);
  const sip = (level, dy) => withGlass(withFace(SIT, { eyes: 'closed' }, dy), level, 7, 13 + dy, true);
  return [hold('open', 6), lift(6), sip(5, 0), sip(4, 1), sip(3, 0), sip(2, 1), lift(2), hold('happy', 2)];
}
function holdGlassFrames() {
  const h = eyes => withGlass(withFace(SIT, { eyes }), 6, 7, 18);
  return [h('open'), h('open'), h('open'), h('open'), h('closed'), h('open')];
}

// Picked up by the scruff: body hangs, legs dangle and kick, tail swings.
function dangleFrames() {
  const upper = SIT.map((row, y) => (y >= 21 ? '.'.repeat(32) : y >= 18 ? row.slice(0, 17) + '.'.repeat(15) : row));
  const make = (kick, tailSide) => {
    let g = P.ellipse(P.blank(32, 32), 10, 23, 5.5, 3.5, 'c');
    g = P.rect(g, 4 - kick, 25, 2, 4 + kick, 'd');     // back legs
    g = P.rect(g, 15 + kick, 25, 2, 5 - kick, 'd');
    g = P.rect(g, 6, 25, 3, 5 - kick, 'c');             // front legs
    g = P.rect(g, 11, 25, 3, 4 + kick, 'c');
    const tail = tailSide > 0 ? [[14, 24], [15, 25], [16, 26], [17, 27], [18, 28], [18, 29]] : [[12, 27], [12, 28], [12, 29], [13, 30]];
    g = P.plot(g, tail.flatMap(([x, y]) => [[x, y, 'd'], [x + 1, y, 'c']]));
    g = P.outline(g);
    g = P.paste(g, upper, 0, 0);
    return withFace(g, { eyes: 'wide', mouth: 'o' });
  };
  return [make(0, 1), make(1, 1), make(0, -1), make(1, -1)];
}

// ---------- Typing: tiny keyboard in front, paws tap by side ----------
function keyboard(lit) {
  const w = 24, h = 6;
  let g = P.rect(P.blank(w, h), 1, 1, w - 2, h - 2, 'K');
  for (let x = 2; x < w - 2; x += 3) { g = P.rect(g, x, 1, 2, 1, 'j'); g = P.rect(g, x, 2, 2, 1, 'j'); }
  g = P.rect(g, 6, 3, 12, 1, 'j'); // space bar
  g = P.rect(g, 1, 4, w - 2, 1, 'L');
  for (const x of lit) g = P.rect(g, x, 1, 2, 2, 'J');
  return P.outline(g);
}
function typingFrames() {
  const base = withFace(SIT, { eyes: 'focus' });
  const make = (leftDown, rightDown, lit) => {
    let g = P.paste(base, keyboard(lit), 0, 25);
    g = P.paste(g, pawGrid, 4, 21 + (leftDown ? 2 : 0));
    g = P.paste(g, pawGrid, 11, 21 + (rightDown ? 2 : 0));
    return g;
  };
  // rest, left key, right key, both (Enter slam)
  return [make(false, false, []), make(true, false, [5]), make(false, true, [14]), make(true, true, [5, 14, 20])];
}

// ---------- Side view (faces right; the app mirrors for left) ----------
const TAIL_SHAPES = {
  up: [[4, 21], [3, 20], [2, 19], [2, 18], [2, 17], [2, 16], [3, 15], [4, 14]],
  mid: [[4, 21], [3, 20], [2, 19], [1, 18], [1, 17], [1, 16], [2, 15]],
  high: [[4, 20], [4, 19], [3, 18], [3, 17], [3, 16], [3, 15], [3, 14], [4, 13], [5, 12]],
  flat: [[4, 22], [3, 22], [2, 21], [1, 21], [0, 20]],
};
function side({ legs, bodyDy = 0, headDy = 0, headDx = 0, tail = 'up', eyes = 'open', mouth = 'cat', lean = 0 }) {
  let g = P.blank(32, 32);
  // tail (2px thick)
  const t = TAIL_SHAPES[tail].map(([x, y]) => [x, y + bodyDy]);
  g = P.plot(g, t.flatMap(([x, y], i) => [[x, y, 'd'], [x + 1, y, i === t.length - 1 ? 's' : 'c']]));
  // far legs
  for (const l of legs.filter(l => l.far)) g = P.rect(g, l.x, l.y ?? 26, 2, (l.h ?? 5), 'd');
  // body: ellipse, fur on top, cream belly, stripes on the back
  g = P.ellipse(g, 13, 23 + bodyDy, 8.5, 3.6, 'f');
  if (lean) g = P.ellipse(g, 8, 22 + bodyDy - lean, 4.5, 3.4, 'f'); // raised rear (crouch/stretch)
  const belly = [];
  for (let x = 6; x <= 20; x++) belly.push([x, 26 + bodyDy, 'c']);
  for (let x = 8; x <= 18; x++) belly.push([x, 25 + bodyDy, 'c']);
  g = P.plot(g, belly);
  g = P.plot(g, [[9, 20, 's'], [9, 21, 's'], [12, 20, 's'], [12, 21, 's'], [15, 20, 's']].map(([x, y, c]) => [x, y + bodyDy - (x < 11 ? lean : 0), c]));
  // near legs
  for (const l of legs.filter(l => !l.far)) g = P.rect(g, l.x, l.y ?? 26, 3, (l.h ?? 5), 'c');
  g = P.outline(g);
  // head in front
  g = P.paste(g, face(HEAD0, { eyes, mouth }), 14 + headDx, 9 + bodyDy + headDy);
  return g;
}
const L = (x, y, h, far) => ({ x, y, h, far });
function walkFrames() {
  return [
    side({ legs: [L(8, 26, 5, 1), L(19, 26, 5, 1), L(5, 26, 5), L(15, 26, 5)] }),
    side({ legs: [L(7, 25, 5, 1), L(20, 26, 5, 1), L(6, 26, 5), L(14, 25, 5)], bodyDy: -1, tail: 'mid' }),
    side({ legs: [L(6, 26, 5, 1), L(17, 26, 5, 1), L(8, 26, 5), L(17, 26, 5)] }),
    side({ legs: [L(7, 26, 5, 1), L(16, 25, 5, 1), L(7, 25, 5), L(18, 26, 5)], bodyDy: -1, tail: 'mid' }),
  ];
}
function runFrames() {
  return [
    side({ legs: [L(3, 25, 4, 1), L(21, 25, 4, 1), L(4, 26, 4), L(20, 26, 4)], bodyDy: 0, tail: 'flat' }),
    side({ legs: [L(7, 24, 4, 1), L(15, 24, 4, 1), L(9, 25, 4), L(14, 25, 4)], bodyDy: -2, tail: 'flat', headDy: 1 }),
    side({ legs: [L(10, 26, 5, 1), L(13, 26, 5, 1), L(11, 26, 5), L(15, 26, 5)], bodyDy: 0, tail: 'mid' }),
    side({ legs: [L(5, 24, 4, 1), L(18, 24, 4, 1), L(6, 25, 4), L(19, 25, 4)], bodyDy: -2, tail: 'flat', headDy: 1 }),
  ];
}
// Crouch → wiggle → pounce → land, used for ball play and jumping.
function pounceFrames() {
  const crouch = (tail) => side({ legs: [L(7, 25, 6, 1), L(20, 28, 3, 1), L(5, 25, 6), L(18, 28, 3)], bodyDy: 2, lean: 2, headDy: 1, tail, eyes: 'focus' });
  const air = side({ legs: [L(4, 20, 4, 1), L(19, 20, 4, 1), L(5, 21, 4), L(17, 21, 4)], bodyDy: -5, tail: 'flat', eyes: 'focus' });
  const land = side({ legs: [L(8, 27, 4, 1), L(18, 27, 4, 1), L(6, 27, 4), L(16, 27, 4)], bodyDy: 1, headDy: 0, tail: 'mid' });
  return [crouch('high'), crouch('up'), crouch('high'), crouch('up'), air, air, land];
}
function stretchFrames() {
  const s = (eyes, mouth) => side({ legs: [L(6, 25, 6, 1), L(22, 29, 2, 1), L(4, 25, 6), L(20, 29, 2)], bodyDy: 2, lean: 3, headDy: 2, tail: 'high', eyes, mouth });
  return [s('closed'), s('closed', 'yawn'), s('closed', 'yawn'), s('closed')];
}

// ---------- Lying down (loaf) + sleeping ----------
function loaf(breathe, eyes = 'closed') {
  let g = P.blank(32, 32);
  g = P.ellipse(g, 15.5, 25.5 + (breathe ? -0.4 : 0), 12, 4.6 + (breathe ? 0.4 : 0), 'f');
  g = P.plot(g, [[5, 23, 's'], [5, 24, 's'], [8, 22, 's'], [26, 23, 's'], [26, 24, 's'], [23, 22, 's']]);
  // tail wrapped around the front
  g = P.rect(g, 18, 29, 10, 2, 'd');
  g = P.rect(g, 18, 29, 9, 1, 'c');
  g = P.outline(g);
  return P.paste(g, face(HEAD0, { eyes }), 7, 14 + (breathe ? 0 : 1));
}
function sleepFrames() { return [loaf(false), loaf(false), loaf(true), loaf(true)]; }
function loafFrames() { return [loaf(false, 'open'), loaf(false, 'open'), loaf(false, 'open'), loaf(false, 'closed')]; }

// Rolling: the loaf spun a quarter turn per frame.
function rotate90(g) { const n = g.length; return g.map((_, y) => g.map((__, x) => g[n - 1 - x][y]).join('')); }
function rollFrames() {
  const ball = loaf(false, 'happy');
  const r1 = rotate90(ball), r2 = rotate90(r1), r3 = rotate90(r2);
  // keep the curled body resting on the floor after each turn
  const settle = g => {
    const last = g.map(r => /[^.]/.test(r)).lastIndexOf(true);
    const cols = [...g[0]].map((_, x) => g.some(r => r[x] !== '.'));
    const cx = Math.round((cols.indexOf(true) + cols.lastIndexOf(true)) / 2);
    return P.shift(g, 15 - cx, 31 - last);
  };
  return [settle(ball), settle(r1), settle(r2), settle(r3)];
}

// ---------- Alarm clock prop (15x15): 2 ticking frames, 2 ringing (shaking) frames ----------
function clockFrames() {
  const body = (minute) => {
    let g = P.blank(15, 15);
    g = P.ellipse(g, 4, 3, 1.6, 1.2, 'R');            // bells
    g = P.ellipse(g, 10, 3, 1.6, 1.2, 'R');
    g = P.plot(g, [[4, 13, 'R'], [10, 13, 'R']]);   // feet
    g = P.ellipse(g, 7, 8, 4.6, 4.4, 'r');           // body
    g = P.ellipse(g, 7, 8, 3.1, 2.9, 'c');           // face
    g = P.plot(g, [[7, 6, 'k'], [7, 7, 'k'], [7, 8, 'k']]);         // hour hand
    g = P.plot(g, minute ? [[8, 8, 'k'], [9, 8, 'k']] : [[8, 9, 'k'], [9, 10, 'k']]); // minute hand
    g = P.plot(g, [[5, 5, 'w']]);                    // shine
    return P.outline(g);
  };
  const ring = dx => P.plot(P.shift(body(1), dx, 0), [[0, 5, 'e'], [0, 8, 'e'], [14, 5, 'e'], [14, 8, 'e'], [1, 2, 'e'], [13, 2, 'e']]);
  return [body(0), body(1), ring(-1), ring(1)];
}

// ---------- Props + effects ----------
const BALL = P.outline(P.plot(P.ellipse(P.blank(9, 9), 4, 4, 3, 3, 'r'),
  [[3, 2, 'R'], [4, 3, 'R'], [5, 4, 'R'], [2, 4, 'R'], [3, 5, 'R'], [5, 2, 'c'], [6, 3, 'c']]));
const grid = s => s.trim().split('\n').map(r => r.trim());
const FX = {
  heart: grid(`
    .oo.oo.
    ohhohho
    ohhhhho
    .ohhho.
    ..oho..
    ...o...`),
  z: grid(`
    ooooo
    ozzzo
    .ozo.
    ozzzo
    ooooo`),
  bang: grid(`
    .ooo.
    .oeo.
    .oeo.
    .oeo.
    .ooo.
    .oeo.
    .ooo.`),
  question: P.outline(P.pad(grid(`
    zzz
    ..z
    .zz
    ...
    .z.`), 5, 7, 1, 1)),
  sparkle: grid(`
    ...o...
    ..owo..
    .owwwo.
    owwwwwo
    .owwwo.
    ..owo..
    ...o...`),
  drop: grid(`
    ...o...
    ..oqo..
    .oqqqo.
    oqqqqqo
    oqqqwqo
    oQqqqQo
    .ooooo.`),
};
const padFx = g => P.pad(g, 9, 9, Math.floor((9 - P.W(g)) / 2), Math.floor((9 - P.H(g)) / 2));

// ---------- Assemble ----------
const ANIMS = {
  idle: { frames: idle, fps: 8, loop: true },
  blink: { frames: blinkFrames(), fps: 8 },
  yawn: { frames: yawnFrames(), fps: 5 },
  happy: { frames: happyFrames(), fps: 6 },
  surprised: { frames: surprisedFrames(), fps: 6 },
  groom: { frames: groomFrames(), fps: 5 },
  swipe: { frames: swipeFrames(), fps: 8 },
  drink: { frames: drinkFrames(), fps: 4 },
  holdGlass: { frames: holdGlassFrames(), fps: 3, loop: true },
  typing: { frames: typingFrames(), fps: 0 }, // driven by keystrokes
  dangle: { frames: dangleFrames(), fps: 6, loop: true },
  walk: { frames: walkFrames(), fps: 7, loop: true },
  run: { frames: runFrames(), fps: 12, loop: true },
  pounce: { frames: pounceFrames(), fps: 8 },
  stretch: { frames: stretchFrames(), fps: 3 },
  loaf: { frames: loafFrames(), fps: 2, loop: true },
  sleep: { frames: sleepFrames(), fps: 2, loop: true },
  roll: { frames: rollFrames(), fps: 8, loop: true },
  box: { frames: box, fps: 4, loop: true },
  dracula: { frames: dracula, fps: 6, loop: true },
};

const manifest = { frameW: 32, frameH: 32, anims: {}, fx: {}, ball: { w: 9, h: 9 } };
for (const [name, a] of Object.entries(ANIMS)) {
  P.writeSheet(path.join(OUT, `${name}.png`), a.frames);
  manifest.anims[name] = { frames: a.frames.length, fps: a.fps, loop: !!a.loop };
  if (a.fps) P.writeGif(path.join(PREVIEW, `${name}.gif`), a.loop ? a.frames.concat(a.frames) : a.frames.concat([a.frames[a.frames.length - 1]]), a.fps);
}
P.writeGif(path.join(PREVIEW, 'typing.gif'), [0, 1, 0, 2, 1, 2, 0, 3, 0, 0].map(i => ANIMS.typing.frames[i]), 6);
P.writeSheet(path.join(OUT, 'ball.png'), [BALL]);
const CLOCK = clockFrames();
P.writeSheet(path.join(OUT, 'clock.png'), CLOCK);
manifest.clock = { w: 15, h: 15, frames: CLOCK.length };
P.writeSheet(path.join(PREVIEW, 'clock-zoom.png'), CLOCK, 10, [236, 238, 244]);
P.writeSheet(path.join(OUT, 'fx.png'), Object.values(FX).map(padFx));
Object.keys(FX).forEach((k, i) => { manifest.fx[k] = i; });
fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 2));
// Sheets are also embedded as data URIs: canvases drawn from them stay readable (file:// images
// would "taint" the canvas and block the pixel hit-testing).
const dataUri = f => `data:image/png;base64,${fs.readFileSync(path.join(OUT, f)).toString('base64')}`;
const embedded = Object.fromEntries([...Object.keys(ANIMS), 'ball', 'fx', 'clock'].map(n => [n, dataUri(`${n}.png`)]));
fs.writeFileSync(path.join(OUT, 'manifest.js'), `window.SPRITES = ${JSON.stringify(manifest)};\nwindow.SPRITE_DATA = ${JSON.stringify(embedded)};\n`);

// Contact sheet for review: one row per animation, 4x scale.
const rows = Object.entries(ANIMS);
const maxF = Math.max(...rows.map(([, a]) => a.frames.length));
let sheet = P.blank(32 * maxF, 32 * rows.length);
rows.forEach(([, a], r) => a.frames.forEach((f, i) => { sheet = P.paste(sheet, f, i * 32, r * 32); }));
P.writeSheet(path.join(PREVIEW, 'contact-sheet.png'), [sheet], 4, [236, 238, 244]);
// Zoomed review sheet for selected animations: node tools/build-sprites.js walk,typing
const pick = (process.argv[2] || '').split(',').filter(n => ANIMS[n]);
if (pick.length) {
  let z = P.blank(32 * maxF, 32 * pick.length);
  pick.forEach((n, r) => ANIMS[n].frames.forEach((f, i) => { z = P.paste(z, f, i * 32, r * 32); }));
  P.writeSheet(path.join(PREVIEW, 'zoom.png'), [z], 8, [236, 238, 244]);
}

// App icon source (cat face).
P.writeSheet(path.join(ROOT, 'assets', 'icon-face.png'), [P.pad(HEAD0, 19, 19, 1, 2)], 1);
console.log('built', rows.map(([n, a]) => `${n}:${a.frames.length}`).join(' '));
module.exports = { HEAD0 };
