// Draws the cat, its ball and little effects; shows bubbles; tells main when the mouse is over
// something clickable so the rest of the window stays click-through.
const M = window.SPRITES;
const canvas = document.getElementById('stage');
const ctx = canvas.getContext('2d', { willReadFrequently: true }); // hover checks read pixels
const bubbleEl = document.getElementById('bubble');

const img = name => Object.assign(new Image(), { src: window.SPRITE_DATA?.[name] || `sprites/${name}.png` });
const sheets = Object.fromEntries(Object.keys(M.anims).map(n => [n, img(n)]));

// Opaque bounding box of every frame (in art pixels), so the clickable area hugs the cat.
const frameBoxes = {};
for (const [name, im] of Object.entries(sheets)) {
  im.addEventListener('load', () => {
    const c = Object.assign(document.createElement('canvas'), { width: im.width, height: im.height });
    const g = c.getContext('2d');
    g.drawImage(im, 0, 0);
    const d = g.getImageData(0, 0, im.width, im.height).data;
    frameBoxes[name] = [...Array(M.anims[name].frames)].map((_, f) => {
      let x0 = 99, y0 = 99, x1 = -1, y1 = -1;
      for (let y = 0; y < M.frameH; y++) for (let x = 0; x < M.frameW; x++) {
        if (d[(y * im.width + f * M.frameW + x) * 4 + 3] > 0) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
      }
      return x1 < 0 ? null : { x0, y0, x1: x1 + 1, y1: y1 + 1 };
    });
  });
}
const ballImg = img('ball');
const fxImg = img('fx');
const NO_FLIP = new Set(['typing', 'box', 'dracula']);

let state = null, animStart = performance.now(), lastSeq = -1;
let catRect = { x: 0, y: 0, w: 0, h: 0 }, px = 3;

function resize() {
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(innerWidth * dpr);
  canvas.height = Math.round(innerHeight * dpr);
}
addEventListener('resize', resize);
resize();

window.buddy.onState(s => {
  if (s.seq !== lastSeq) { lastSeq = s.seq; animStart = performance.now(); }
  state = s;
  renderBubble(s);
});

function frameIndex(s, now) {
  const a = M.anims[s.anim];
  if (s.frame != null) return Math.min(s.frame, a.frames - 1);
  if (!a.fps) return 0;
  const i = Math.floor((now - animStart) / 1000 * a.fps);
  return a.loop ? i % a.frames : Math.min(i, a.frames - 1);
}

function draw(now) {
  requestAnimationFrame(draw);
  if (!state) return;
  const dpr = window.devicePixelRatio || 1;
  px = Math.max(1, Math.round((state.scale || 3) * dpr)); // device pixels per art pixel, kept whole for crispness
  const W = canvas.width, H = canvas.height, fw = M.frameW * px, fh = M.frameH * px;
  ctx.clearRect(0, 0, W, H);
  ctx.imageSmoothingEnabled = false;
  const cx = Math.round(W / 2), bottom = Math.round((state.catBottom || H / dpr) * dpr);

  // ball (quarter-turn spins keep the pixels square)
  if (state.ball) {
    const s = M.ball.w * px, bx = cx + state.ball.dx * dpr, by = bottom + state.ball.dy * dpr - s / 2;
    ctx.save();
    ctx.translate(Math.round(bx), Math.round(by));
    ctx.rotate(Math.round(state.ball.spin / (Math.PI / 2)) * (Math.PI / 2));
    ctx.drawImage(ballImg, -s / 2, -s / 2, s, s);
    ctx.restore();
  }

  // cat
  const sheet = sheets[state.anim];
  const i = frameIndex(state, now);
  const x = cx - fw / 2, y = bottom - fh;
  ctx.save();
  if (state.tilt) { // swing from the scruff while being carried
    const pivotY = y + 3 * px;
    ctx.translate(cx, pivotY); ctx.rotate(state.tilt); ctx.translate(-cx, -pivotY);
  }
  if (state.facing < 0 && !NO_FLIP.has(state.anim)) { ctx.translate(cx * 2, 0); ctx.scale(-1, 1); }
  ctx.drawImage(sheet, i * M.frameW, 0, M.frameW, M.frameH, x, y, fw, fh);
  ctx.restore();
  catRect = { x: x / dpr, y: y / dpr, w: fw / dpr, h: fh / dpr };
  reportHitboxes(i, px / dpr);

  // effects
  const headY = y + 6 * px;
  for (const f of state.fx) {
    const age = f.age;
    let fx = cx + f.dx * dpr, fy = headY, alpha = 1, idx = M.fx[f.type];
    if (f.type === 'heart') { fy -= age * 28 * dpr; fx += Math.sin(age * 6) * 3 * dpr; alpha = 1 - age / 1.4; }
    if (f.type === 'z') { fx += (10 + age * 8) * px / 3; fy -= age * 22 * dpr; alpha = 1 - age / 2.4; }
    if (f.type === 'bang') { fy -= (8 + Math.abs(Math.sin(age * 10)) * 4) * dpr; alpha = age < 0.8 ? 1 : (1 - age) * 5; }
    if (f.type === 'sparkle') { fy = bottom - 10 * px; alpha = Math.round(age * 8) % 2 ? 0.5 : 1; }
    const s = 9 * Math.max(1, Math.round(px * (f.type === 'z' ? 0.6 : 0.75)));
    ctx.globalAlpha = Math.max(0, Math.min(1, alpha));
    ctx.drawImage(fxImg, idx * 9, 0, 9, 9, Math.round(fx - s / 2), Math.round(fy - s / 2), s, s);
    ctx.globalAlpha = 1;
  }
}
requestAnimationFrame(draw);

