package com.workbuddy.cat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/** A tiny effect (heart, z, !, ?, sparkle) that floats up from the cat and fades. */
data class Fx(val type: String, val born: Float, val dx: Float, val life: Float)

data class BubbleButton(val id: String, val label: String)
/** kinds: say, ask (nudge), alarm, menu */
data class Bubble(
    val kind: String, val text: String, val sub: String = "",
    val buttons: List<BubbleButton> = emptyList(), val until: Float = Float.MAX_VALUE, val tag: String = "",
)

data class Ball(var x: Float, var y: Float, var vx: Float, var spin: Float = 0f)

/** Where the floor is for the cat right now (feet y, in px), provided by the service. */
interface World {
    val width: Int
    val zone: Zone
    fun groundBottom(): Float
    fun groundTop(): Float
    val px: Float          // screen px per art px (cat scale)
    val dp: Float          // screen px per dp
    val lowPower: Boolean
    val quiet: Boolean
}

/**
 * The cat's behaviour engine, redesigned for a phone: one screen, edge "zones", touch input, keyboard
 * awareness and a lazier low-power personality. Behaviours are coroutine-style sequences that yield once
 * per frame, so they read top to bottom and can be interrupted (swats, alarms, touch).
 */
class Brain(private val w: World, private val events: Events) {
    interface Events {
        fun swatHit()                      // the paw lands: press Back now
        fun bubbleAnswered(b: Bubble, answer: String)
    }

    var x = 0f; var y = 0f; var facing = 1
    var anim = "idle"; var frame: Int? = null; var seq = 0
    var t = 0f; var dt = 0f
    var fx = mutableListOf<Fx>(); var ball: Ball? = null; var bubble: Bubble? = null
    var tilt = 0f
    var onTop = false                    // which edge we're on (only matters for Zone.BOTH)
    var countdown: Float? = null; var clockGround: Float? = null; var ringing = false
    var mode = "life"
    var lastType = -1e9f; private var typeTimes = ArrayDeque<Float>()
    var keyUpAt = 0f

    private var task: Iterator<Unit> = life().iterator()
    private var answer: String? = null
    private var hold: FloatArray? = null        // x, y, vx, vy while being carried
    private val alarmQueue = ArrayDeque<Reminder>()
    private var activeAlarm: Reminder? = null

    fun place(x0: Float) { x = x0; onTop = w.zone == Zone.TOP; y = ground() }

    // ---------------- plumbing ----------------
    private fun setAnim(name: String, f: Int? = null) { if (name != anim) { anim = name; seq++ }; frame = f }
    private fun addFx(type: String, dx: Float = 0f, life: Float = 1.5f) { fx.add(Fx(type, t, dx, life)) }
    private fun interrupt(seqGen: Sequence<Unit>, m: String) {
        task = seqGen.iterator(); mode = m; ball = null; tilt = 0f; clockGround = null
    }
    private fun speed(base: Float) = base * w.dp * (if (w.lowPower) 0.6f else 1f)
    private fun catH() = w.frameArt() * w.px
    private fun World.frameArt() = 32f

    fun ground(): Float = if (onTop && w.zone != Zone.BOTTOM) w.groundTop() else w.groundBottom()
    fun clampX(v: Float): Float { val m = 26 * w.px; return min(max(v, m), w.width - m) }
    private val onGround get() = abs(y - ground()) < 3 * w.dp

    fun tick(dtIn: Float) {
        dt = min(dtIn, 0.1f); t += dt
        fx.removeAll { t - it.born > it.life }
        stepBall()
        if (mode == "typing" && t - lastType > 5f) interrupt(afterTyping(), "life")
        if (mode == "typing" && keyUpAt > 0 && t > keyUpAt) { setAnim("typing", 0); keyUpAt = 0f }
        if (mode == "life" && (activeAlarm != null || alarmQueue.isNotEmpty())) interrupt(ringing(), "alarm")
        // keep feet glued to the floor when it moves (keyboard opening, zone change, rotation)
        if (mode == "life" || mode == "typing" || mode == "menu") y += (ground() - y) * min(1f, dt * 10)
        try {
            if (!task.hasNext()) { task = life().iterator(); mode = "life" } else task.next()
        } catch (e: Throwable) {
            android.util.Log.e("WorkBuddy", "behaviour crashed", e)
            bubble = null; ball = null; clockGround = null
            task = life().iterator(); mode = "life"
        }
    }

