// WorkBuddy site: sprite playback, the live hero scene, and the little interactive cards.
(() => {
  // Download links always point at the newest GitHub release, so a new release needs no site change.
  const RELEASES = 'https://github.com/sachin-aerosend/workbuddy/releases/latest/download';
  const DOWNLOAD_URL = `${RELEASES}/WorkBuddy-Setup.exe`;
  const EXTENSION_URL = `${RELEASES}/WorkBuddy-Extension.zip`;

  // name: [frames, fps] (fps 0 = driven by code)
  const ANIMS = {
    idle: [10, 8], blink: [5, 8], yawn: [5, 5], happy: [4, 6], surprised: [2, 6], groom: [6, 5],
    swipe: [6, 8], typing: [4, 0], dangle: [4, 6], walk: [4, 7], run: [4, 12], pounce: [7, 8],
    stretch: [4, 3], loaf: [4, 2], sleep: [4, 2], roll: [4, 8], box: [4, 4], dracula: [6, 6],
  };
  const FX = { heart: 0, z: 1, bang: 2, question: 3, sparkle: 4 };
  const NO_FLIP = new Set(['typing', 'box', 'dracula', 'idle', 'blink', 'yawn', 'happy', 'surprised', 'groom', 'loaf', 'sleep', 'dangle']);
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const rand = (a, b) => a + Math.random() * (b - a);
  const pick = a => a[Math.floor(Math.random() * a.length)];
  const pxScale = () => parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--px')) || 3;

  // ---------- sprites ----------
  function setSprite(el, name, frame = null) {
    const [frames, fps] = ANIMS[name];
    if (el.dataset.cur !== name) {
      el.dataset.cur = name;
      el.style.backgroundImage = `url(assets/sprites/${name}.png)`;
      el.style.setProperty('--frames', frames);
      el.style.setProperty('--dur', `${fps ? frames / fps : 1}s`);
      el.style.animation = 'none'; void el.offsetWidth; el.style.animation = ''; // restart from frame 0
    }
    const still = frame != null || !fps;
    el.classList.toggle('still', still);
    el.style.backgroundPositionX = still ? `calc(var(--size) * ${-(frame || 0)})` : '';
  }
  document.querySelectorAll('.sprite[data-anim]').forEach(el => setSprite(el, el.dataset.anim, el.dataset.frame != null ? +el.dataset.frame : null));

  // ---------- download links ----------
  document.querySelectorAll('[data-download]').forEach(a => { a.href = DOWNLOAD_URL; });
  document.querySelectorAll('[data-extension]').forEach(a => { a.href = EXTENSION_URL; });

  // ---------- gallery ----------
  const moods = document.getElementById('moods');
  const LABELS = {
    idle: 'chilling', blink: 'blink', yawn: 'yawn', happy: 'happy', surprised: 'startled', groom: 'grooming',
    swipe: 'swat!', typing: 'typing', dangle: 'picked up', walk: 'stroll', run: 'zoomies', pounce: 'pounce',
    stretch: 'stretch', loaf: 'loaf', sleep: 'nap', roll: 'roll', box: 'box time', dracula: 'spooky',
  };
  let typingTick = 0;
  for (const name of Object.keys(ANIMS)) {
    const fig = document.createElement('div');
    fig.className = 'mood';
    const s = document.createElement('div');
    s.className = 'sprite';
    fig.append(s, Object.assign(document.createElement('span'), { textContent: LABELS[name] }));
    moods.append(fig);
    if (name === 'typing') { // cycle the keyboard frames
      setSprite(s, 'typing', 0);
      setInterval(() => setSprite(s, 'typing', [1, 0, 2, 0, 1, 2, 3, 0][typingTick++ % 8]), 180);
    } else setSprite(s, name);
  }

  // ---------- typing card: taps along with your real keystrokes ----------
  const typer = document.getElementById('typer');
  const typeCard = typer.closest('.card');
  const LEFT = /^(Key[QWERTASDFGZXCVB]|Digit[1-5]|Tab|CapsLock|ShiftLeft|ControlLeft|AltLeft|Backquote|Escape)$/;
  let typeReset;
  addEventListener('keydown', e => {
    if (e.target.closest && e.target.closest('input, textarea')) return;
    const f = e.code === 'Enter' ? 3 : e.code === 'Space' ? pick([1, 2]) : LEFT.test(e.code) ? 1 : 2;
    setSprite(typer, 'typing', f);
    typeCard.classList.add('typing');
    clearTimeout(typeReset);
    typeReset = setTimeout(() => { setSprite(typer, 'typing', 0); typeCard.classList.remove('typing'); }, 150);
  });

  // ---------- nudge card ----------
  const nudge = document.getElementById('nudge');
  const nudgeCat = nudge.parentElement.querySelector('.sprite');
  nudge.addEventListener('click', e => {
    const b = e.target.closest('button');
    if (!b) return;
    nudge.classList.add('gone');
    const seq = { yes: ['swipe', 'happy'], no: ['blink', 'idle'], later: ['yawn', 'idle'] }[b.dataset.nudge];
    setSprite(nudgeCat, seq[0]);
    setTimeout(() => setSprite(nudgeCat, seq[1]), 800);
    setTimeout(() => { nudge.classList.remove('gone'); setSprite(nudgeCat, 'idle'); }, 2600);
  });

  // ============================================================
  // Hero scene: a tiny version of the desktop app's cat brain.
  // Behaviours are generators that yield once per frame, so they can be interrupted.
  // ============================================================
  const scene = document.getElementById('scene');
  const actor = document.getElementById('cat');
  const sprite = actor.querySelector('.sprite');
  const sayEl = document.getElementById('say');
  const fxLayer = document.getElementById('fx');
  const tabstrip = document.getElementById('tabstrip');
  const tabWork = document.getElementById('tab-work');
  const urlEl = document.getElementById('url');
  const reelsEl = document.getElementById('reels');
  const taskbar = scene.querySelector('.taskbar');
  const browser = scene.querySelector('.browser');

  const cat = { x: 0, y: 0, facing: 1, anim: 'idle', frame: null, t: 0, dt: 0 };
  const S = () => 32 * pxScale();
  const rel = el => { const a = el.getBoundingClientRect(), b = scene.getBoundingClientRect(); return { x: a.left - b.left, y: a.top - b.top, w: a.width, h: a.height }; };
  const ground = () => taskbar.offsetTop;
  const perchY = () => browser.offsetTop + tabstrip.offsetHeight + 36; // stands on the toolbar, over the tabs
  const W = () => scene.clientWidth;
  const clampX = x => Math.min(Math.max(x, S() * 0.55), W() - S() * 0.55);

  function anim(name, frame = null) { cat.anim = name; cat.frame = frame; }
  function* wait(s) { const end = cat.t + s; while (cat.t < end) yield; }
  function* play(name, s) { anim(name); yield* wait(s ?? ANIMS[name][0] / ANIMS[name][1]); }
  function* walkTo(tx, speed = 60, name = 'walk', floor = null) {
    tx = clampX(tx);
    cat.facing = tx > cat.x ? 1 : -1;
    anim(name);
    while (Math.abs(tx - cat.x) > 1.5) {
      cat.x += Math.sign(tx - cat.x) * Math.min(Math.abs(tx - cat.x), speed * cat.dt);
      cat.y = floor ?? ground();
      yield;
    }
  }
  function* jumpTo(tx, ty, dur = 0.45, arc = 50) {
    const x0 = cat.x, y0 = cat.y;
    cat.facing = tx >= x0 ? 1 : -1;
    anim('pounce', 4);
    for (let k = 0; k < 1;) {
      k = Math.min(1, k + cat.dt / dur);
      cat.x = x0 + (tx - x0) * k;
      cat.y = y0 + (ty - y0) * k - Math.sin(Math.PI * k) * arc;
      yield;
    }
    anim('pounce', 6);
    yield* wait(0.15);
  }

  function fx(type, dx = 0, dy = 0) {
    const s = S();
    const el = document.createElement('div');
    el.className = 'fx';
    el.style.backgroundPositionX = `${-FX[type] * 9 * pxScale()}px`;
    el.style.left = `${cat.x + dx - 4.5 * pxScale()}px`;
    el.style.top = `${cat.y - s + dy}px`;
    fxLayer.append(el);
    setTimeout(() => el.remove(), 1400);
  }
  let sayTimer;
  function say(text, ms = 1800) {
    sayEl.textContent = text;
    sayEl.hidden = false;
    sayEl.style.animation = 'none'; void sayEl.offsetWidth; sayEl.style.animation = '';
    placeSay();
    clearTimeout(sayTimer);
    sayTimer = setTimeout(() => { sayEl.hidden = true; }, ms);
  }
  function placeSay() {
    if (sayEl.hidden) return;
    const top = cat.y - S() - 8;
    const below = top < 34;
    sayEl.classList.toggle('below', below);
    sayEl.style.left = `${Math.min(Math.max(cat.x, 70), W() - 70)}px`;
    sayEl.style.top = `${below ? cat.y + 10 : top}px`;
  }

  // --- the Reels demo: a tab opens, the cat hunts it down ---
  function openReels() {
    const tab = document.createElement('div');
    tab.className = 'tab active enter';
    tab.innerHTML = '<i class="fav fav-reels"></i><span>Reels • Instagram</span>';
    tabWork.classList.remove('active');
    tabstrip.insertBefore(tab, tabstrip.querySelector('.tab-new'));
    urlEl.textContent = 'instagram.com/reels';
    reelsEl.hidden = false;
    return tab;
  }
  function closeReels(tab) {
    tab.classList.add('poof');
    setTimeout(() => tab.remove(), 320);
    tabWork.classList.add('active');
    urlEl.textContent = 'app.crm.com/pipeline';
    reelsEl.hidden = true;
  }
  function* reelsDemo() {
    yield* play('idle', 0.6);
    const tab = openReels();
    yield* wait(0.5);
    fx('bang', 0, -6);
    yield* play('surprised', 0.5);
    const r = rel(tab);
    const tx = clampX(r.x + r.w / 2);
    yield* walkTo(tx, Math.max(260, Math.abs(tx - cat.x) / 1.1), 'run');
    yield* jumpTo(tx, perchY(), 0.5, 40);
    cat.facing = 1;
    anim('swipe');
    yield* wait(0.4);
    closeReels(tab);
    fx('sparkle', 24, 20);
    yield* wait(0.35);
    say(pick(['not today!', 'nope. back to work', 'caught you 🐾', 'focus mode!']));
    yield* play('happy', 1.3);
    yield* jumpTo(cat.x, ground(), 0.55, 25);
  }

  function* life() {
    let nextDemo = cat.t + 2.5;
    while (true) {
      if (Math.abs(cat.y - ground()) > 3) yield* jumpTo(cat.x, ground(), 0.5, 20);
      if (cat.t > nextDemo) { yield* reelsDemo(); nextDemo = cat.t + rand(11, 16); continue; }
      const r = Math.random();
      if (r < 0.4) yield* walkTo(rand(S(), W() - S()));
      else if (r < 0.6) yield* play('idle', rand(1.5, 3));
      else if (r < 0.7) yield* play('groom', 2.4);
      else if (r < 0.78) yield* play('yawn');
      else if (r < 0.86) { fx('question', 0, -4); yield* play('idle', 1.2); }
      else if (r < 0.93) yield* play('loaf', 3);
      else { anim('roll'); const tx = clampX(cat.x + pick([-1, 1]) * 70); while (Math.abs(cat.x - tx) > 1) { cat.x += Math.sign(tx - cat.x) * Math.min(Math.abs(tx - cat.x), 60 * cat.dt); yield; } }
    }
  }
  function* petted() {
    anim('happy');
    for (let i = 0; i < 3; i++) { fx('heart', rand(-14, 14), 4); yield* wait(0.3); }
    yield* wait(0.7);
  }
  let hold = null;
  function* held() {
    anim('dangle');
    while (hold) { cat.x = hold.x; cat.y = hold.y + S() * 0.85; yield; }
  }
  function* falling(vx, vy) {
    anim('pounce', 4);
    const y0 = cat.y;
    while (true) {
      vy += 2400 * cat.dt; vx *= Math.pow(0.5, cat.dt);
      cat.x += vx * cat.dt; cat.y += vy * cat.dt;
      if (cat.x < S() * 0.55 || cat.x > W() - S() * 0.55) { vx = -vx * 0.5; cat.x = clampX(cat.x); }
      if (Math.abs(vx) > 30) cat.facing = Math.sign(vx);
      if (cat.y >= ground()) { cat.y = ground(); break; }
      yield;
    }
    anim('pounce', 6); yield* wait(0.2);
    if (ground() - y0 > 120) { fx('bang', 0, -6); yield* play('surprised', 0.5); yield* play('groom', 1.6); }
    else yield* play('happy', 0.8);
  }

  let task = life();
  const run = gen => { task = (function* () { yield* gen; yield* life(); })(); };

  function render() {
    const s = S();
    actor.style.transform = `translate(${cat.x - s / 2}px, ${cat.y - s}px)`;
    setSprite(sprite, cat.anim, cat.frame);
    sprite.classList.toggle('flip', cat.facing < 0 && !NO_FLIP.has(cat.anim));
    placeSay();
  }

  // Pointer: click = pet, drag = pick up (then it falls and lands).
  let down = null, lastMove = null;
  const local = e => { const b = scene.getBoundingClientRect(); return { x: e.clientX - b.left, y: e.clientY - b.top }; };
  actor.addEventListener('pointerdown', e => {
    down = local(e); actor.setPointerCapture(e.pointerId);
  });
  actor.addEventListener('pointermove', e => {
    if (!down) return;
    const p = local(e);
    if (!hold && Math.hypot(p.x - down.x, p.y - down.y) > 5) {
      hold = { ...p, vx: 0, vy: 0 }; sayEl.hidden = true; actor.style.cursor = 'grabbing';
      run(held());
    }
    if (hold) {
      const now = performance.now();
      if (lastMove) { const dt = Math.max(8, now - lastMove.t) / 1000; hold.vx = (p.x - hold.x) / dt; hold.vy = (p.y - hold.y) / dt; }
      lastMove = { t: now };
      hold.x = Math.min(Math.max(p.x, 0), W()); hold.y = Math.min(Math.max(p.y, 0), ground());
    }
  });
  const up = () => {
    if (!down) return;
    if (hold) { const { vx, vy } = hold; hold = null; run(falling(Math.max(-1200, Math.min(1200, vx)), Math.max(-900, Math.min(900, vy)))); }
    else run(petted());
    down = null; lastMove = null; actor.style.cursor = '';
  };
  actor.addEventListener('pointerup', up);
  actor.addEventListener('pointercancel', up);

  // Only animate while the scene is on screen.
  let visible = true, last = performance.now();
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; last = performance.now(); }).observe(scene);
  function frame(now) {
    requestAnimationFrame(frame);
    if (!visible) return;
    cat.dt = Math.min(0.05, (now - last) / 1000); last = now;
    cat.t += cat.dt;
    task.next();
    render();
  }
  cat.x = W() * 0.72; cat.y = ground();
  // ?pose=hunt freezes the cat mid-swat on a Reels tab (used for the share image and screenshots).
  if (new URLSearchParams(location.search).get('pose') === 'hunt') {
    const tab = openReels(); tab.classList.remove('enter');
    const r = rel(tab);
    Object.assign(cat, { x: clampX(r.x + r.w / 2), y: perchY(), facing: 1 });
    anim('swipe', 2); render();
    fx('bang', 0, -4);
    say('not today!', 1e9);
    document.querySelectorAll('.fx').forEach(el => { el.style.animation = 'none'; el.style.transform = 'translateY(-14px)'; });
    return;
  }
  if (reduceMotion) { anim('idle'); render(); }
  else requestAnimationFrame(frame);
  addEventListener('resize', () => { cat.x = clampX(cat.x); if (!hold && Math.abs(cat.y - ground()) < 40) cat.y = ground(); });
})();