// ---------- bubble ----------
let bubbleKey = '';
function renderBubble(s) {
  const b = s.bubble;
  const key = b ? JSON.stringify([b, s.below, s.offL, s.offR, s.catBottom]) : '';
  if (key === bubbleKey) return;
  bubbleKey = key;
  if (!b) { bubbleEl.hidden = true; return; }
  bubbleEl.className = (b.kind === 'ask' ? 'thought' : 'speech') + (s.below ? ' below' : '');
  bubbleEl.querySelector('.text').textContent = b.text;
  bubbleEl.querySelector('.sub').textContent = b.sub || '';
  const btns = bubbleEl.querySelector('.buttons');
  btns.replaceChildren(...(b.buttons || []).map((btn, n) => {
    const el = document.createElement('button');
    el.textContent = btn.label;
    if (n === 0) el.className = 'primary';
    el.addEventListener('pointerdown', () => window.buddy.log(`pointerdown ${btn.id}`));
    el.addEventListener('click', () => { window.buddy.log(`click ${btn.id}`); window.buddy.answer(btn.id); });
    return el;
  }));
  // Above the cat's head normally; under its feet when the cat is up near the top of the screen.
  const catH = M.frameH * (s.scale || 2.5), gap = b.kind === 'ask' ? 24 : 10;
  if (s.below) { bubbleEl.style.top = `${s.catBottom + gap}px`; bubbleEl.style.bottom = ''; }
  else { bubbleEl.style.bottom = `${innerHeight - s.catBottom + catH - 2 + gap}px`; bubbleEl.style.top = ''; }
  bubbleEl.hidden = false;
  // Slide sideways so no part of it hangs off the screen edge; keep the dots/tail pointing at the cat.
  const w = bubbleEl.offsetWidth, mid = innerWidth / 2;
  const left = Math.min(Math.max(mid - w / 2, (s.offL || 0) + 4), innerWidth - (s.offR || 0) - w - 4);
  bubbleEl.style.left = `${Math.round(left)}px`;
  bubbleEl.style.setProperty('--point', `${Math.round(mid - left)}px`);
}

// ---------- click-through except on the cat and bubble ----------
// The main process polls the real cursor and makes the window clickable only inside these boxes
// (window-local DIP). Sent whenever they change.
let lastHit = '';
function reportHitboxes(frame, artPx) {
  let cat = null;
  const box = frameBoxes[state.anim]?.[frame];
  if (box) {
    const flip = state.facing < 0 && !NO_FLIP.has(state.anim);
    const bx0 = flip ? M.frameW - box.x1 : box.x0, bx1 = flip ? M.frameW - box.x0 : box.x1;
    cat = { x: Math.floor(catRect.x + bx0 * artPx) - 2, y: Math.floor(catRect.y + box.y0 * artPx) - 2, w: Math.ceil((bx1 - bx0) * artPx) + 4, h: Math.ceil((box.y1 - box.y0) * artPx) + 4 };
  }
  let bubble = null;
  if (!bubbleEl.hidden) {
    const r = bubbleEl.getBoundingClientRect();
    bubble = { x: Math.floor(r.left) - 6, y: Math.floor(r.top) - 6, w: Math.ceil(r.width) + 12, h: Math.ceil(r.height) + 12 };
  }
  const key = JSON.stringify([cat, bubble]);
  if (key !== lastHit) { lastHit = key; window.buddy.setHitboxes({ cat, bubble }); }
}
function overCat(x, y) {
  if (x < catRect.x || y < catRect.y || x >= catRect.x + catRect.w || y >= catRect.y + catRect.h) return false;
  const dpr = window.devicePixelRatio || 1;
  return ctx.getImageData(Math.floor(x * dpr), Math.floor(y * dpr), 1, 1).data[3] > 0;
}
addEventListener('mousemove', e => { if (!dragging) document.body.classList.toggle('pointer', overCat(e.clientX, e.clientY)); });
// Click = pet. Press and move = pick the cat up; let go = it falls and lands.
let downAt = null, dragging = false;
canvas.addEventListener('pointerdown', e => {
  if (e.button !== 0 || !overCat(e.clientX, e.clientY)) return;
  downAt = { x: e.screenX, y: e.screenY };
  canvas.setPointerCapture(e.pointerId);
});
canvas.addEventListener('pointermove', e => {
  if (!downAt || dragging) return;
  if (Math.hypot(e.screenX - downAt.x, e.screenY - downAt.y) > 5) {
    dragging = true;
    document.body.classList.add('grabbing');
    window.buddy.dragStart();
  }
});
function endPointer(e) {
  if (!downAt) return;
  if (dragging) window.buddy.dragEnd();
  else if (e.type === 'pointerup') window.buddy.pet();
  downAt = null; dragging = false;
  document.body.classList.remove('grabbing');
}
canvas.addEventListener('pointerup', endPointer);
canvas.addEventListener('pointercancel', endPointer);