    // ---------------- inputs ----------------
    fun onType() {
        lastType = t
        typeTimes.addLast(t); while (typeTimes.isNotEmpty() && t - typeTimes.first() > 2f) typeTimes.removeFirst()
        if (mode == "life" && typeTimes.size >= 2) interrupt(typing(), "typing")
        if (mode == "typing") { setAnim("typing", if (Random.nextBoolean()) 1 else 2); keyUpAt = t + 0.13f }
    }
    fun pet() { if (mode in setOf("life", "typing")) interrupt(petted(), "pet") }
    fun swat() { bubble = null; hold = null; interrupt(swatMission(), "mission") }
    fun nudge(appLabel: String, minutes: Int, pkg: String) {
        if (mode != "life") return
        interrupt(ask(Bubble("ask", "$minutes min on $appLabel", "take a break?",
            listOf(BubbleButton("close", "close it"), BubbleButton("more", "5 more min"), BubbleButton("stop", "not today")), tag = pkg)), "nudge")
    }
    fun openMenu(items: List<BubbleButton>) {
        if (mode == "mission" || mode == "held" || mode == "alarm") return
        interrupt(waitOnBubble(Bubble("menu", "", buttons = items), 12f), "menu")
    }
    fun say(text: String, secs: Float = 2.5f) { bubble = Bubble("say", text, until = t + secs) }
    fun playBall() = interrupt(sequence { dropToGround(); yieldAll(ballPlay()) }, "life")
    fun napNow() = interrupt(sequence { dropToGround(); yieldAll(nap(60f)) }, "life")
    fun answer(id: String) {
        val b = bubble ?: return
        bubble = null; answer = id
        events.bubbleAnswered(b, id)
    }
    fun alarm(r: Reminder) { alarmQueue.addLast(r); if (mode != "alarm" && mode != "held") interrupt(ringing(), "alarm") }
    fun grab(hx: Float, hy: Float) { bubble = null; hold = floatArrayOf(hx, hy, 0f, 0f); interrupt(held(), "held") }
    fun holdAt(hx: Float, hy: Float) {
        val h = hold ?: return
        val d = max(dt, 1 / 60f)
        h[2] = h[2] * 0.5f + (hx - h[0]) / d * 0.5f; h[3] = h[3] * 0.5f + (hy - h[1]) / d * 0.5f
        h[0] = hx; h[1] = hy
    }
    fun release() {
        val h = hold ?: return; hold = null
        val cap = 2600 * w.dp / 2.6f
        interrupt(falling(h[2].coerceIn(-cap, cap), h[3].coerceIn(-cap, cap)), "life")
    }

    // ---------------- building blocks ----------------
    private suspend fun SequenceScope<Unit>.wait(secs: Float) {
        val end = t + secs
        while (t < end) { if ((bubble?.until ?: Float.MAX_VALUE) < t) bubble = null; yield(Unit) }
    }
    private suspend fun SequenceScope<Unit>.play(name: String, secs: Float? = null) {
        setAnim(name); wait(secs ?: w.anim(name))
    }
    private fun World.anim(name: String) = animSeconds[name] ?: 1f

    private suspend fun SequenceScope<Unit>.walkTo(tx0: Float, speedDp: Float = 36f, anim: String = "walk") {
        val tx = clampX(tx0)
        facing = if (tx > x) 1 else -1
        setAnim(anim)
        while (abs(tx - x) > 1.5f) {
            x += sign(tx - x) * min(abs(tx - x), speed(speedDp) * dt)
            y += (ground() - y) * min(1f, dt * 12)
            yield(Unit)
        }
    }
    private suspend fun SequenceScope<Unit>.jumpTo(tx: Float, ty: Float, dur: Float = 0.45f, arcDp: Float = 40f) {
        val x0 = x; val y0 = y
        facing = if (tx >= x0) 1 else -1
        setAnim("pounce", 4)
        var k = 0f
        while (k < 1f) {
            k = min(1f, k + dt / dur)
            x = x0 + (tx - x0) * k
            y = y0 + (ty - y0) * k - sin(PI * k).toFloat() * arcDp * w.dp
            yield(Unit)
        }
        setAnim("pounce", 6); wait(0.15f)
    }
    private suspend fun SequenceScope<Unit>.dropToGround() { if (!onGround) jumpTo(x, ground(), 0.4f, 12f) }

    // ---------------- life ----------------
    private fun life(): Sequence<Unit> = sequence {
        while (true) {
            dropToGround()
            val lp = w.lowPower; val q = w.quiet
            val choices = listOf(
                (if (lp) 20f else 28f) to { sit() },
                (if (lp) 8f else 24f) to { wander() },
                8f to { sequence<Unit> { play("groom", w.anim("groom") * 2) } },
                5f to { sequence<Unit> { play("yawn") } },
                4f to { sequence<Unit> { play("stretch", w.anim("stretch") * 2) } },
                (if (lp) 25f else 8f) to { sequence<Unit> { play("loaf", rnd(15f, 40f)) } },
                (if (lp) 22f else 5f) to { nap(rnd(30f, 90f)) },
                4f to { sequence<Unit> { play("box", rnd(12f, 30f)) } },
                (if (q || lp) 0f else 4f) to { zoomies() },
                (if (q || lp) 0f else 7f) to { ballPlay() },
                (if (q || lp) 0f else 3f) to { rollAround() },
                (if (w.zone == Zone.BOTH && !q) 5f else 0f) to { switchEdge() },
                (if (countdown != null && !lp) 12f else 0f) to { clockPlay() },
                0.5f to { sequence<Unit> { play("dracula", rnd(8f, 14f)) } },
            )
            var r = Random.nextFloat() * choices.sumOf { it.first.toDouble() }.toFloat()
            val pick = choices.firstOrNull { r -= it.first; r < 0 }?.second ?: choices[0].second
            yieldAll(pick())
        }
    }

