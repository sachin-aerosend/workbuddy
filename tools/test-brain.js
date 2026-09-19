// Headless simulation of the cat's brain across two monitors: every behaviour, missions,
// typing and nudges. Fails loudly on exceptions or the cat leaving the screens.
const assert = require('assert');
const { Brain } = require('../app/brain');

const displays = [
  { bounds: { x: 0, y: 0, width: 1920, height: 1080 }, workArea: { x: 0, y: 0, width: 1920, height: 1032 } },
  { bounds: { x: 1920, y: 0, width: 1280, height: 1024 }, workArea: { x: 1920, y: 0, width: 1280, height: 984 } },
];
// Water reminders run often during the long random stretch (robustness), then are switched off
// until their own deterministic section at the end.
const settings = { speed: 1, typingBuddy: true, sleepAfterIdleMinutes: 4, waterEnabled: true, waterEveryMinutes: 5 };
const closed = [], answers = [];
let perch = { x0: 0, x1: 1920, y: 86 }; // a maximized window on the laptop screen
let glasses = 0, waterGate = 'show', waterNotified = 0;
const brain = new Brain({
  displays: () => displays, settings: () => settings, perch: () => perch,
  onCloseTab: (id, why) => closed.push([id, why]),
  onBubbleAnswer: (b, a) => { answers.push([b.kind, a]); if (b.kind === 'water' && a === 'yes') glasses++; },
  waterGate: () => waterGate, onWaterNotify: () => waterNotified++, waterCount: () => glasses,
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

// Random behaviours mean "right now" checks can catch the cat mid-hop; wait for a condition instead.
function eventually(pred, secs = 6, check = true) {
  for (let i = 0; i < secs * 10; i++) { if (pred()) return true; run(0.1, check); }
  return pred();
}

// Long stretch of free life to exercise the random routine.
let waterStarts = 0;
for (let i = 0, was = false; i < 60 * 30 * 10; i++) { run(0.1); const now = brain.mode === 'water'; if (now && !was) waterStarts++; was = now; }
assert(waterStarts >= 2, `water reminders fired during free life: ${waterStarts}`);
settings.waterEnabled = false; run(0.1);
assert.strictEqual(brain.waterDue, null, 'water off -> no schedule');

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
assert(eventually(() => brain.onGround()), 'back on the taskbar after patrolling');
perch = { x0: 0, x1: 1920, y: 86 };
// Window switched away mid-patrol: the cat hops off instead of floating.
brain.interrupt(brain.grounded(brain.tabPatrol()), 'life'); run(2);
perch = null; run(3);
assert(eventually(() => brain.onGround()), 'dropped when the window went away');
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
brain.interrupt(brain.sit(), 'life');
brain.lastKey = -100; brain.queueNudge({ tabId: 8, title: 'x', domain: 'x.com', idleMinutes: 70 });
assert(eventually(() => answers.at(-1)?.[1] === 'later', 45), `timed-out nudge counts as later: ${answers.at(-1)}`);

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
assert(eventually(() => brain.onGround(), 6, false), `landed on the taskbar: y=${brain.y}`);
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
assert(eventually(() => brain.onGround()), 'hops down once the window is gone');
perch = { x0: 0, x1: 1920, y: 86 };

// Right-click menu opens as a bubble and closes cleanly.
brain.openMenu([{ id: 'remind', label: 'x' }]); run(1);
assert.strictEqual(brain.bubble?.kind, 'menu');
brain.closeBubble(); run(0.5);
assert.strictEqual(brain.bubble, null); assert.notStrictEqual(brain.mode, 'menu');

// Countdown shows a clock; playing with it puts it on the floor, then it floats back.
brain.setCountdown(300);
assert(brain.state().clock && brain.state().clock.remain === 300, 'clock with countdown');
brain.interrupt(brain.grounded(brain.clockPlay()), 'life'); run(1);
assert(brain.state().clock.ground != null, 'clock on the floor while playing');
let floatedBack = false;
for (let i = 0; i < 80 && !floatedBack; i++) { run(0.1); floatedBack = brain.state().clock.ground == null; }
assert(floatedBack, 'clock back over her head');

// Alarm rings until answered, survives a Reels mission in the middle, snooze answer is reported.
brain.alarm({ id: 'r1', text: 'call mom' }); run(1.5);
assert.strictEqual(brain.bubble?.kind, 'alarm'); assert(brain.state().clock.ringing);
brain.mission(99, null); run(4);
assert.strictEqual(brain.bubble?.kind, 'alarm', 'alarm comes back after the mission');
brain.answerBubble('snooze'); run(1.5);
assert.deepStrictEqual(answers.at(-1), ['alarm', 'snooze']);
assert.strictEqual(brain.activeAlarm, null);
brain.setCountdown(null);

// ---------- Water reminders ----------
const calm = () => { brain.ball = null; brain.bubble = null; brain.pendingNudge = null; brain.lastKey = -100; idle = 0; brain.interrupt(brain.sit(), 'life'); };
const dueIn = () => brain.waterDue - brain.t;
const waterBubble = () => brain.bubble?.kind === 'water';
calm();
settings.waterEnabled = true; settings.waterEveryMinutes = 20; run(0.1);
assert(Math.abs(dueIn() - 1200) < 1, `scheduled 20 min out: ${dueIn()}`);
// Fires after the interval (not before; free life may delay it a little), drinking first, then asking.
const scheduledFrom = brain.t;
let firedAt = null;
for (let i = 0; i < 1400 * 10 && firedAt == null; i++) { run(0.1); if (brain.mode === 'water') firedAt = brain.t; }
assert(firedAt != null && firedAt - scheduledFrom >= 1199, `water reminder fired after the interval: ${firedAt - scheduledFrom}`);
assert(eventually(() => brain.anim === 'drink', 3), `drinks first: ${brain.anim}`);
assert(eventually(waterBubble, 6), 'water bubble shown');
assert.strictEqual(brain.anim, 'holdGlass');
assert.deepStrictEqual(brain.bubble.buttons.map(b => b.id), ['yes', 'notyet', 'snooze']);
// Pets don't cancel it.
brain.pet(); run(0.2); assert(waterBubble(), 'petting keeps the water question up');
// Yes -> counted, celebration, next one a full interval away.
brain.answerBubble('yes'); run(0.2);
assert.strictEqual(glasses, 1, 'glass counted');
assert.deepStrictEqual(answers.at(-1), ['water', 'yes']);
assert(Math.abs(dueIn() - 1200) < 2, `next in 20 min: ${dueIn()}`);
assert(/1 glass today/.test(brain.bubble?.text || ''), `cheers: ${brain.bubble?.text}`);
run(3);

// Deferred while typing: due now, but the cat waits until the typing has stopped for a bit.
calm(); brain.waterDue = brain.t;
for (let i = 0; i < 50; i++) { brain.onKey(i % 2 ? 16 : 25); run(0.2); assert.notStrictEqual(brain.mode, 'water', 'no water while typing'); }
run(5); assert.notStrictEqual(brain.mode, 'water', 'still waits right after typing');
assert(eventually(() => brain.mode === 'water', 20), 'asks once typing has settled');
// Snooze -> 10 min.
assert(eventually(waterBubble, 6), 'bubble after typing');
brain.answerBubble('snooze'); run(0.2);
assert(Math.abs(dueIn() - 600) < 2, `snoozed 10 min: ${dueIn()}`);
assert.strictEqual(glasses, 1, 'snooze does not count a glass');
run(2);

// Not yet -> gentle retry after 15 min with a different line; a few misses in a row -> back to normal.
calm(); brain.waterDue = brain.t;
assert(eventually(waterBubble, 8), 'bubble for not-yet');
brain.answerBubble('notyet'); run(0.2);
assert(Math.abs(dueIn() - 900) < 2, `retry in 15 min: ${dueIn()}`);
run(3); calm(); brain.waterDue = brain.t;
assert(eventually(waterBubble, 8), 'retry bubble');
assert(/how about/.test(brain.bubble.text), `gentler retry text: ${brain.bubble.text}`);
// Unanswered: disappears quietly after ~30 s and counts as a miss.
assert(eventually(() => !waterBubble(), 40), 'unanswered bubble goes away');
run(1);
assert.notStrictEqual(brain.mode, 'water');
assert(Math.abs(dueIn() - 900) < 5, `unanswered -> retry in 15 min: ${dueIn()}`);
assert.strictEqual(brain.waterMisses, 2);
// Third miss in a row: stop nagging, back to the normal interval.
calm(); brain.waterDue = brain.t;
assert(eventually(waterBubble, 8));
assert(eventually(() => !waterBubble(), 40)); run(1);
assert(Math.abs(dueIn() - 1200) < 5, `after 3 misses, back to 20 min: ${dueIn()}`);
assert.strictEqual(brain.waterMisses, 0);

// Away: the timer pauses; coming back gives one reminder a little later, not a pile.
calm(); brain.waterDue = brain.t + 30;
idle = 400; run(600);
assert.notStrictEqual(brain.mode, 'water', 'no reminder while away');
assert(dueIn() >= 89, `pushed past the return: ${dueIn()}`);
idle = 0;
let starts = 0;
for (let i = 0, was = false; i < 400 * 10; i++) { run(0.1); const now = brain.mode === 'water'; if (now && !was) { starts++; if (starts === 1) assert(eventually(waterBubble, 6)); } was = now; }
assert.strictEqual(starts, 1, `exactly one reminder after coming back: ${starts}`);

// Interrupted mid-drink by a Reels mission: no crash, bubble gone, retried a few minutes later.
calm(); brain.waterDue = brain.t;
assert(eventually(() => brain.mode === 'water', 5));
run(0.5); brain.mission(77, null); run(5);
assert.notStrictEqual(brain.mode, 'water');
assert(!waterBubble(), 'no leftover water bubble');
assert(dueIn() > 200 && dueIn() <= 300, `fallback retry: ${dueIn()}`);

// Cat hidden: a notification instead of the cat, then the normal interval.
calm(); waterGate = 'notify'; brain.waterDue = brain.t; run(0.5);
assert.strictEqual(waterNotified, 1); assert.notStrictEqual(brain.mode, 'water');
assert(Math.abs(dueIn() - 1200) < 2);
// Full screen: wait and check again in a minute.
waterGate = 'defer'; brain.waterDue = brain.t; run(0.5);
assert.notStrictEqual(brain.mode, 'water'); assert(dueIn() > 55 && dueIn() <= 60);
waterGate = 'show';
// Interval changed live -> rescheduled with the new interval.
settings.waterEveryMinutes = 45; run(0.1);
assert(Math.abs(dueIn() - 2700) < 1, `interval change applies live: ${dueIn()}`);
// "Drink water now" from the menu: drinks, celebrates, restarts the interval.
calm(); glasses++; brain.drinkNow();
assert(eventually(() => brain.anim === 'drink', 3));
assert(eventually(() => /2 glasses today/.test(brain.bubble?.text || ''), 6), 'drink-now celebration');
assert(Math.abs(dueIn() - 2700) < 10);
settings.waterEnabled = false; run(0.1); assert.strictEqual(brain.waterDue, null);
run(5);

// Monitor unplugged: cat is pulled back onto the remaining screen.
displays.pop(); brain.x = brain.clampX(brain.x); run(60);

console.log('ok - animations exercised:', [...seen].sort().join(', '));
