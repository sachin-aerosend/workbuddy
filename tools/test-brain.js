// Headless simulation of the cat's brain across two monitors: every behaviour, missions,
// typing and nudges. Fails loudly on exceptions or the cat leaving the screens.
const assert = require('assert');
const { Brain } = require('../app/brain');

const displays = [
  { bounds: { x: 0, y: 0, width: 1920, height: 1080 }, workArea: { x: 0, y: 0, width: 1920, height: 1032 } },
  { bounds: { x: 1920, y: 0, width: 1280, height: 1024 }, workArea: { x: 1920, y: 0, width: 1280, height: 984 } },
];
const settings = { speed: 1, typingBuddy: true, sleepAfterIdleMinutes: 4 };
const closed = [], answers = [];
let perch = { x0: 0, x1: 1920, y: 86 }; // a maximized window on the laptop screen
const brain = new Brain({
  displays: () => displays, settings: () => settings, perch: () => perch,
  onCloseTab: (id, why) => closed.push([id, why]),
  onBubbleAnswer: (b, a) => answers.push([b.kind, a]),
});

const seen = new Set();
let idle = 0;
function run(secs, check = true) {
  for (let i = 0; i < secs * 30; i++) {
    brain.tick(1 / 30, idle);
    seen.add(brain.anim);
    const st = brain.state();
    assert(Number.isFinite(brain.x) && Number.isFinite(brain.y), 'position went NaN');
    if (check && brain.mode !== 'mission') {
      assert(brain.x >= -5 && brain.x <= 3205, `x out of screens: ${brain.x}`);
      assert(brain.y <= 1035 && brain.y > 20, `y off screen: ${brain.y} (${brain.anim})`);
    }
    JSON.stringify(st);
  }
}

// Long stretch of free life to exercise the random routine.
run(60 * 30);

// Each behaviour directly.
for (const b of ['sit', 'wander', 'visitMonitor', 'groom', 'loaf', 'boxTime', 'zoomies', 'rollAround', 'ballPlay']) {
  brain.interrupt(brain[b](), 'life');
  run(40);
}
brain.interrupt(brain.nap(5), 'life'); run(10);

// Tab patrol: hops up onto the window's top edge, strolls within it, then drops back down.
brain.x = 800; brain.y = brain.ground(800);
brain.interrupt(brain.grounded(brain.tabPatrol()), 'life');
let perched = false;
for (let i = 0; i < 30 && !perched; i++) { run(0.1); perched = Math.abs(brain.y - 86) < 1; }
assert(perched, `perched on tabs: y=${brain.y}`);
let minX = 1e9, maxX = -1e9;
for (let i = 0; i < 300; i++) { run(0.1); if (Math.abs(brain.y - 86) < 1) { minX = Math.min(minX, brain.x); maxX = Math.max(maxX, brain.x); } }
assert(minX >= 30 && maxX <= 1890, `stayed on the perch: ${minX}..${maxX}`);
perch = null; run(4); // (with a window around it may choose to patrol again, so take it away)
assert(brain.onGround(), 'back on the taskbar after patrolling');
perch = { x0: 0, x1: 1920, y: 86 };
// Window switched away mid-patrol: the cat hops off instead of floating.
brain.interrupt(brain.grounded(brain.tabPatrol()), 'life'); run(2);
perch = null; run(3);
assert(brain.onGround(), 'dropped when the window went away');
perch = { x0: 0, x1: 1920, y: 86 };

// Typing: burst of keys enters typing mode, frames follow key side, then it wanders off.
// (Start from a calm, grounded cat: typing mode deliberately waits while the ball is out.)
brain.ball = null; brain.y = brain.ground(brain.x); brain.interrupt(brain.sit(), 'life');
for (let i = 0; i < 8; i++) { brain.onKey(i % 2 ? 16 : 25); run(0.1); }
assert.strictEqual(brain.mode, 'typing');
brain.onKey(28); assert.strictEqual(brain.frame, 3, 'enter = both paws');
run(8);
assert.notStrictEqual(brain.mode, 'typing', 'typing should end after a pause');