    private fun sit() = sequence {
        val end = t + rnd(4f, 10f)
        while (t < end) { play("idle", rnd(2f, 4f)); if (Random.nextFloat() < 0.5f) play("blink") }
    }
    private fun wander() = sequence {
        val edge = Random.nextFloat() < 0.6f
        val m = 40 * w.dp
        val tx = if (edge) (if (Random.nextBoolean()) rnd(m, m * 4) else w.width - rnd(m, m * 4)) else rnd(m, w.width - m)
        walkTo(tx); play("idle", rnd(1f, 3f))
    }
    private fun nap(secs: Float) = sequence {
        play("loaf", 3f); setAnim("sleep")
        val end = t + secs; var nextZ = t
        while (t < end) { if (t > nextZ) { addFx("z", life = 2.4f); nextZ = t + 1.4f }; yield(Unit) }
        play("stretch")
    }
    private fun zoomies() = sequence {
        val home = x; val far = if (x > w.width / 2f) 50 * w.dp else w.width - 50 * w.dp
        walkTo(far, 220f, "run"); play("surprised", 0.4f); walkTo(home, 220f, "run"); play("happy", 1.2f)
    }
    private fun rollAround() = sequence {
        val dir = if (Random.nextBoolean()) 1 else -1
        val tx = clampX(x + dir * 60 * w.dp); facing = dir; setAnim("roll")
        while (abs(x - tx) > 1) { x += sign(tx - x) * min(abs(tx - x), 45 * w.dp * dt); yield(Unit) }
        play("happy", 1f)
    }
    /** Zone.BOTH: a big leap from one edge to the other. */
    private fun switchEdge() = sequence {
        play("pounce", 0.5f)
        onTop = !onTop
        jumpTo(clampX(x + rnd(-80f, 80f) * w.dp), ground(), 0.9f, if (onTop) 0f else 60f)
        play("happy", 0.8f)
    }

    // ---------------- ball ----------------
    private fun stepBall() {
        val b = ball ?: return
        b.x += b.vx * dt; b.spin += b.vx * dt / 4
        b.vx -= sign(b.vx) * min(abs(b.vx), 380 * w.dp / 2.6f * dt)
        val m = 12 * w.dp
        if (b.x < m || b.x > w.width - m) { b.vx = -b.vx * 0.6f; b.x = b.x.coerceIn(m, w.width - m) }
        b.y = ground()
    }
    private fun ballPlay() = sequence {
        facing = if (x > w.width / 2f) -1 else 1
        ball = Ball(clampX(x + facing * 45 * w.dp), y, 0f)
        addFx("sparkle", facing * 45 * w.dp)
        play("surprised", 0.5f)
        repeat(Random.nextInt(3, 6)) {
            val b = ball ?: return@sequence
            facing = if (b.x > x) 1 else -1
            setAnim("pounce", 0)
            for (f in 0..3) { frame = f; wait(0.14f) }
            jumpTo(clampX(b.x - facing * 14 * w.dp), ground(), 0.35f, 22f)
            b.vx = facing * rnd(170f, 280f) * w.dp / 2.6f
            wait(0.2f)
            while (abs(b.vx) > 3) {
                val gap = b.x - x
                if (abs(gap) > 26 * w.dp) { facing = sign(gap).toInt(); setAnim("run"); x += facing * 140 * w.dp / 2.6f * dt } else setAnim("idle")
                yield(Unit)
            }
            walkTo(b.x - sign(b.x - x) * 22 * w.dp, 60f)
        }
        play("happy", 1.2f); ball = null; play("loaf", rnd(5f, 10f))
    }

    // ---------------- typing ----------------
    private fun typing() = sequence { setAnim("typing", 0); while (true) yield(Unit) }
    private fun afterTyping() = sequence { play("stretch"); play("idle", 1.5f) }