// Blocked tab mission on the other monitor.
brain.mission(42, { x: 2500, y: 60 });
run(6, false);
assert.deepStrictEqual(closed.at(-1), [42, 'blocked']);
run(3);
assert(brain.y > 900, 'cat came back down to the taskbar');

// Mission with unknown tab position.
brain.mission(43, null); run(4);
assert.deepStrictEqual(closed.at(-1), [43, 'blocked']);

// Nudge: waits for a calm moment, asks, answer "yes" is reported.
brain.interrupt(brain.sit(), 'life'); brain.mode = 'life';
brain.lastKey = -100;
brain.queueNudge({ tabId: 7, title: 'LinkedIn Feed', domain: 'linkedin.com', idleMinutes: 190 });
run(1);
assert(brain.bubble && brain.bubble.kind === 'ask', 'nudge bubble shown');
assert(/3 h/.test(brain.bubble.sub), brain.bubble.sub);
brain.answerBubble('yes'); run(3);
assert.deepStrictEqual(answers.at(-1), ['ask', 'yes']);

// Nudge that times out counts as "later".
brain.lastKey = -100; brain.queueNudge({ tabId: 8, title: 'x', domain: 'x.com', idleMinutes: 70 });
run(30);
assert.deepStrictEqual(answers.at(-1), ['ask', 'later']);

// Away -> sleeps, back -> wakes.
idle = 400; run(5);
assert.strictEqual(brain.anim, 'sleep');
idle = 0; run(6);
assert.notStrictEqual(brain.mode, 'away');

// Drag & drop: carried under the cursor, dropped from mid-screen -> lands on the taskbar.
brain.x = 900; brain.y = brain.ground(900);
brain.grab(900, 900, 80);
for (let i = 0; i < 20; i++) { brain.holdAt(900 + i * 5, 900 - i * 25); run(1 / 30, false); }
assert.strictEqual(brain.mode, 'held'); assert.strictEqual(brain.anim, 'dangle');
assert(Math.abs(brain.x - 995) < 1 && Math.abs(brain.y - (425 + 68)) < 1, `follows cursor: ${brain.x},${brain.y}`);
brain.release(); run(0.1, false);
assert.strictEqual(brain.anim, 'pounce');
run(3, false);
assert(brain.onGround(), `landed on the taskbar: y=${brain.y}`);
// Thrown hard toward the far right edge of the right-most screen: bounces back, stays on screen.
brain.grab(3000, 500, 80); brain.holdAt(3000, 500); run(0.05, false);
brain.hold.vx = 1500; brain.release();
let landedAt = null;
for (let i = 0; i < 60 && landedAt == null; i++) { run(0.05, false); if (brain.onGround()) landedAt = brain.x; }
assert(landedAt != null && landedAt <= 3200 - 20, `bounced and landed: x=${landedAt}`);
// Interrupting ball play must not leave the ball behind (it would block typing and nudges).
brain.interrupt(brain.grounded(brain.ballPlay()), 'life'); run(1.5);
brain.pet(); run(2);
assert.strictEqual(brain.ball, null, 'ball put away after an interruption');
// Dropped from above a window's top edge -> lands on the window and strolls there.
brain.grab(700, 0, 80); run(0.05, false); brain.release();
run(1.2, false);
assert(Math.abs(brain.y - 86) < 1, `landed on the window edge: y=${brain.y}`);
perch = null; run(4);
assert(brain.onGround(), 'hops down once the window is gone');
perch = { x0: 0, x1: 1920, y: 86 };

// Monitor unplugged: cat is pulled back onto the remaining screen.
displays.pop(); brain.x = brain.clampX(brain.x); run(60);

console.log('ok - animations exercised:', [...seen].sort().join(', '));