    // ---------------- touch ----------------
    private fun petted() = sequence {
        setAnim("happy")
        repeat(3) { addFx("heart", rnd(-10f, 10f) * w.dp, 1.4f); wait(0.3f) }
        wait(0.8f)
    }
    private fun held() = sequence {
        setAnim("dangle")
        while (true) {
            val h = hold ?: break
            x = h[0]; y = h[1] + catH() * 0.85f
            tilt = (-h[2] / (2500 * w.dp / 2.6f)).coerceIn(-0.35f, 0.35f)
            yield(Unit)
        }
    }
    private fun falling(vx0: Float, vy0: Float) = sequence {
        tilt = 0f
        // In "both edges", a cat let go in the top third flies up to the top edge.
        if (w.zone == Zone.BOTH) onTop = y < w.groundBottom() / 3f
        if (w.zone == Zone.TOP) onTop = true
        var vx = vx0; var vy = vy0
        val startY = y
        setAnim("pounce", 4)
        if (onTop && w.zone != Zone.BOTTOM) {
            jumpTo(clampX(x + vx * 0.2f), ground(), 0.5f, -20f)
        } else {
            val g = 2600 * w.dp / 2.6f
            while (true) {
                vy += g * dt; vx *= 0.6f.pow(dt)
                x += vx * dt; y += vy * dt
                if (abs(vx) > 30) facing = sign(vx).toInt()
                val m = 20 * w.dp
                if (x < m || x > w.width - m) { vx = -vx * 0.5f; x = x.coerceIn(m, w.width - m) }
                if (y < catH()) { y = catH(); vy = abs(vy) * 0.3f }
                if (y >= ground()) { y = ground(); break }
                yield(Unit)
            }
        }
        setAnim("pounce", 6); wait(0.25f)
        if (abs(y - startY) > 250 * w.dp) { addFx("bang", life = 0.8f); play("surprised", 0.6f); play("groom", w.anim("groom")) }
        else play("happy", 0.8f)
    }

    // ---------------- swat ----------------
    private fun swatMission() = sequence {
        addFx("bang", life = 1f)
        play("surprised", 0.35f)
        val tx = clampX(w.width / 2f)
        walkTo(tx, max(260f, abs(tx - x) / w.dp / 0.9f), "run")
        facing = 1; setAnim("swipe")
        wait(0.38f)
        events.swatHit()
        addFx("sparkle", 26 * w.dp, 0.8f)
        wait(0.35f)
        say(listOf("not today!", "nope. back to it", "caught you 🐾", "focus mode!").random(), 2.2f)
        play("happy", 1.2f)
    }

    // ---------------- bubbles ----------------
    private fun waitOnBubble(b: Bubble, secs: Float) = sequence {
        dropToGround()
        bubble = b; setAnim("idle")
        val end = t + secs
        while (bubble === b && t < end) yield(Unit)
        if (bubble === b) bubble = null
    }
    private fun ask(b: Bubble) = sequence {
        play("idle", 0.4f)
        answer = null; bubble = b
        val end = t + 30f
        while (answer == null && t < end) yield(Unit)
        if (answer == null) { bubble = null; events.bubbleAnswered(b, "timeout"); return@sequence }
        if (answer == "close") { setAnim("swipe"); wait(0.4f); play("happy", 1f) } else play("blink")
    }

    // ---------------- reminders ----------------
    private fun ringing() = sequence {
        while (activeAlarm != null || alarmQueue.isNotEmpty()) {
            val rem = activeAlarm ?: alarmQueue.removeFirst().also { activeAlarm = it }
            ringing = true; clockGround = null
            dropToGround()
            addFx("bang", life = 1f); play("surprised", 0.6f)
            answer = null
            val b = Bubble("alarm", "⏰ ${rem.text}", "time’s up!", listOf(BubbleButton("done", "done ✓"), BubbleButton("snooze", "snooze 5 min")), tag = rem.id)
            bubble = b
            var next = t + 4f; var flip = false
            while (answer == null) {
                if (bubble !== b) bubble = b
                if (t > next) { addFx("bang", life = 0.9f); flip = !flip; setAnim(if (flip) "surprised" else "idle"); next = t + 4f }
                yield(Unit)
            }
            activeAlarm = null; ringing = false
            play(if (answer == "done") "happy" else "yawn", 1f)
        }
    }
    private fun clockPlay() = sequence {
        if (countdown == null) return@sequence
        val side = if (Random.nextBoolean()) 1 else -1
        facing = side; clockGround = side * 26 * w.dp
        play("surprised", 0.4f)
        repeat(3) { i ->
            if (clockGround == null || countdown == null) return@repeat
            setAnim("pounce", 0); for (f in 0..3) { frame = f; wait(0.13f) }
            setAnim("pounce", 5); clockGround = side * (26 + 10 * (if (i % 2 == 1) -1 else 1)) * w.dp
            wait(0.35f); play("idle", 0.8f)
        }
        clockGround = null; play("happy", 0.8f)
    }

    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)

    companion object {
        /** filled from the sprite manifest by the service */
        val animSeconds = mutableMapOf<String, Float>()
    }
}
