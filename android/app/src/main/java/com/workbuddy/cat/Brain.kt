package com.workbuddy.cat

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/** Where the cat is right now: tiny in the status BAR, full size just under it (TOP, for events), or out PLAYing on the edges. */
enum class Stage { BAR, TOP, PLAY }

/** A tiny effect (heart, z, !, ?, sparkle, drop) that floats up from the cat and fades. */
data class Fx(val type: String, val born: Float, val dx: Float, val life: Float)

data class BubbleButton(val id: String, val label: String)
/** kinds: say, ask (nudge), alarm, menu, water, focus */
data class Bubble(
    val kind: String, val text: String, val sub: String = "",
    val buttons: List<BubbleButton> = emptyList(), val until: Float = Float.MAX_VALUE, val tag: String = "",
)

data class Ball(var x: Float, var y: Float, var vx: Float, var spin: Float = 0f)

fun glasses(n: Int) = if (n == 1) "1 glass" else "$n glasses"

/** What the service knows about the screen; the brain only thinks in these terms. */
interface World {
    val width: Int
    val zone: Zone
    val dp: Float
    val playSeconds: Int
    fun px(stage: Stage): Float          // screen px per art px at that stage
    fun groundBottom(): Float
    fun groundTop(): Float
    fun barGround(): Float
    fun lanes(): List<FloatArray>        // walkable [x0, x1] ranges inside the status bar, left to right
    val lowPower: Boolean
    val quiet: Boolean
}

/**
 * The cat's behaviour engine, phone edition. She normally lives tiny in the status bar; events (a feed to swat,
 * water, a reminder, focus mode) bring her down just under the bar, and a tap summons her out to play.
 * Behaviours are coroutine-style sequences that yield once per frame, so they read top to bottom and can be
 * interrupted at any moment.
 */
class Brain(private val w: World, private val events: Events) {
    interface Events {
        fun swatHit()                       // paw landed on a feed: press Back
        fun closeApp()                      // focus time ran out: go home
        fun bubbleAnswered(b: Bubble, answer: String)
    }

    var x = 0f; var y = 0f; var facing = 1
    var anim = "idle"; var frame: Int? = null; var seq = 0
    var t = 0f; var dt = 0f
    var fx = mutableListOf<Fx>(); var ball: Ball? = null; var bubble: Bubble? = null
    var tilt = 0f
    var stage = Stage.PLAY; private set
    var onTop = false                    // legacy zones: which edge (only matters for Zone.BOTH / TOP)
    var countdown: Float? = null; var clockGround: Float? = null; var ringing = false
    var focusLabel: String? = null; var focusRemain: Float? = null
    var waterToday = 0
    var mode = "life"                    // life | typing | mission | nudge | pet | menu | alarm | held | water | focus
    var lastType = -1e9f; private val typeTimes = ArrayDeque<Float>(); var keyUpAt = 0f
    private var playUntil = 0f
    private var airborne = false
    private var task: Iterator<Unit> = life().iterator()
    private var answer: String? = null
    private var hold: FloatArray? = null        // x, y, vx, vy while being carried
    private val alarmQueue = ArrayDeque<Reminder>()
    private var activeAlarm: Reminder? = null

    fun homeStage() = if (w.zone == Zone.STATUS) Stage.BAR else Stage.PLAY
    fun place() {
        stage = homeStage(); onTop = w.zone == Zone.TOP
        x = if (stage == Stage.BAR) laneMid() else w.width * 0.75f
        y = ground()
    }

    // ---------------- plumbing ----------------
    private val px get() = w.px(stage)
    private fun setAnim(name: String, f: Int? = null) { if (name != anim) { anim = name; seq++ }; frame = f }
    private fun addFx(type: String, dx: Float = 0f, life: Float = 1.5f) { fx.add(Fx(type, t, dx, life)) }
    private fun interrupt(seqGen: Sequence<Unit>, m: String) {
        task = seqGen.iterator(); mode = m; ball = null; tilt = 0f; clockGround = null; frame = null; airborne = false
        shuffling = false
    }
    private var shuffling = false
    /** A new icon took her spot in the status bar (or the bar got re-measured): shuffle over into free space. */
    private fun makeWay() {
        if (stage != Stage.BAR || mode != "life" || airborne || shuffling) return
        val b = bounds()
        if (x >= b[0] - 2 * w.dp && x <= b[1] + 2 * w.dp) return
        interrupt(sequence { walkTo(x, 60f); shuffling = false }, "life")
        shuffling = true
    }
    private fun setStage(s: Stage) { stage = s; frame = null; if (s == Stage.BAR) x = clampX(x) }
    private fun speed(baseDp: Float) = baseDp * w.dp * (if (w.lowPower) 0.6f else 1f) * (if (stage == Stage.BAR) 0.5f else 1f)
    private fun secs(name: String) = animSeconds[name] ?: 1f
    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)

    fun ground(): Float = when (stage) {
        Stage.BAR -> w.barGround()
        Stage.TOP -> w.groundTop()
        Stage.PLAY -> if (w.zone != Zone.STATUS && w.zone != Zone.BOTTOM && onTop) w.groundTop() else w.groundBottom()
    }
    private fun laneFor(v: Float): FloatArray {
        val ls = w.lanes()
        if (ls.isEmpty()) return floatArrayOf(13 * px, w.width - 13 * px)
        return ls.minBy { abs((it[0] + it[1]) / 2 - v) }
    }
    private fun laneMid(): Float { val l = w.lanes().firstOrNull() ?: return w.width * 0.3f; return (l[0] + l[1]) / 2 }
    /** Walkable range at the current stage: the status-bar lane she's in, or the whole screen (minus margins). */
    private fun bounds(): FloatArray = if (stage == Stage.BAR) laneFor(x) else floatArrayOf(16 * px, w.width - 16 * px)
    fun clampX(v: Float): Float { val b = bounds(); return v.coerceIn(b[0], b[1]) }
    private val onGround get() = abs(y - ground()) < 3 * w.dp

    fun tick(dtIn: Float) {
        dt = min(dtIn, 0.1f); t += dt
        fx.removeAll { t - it.born > it.life }
        stepBall()
        if (mode == "typing" && t - lastType > 5f) interrupt(afterTyping(), "life")
        if (mode == "typing" && keyUpAt > 0 && t > keyUpAt) { setAnim("typing", 0); keyUpAt = 0f }
        if (mode == "life" && (activeAlarm != null || alarmQueue.isNotEmpty())) interrupt(ringing(), "alarm")
        makeWay()
        // keep feet glued to the floor when it moves (keyboard, zone change, rotation), unless mid-jump
        if (!airborne && mode in GLUED) y += (ground() - y) * min(1f, dt * 10)
        try {
            if (!task.hasNext()) { task = life().iterator(); mode = "life" } else task.next()
        } catch (e: Throwable) {
            Log.e("WorkBuddy", "behaviour crashed", e)
            bubble = null; ball = null; clockGround = null; airborne = false
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
    /** A tap on the cat: in the status bar it summons her; anywhere else it's a pet. */
    fun tap() { if (stage == Stage.BAR) summon() else pet() }
    fun pet() {
        if (mode !in setOf("life", "typing", "pet")) return
        touchPlay(); interrupt(petted(), "pet")
    }
    private fun touchPlay() { if (stage == Stage.PLAY && homeStage() == Stage.BAR) playUntil = playEnd() }
    private fun playEnd() = if (w.playSeconds <= 0) Float.MAX_VALUE else t + w.playSeconds
    fun summon() {
        if (mode in setOf("mission", "alarm", "held", "water", "focus")) return
        playUntil = playEnd()
        interrupt(sequence { yieldAll(descendTo(Stage.PLAY)); play("happy", 0.8f) }, "life")
    }
    fun sendHome() { playUntil = 0f; if (stage != homeStage() && mode in setOf("life", "pet", "menu")) interrupt(goHome(), "life") }

    fun swat() { bubble = null; hold = null; interrupt(swatMission(), "mission") }
    fun focusAsk(pkg: String, label: String) { if (mode in setOf("life", "pet", "typing")) interrupt(focusAskSeq(pkg, label), "focus") }
    fun focusExpired(label: String) { bubble = null; hold = null; interrupt(focusClose(label), "mission") }
    fun waterAsk() { if (mode == "life") interrupt(waterSeq(), "water") }
    fun drinkNow() { if (mode in setOf("life", "pet", "menu")) interrupt(sequence { val ret = enterEvent(); play("drink"); addFx("drop", -3 * px, 1.2f); play("happy", 0.8f); leaveEvent(ret) }, "water") }
    fun nudge(appLabel: String, minutes: Int, pkg: String) {
        if (mode != "life") return
        val b = Bubble("ask", "$minutes min on $appLabel", "take a break?",
            listOf(BubbleButton("close", "close it"), BubbleButton("more", "5 more min"), BubbleButton("stop", "not today")), tag = pkg)
        interrupt(sequence { val ret = enterEvent(); yieldAll(ask(b)); leaveEvent(ret) }, "nudge")
    }
    fun openMenu(items: List<BubbleButton>) {
        if (mode in setOf("mission", "held", "alarm")) return
        interrupt(waitOnBubble(Bubble("menu", "", buttons = items), 12f), "menu")
    }
    fun say(text: String, secs: Float = 2.5f) { bubble = Bubble("say", text, until = t + secs) }
    fun playBall() { touchPlay(); interrupt(sequence { dropToGround(); yieldAll(ballPlay()) }, "life") }
    fun napNow() { interrupt(sequence { dropToGround(); yieldAll(nap(60f)) }, "life") }
    fun answer(id: String) {
        val b = bubble ?: return
        bubble = null; answer = id
        events.bubbleAnswered(b, id)
    }
    /** The app that a question was about went away: stop asking without an answer. */
    fun dismissAsk(kind: String) { if (bubble?.kind == kind) { bubble = null; answer = "cancel" } }
    fun alarm(r: Reminder) { alarmQueue.addLast(r); if (mode != "alarm" && mode != "held") interrupt(ringing(), "alarm") }

    fun grab(hx: Float, hy: Float) {
        if (stage == Stage.BAR) return
        bubble = null; hold = floatArrayOf(hx, hy, 0f, 0f); interrupt(held(), "held")
    }
    fun holdAt(hx: Float, hy: Float) {
        val h = hold ?: return
        val d = max(dt, 1 / 60f)
        h[2] = h[2] * 0.5f + (hx - h[0]) / d * 0.5f; h[3] = h[3] * 0.5f + (hy - h[1]) / d * 0.5f
        h[0] = hx; h[1] = hy
    }
    fun release() {
        val h = hold ?: return; hold = null
        // dragged away from an event spot: she's out playing now
        if (w.zone == Zone.STATUS && stage == Stage.TOP) { setStage(Stage.PLAY); playUntil = playEnd() }
        touchPlay()
        val cap = 2600 * w.dp / 2.6f
        interrupt(falling(h[2].coerceIn(-cap, cap), h[3].coerceIn(-cap, cap)), "life")
    }

    // ---------------- building blocks ----------------
    private suspend fun SequenceScope<Unit>.wait(secs: Float) {
        val end = t + secs
        while (t < end) { if ((bubble?.until ?: Float.MAX_VALUE) < t) bubble = null; yield(Unit) }
    }
    private suspend fun SequenceScope<Unit>.play(name: String, secs: Float? = null) { setAnim(name); wait(secs ?: secs(name)) }
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
        setAnim("pounce", 4); airborne = true
        var k = 0f
        while (k < 1f) {
            k = min(1f, k + dt / dur)
            x = x0 + (tx - x0) * k
            y = y0 + (ty - y0) * k - sin(PI * k).toFloat() * arcDp * w.dp
            yield(Unit)
        }
        airborne = false
        setAnim("pounce", 6); wait(0.15f)
    }
    private suspend fun SequenceScope<Unit>.dropToGround() { if (!onGround) jumpTo(x, ground(), 0.4f, 12f) }

    /** Events happen just under the status bar (or where she already is); returns whether to go back up after. */
    private suspend fun SequenceScope<Unit>.enterEvent(): Boolean {
        if (stage != Stage.BAR) { dropToGround(); return false }
        yieldAll(descendTo(if (w.zone == Zone.STATUS) Stage.TOP else Stage.PLAY))
        return true
    }
    private suspend fun SequenceScope<Unit>.leaveEvent(ret: Boolean) { if (ret && stage != Stage.BAR) yieldAll(goHome()) }

    private fun descendTo(s: Stage): Sequence<Unit> = sequence {
        if (stage == s) return@sequence
        val fromBar = stage == Stage.BAR
        setStage(s)
        val tx = clampX(x)
        if (fromBar && s == Stage.PLAY) { x = tx; yieldAll(falling(0f, 0f)) }
        else jumpTo(tx, ground(), 0.4f, 12f)
    }
    private fun goHome(): Sequence<Unit> = sequence {
        val home = homeStage()
        if (stage == home) return@sequence
        if (home == Stage.BAR) yieldAll(ascend())
        else { setStage(home); jumpTo(clampX(x), ground(), 0.4f, 12f) }
    }
    /** Back up into the status bar: run under the nearest lane, then one big leap. */
    private fun ascend(): Sequence<Unit> = sequence {
        val ls = w.lanes()
        val target = if (ls.isEmpty()) w.width * 0.3f else {
            val l = ls.minBy { abs((it[0] + it[1]) / 2 - x) }; x.coerceIn(l[0], l[1])
        }
        if (stage == Stage.PLAY && abs(target - x) > 40 * w.dp) walkTo(target, 120f, "run")
        play("pounce", 0.3f)
        setStage(Stage.BAR)
        jumpTo(target, ground(), 0.5f, 0f)
        play("happy", 0.5f)
    }

    // ---------------- life ----------------
    private fun life(): Sequence<Unit> = sequence {
        while (true) {
            val home = homeStage()
            if (stage == Stage.PLAY && home == Stage.BAR && t < playUntil) { yieldAll(playRound()); continue }
            if (stage != home) { yieldAll(goHome()); continue }
            yieldAll(if (stage == Stage.BAR) barRound() else playRound())
        }
    }
    private fun pickWeighted(choices: List<Pair<Float, () -> Sequence<Unit>>>): Sequence<Unit> {
        var r = Random.nextFloat() * choices.sumOf { it.first.toDouble() }.toFloat()
        return (choices.firstOrNull { r -= it.first; r < 0 } ?: choices.first()).second()
    }
    /** One behaviour in the status bar: small, slow, cosy. */
    private fun barRound(): Sequence<Unit> {
        val lp = w.lowPower; val q = w.quiet
        val lanes = w.lanes()
        return pickWeighted(listOf(
            30f to { sit() },
            (if (lp) 6f else 22f) to { barWander() },
            (if (lanes.size > 1 && !lp) 8f else 0f) to { hopLane() },
            8f to { sequence<Unit> { play("groom", secs("groom") * 2) } },
            5f to { sequence<Unit> { play("yawn") } },
            4f to { sequence<Unit> { play("stretch", secs("stretch") * 2) } },
            (if (lp) 25f else 8f) to { sequence<Unit> { play("loaf", rnd(15f, 40f)) } },
            (if (lp) 22f else 6f) to { nap(rnd(30f, 90f)) },
            (if (q || lp) 0f else 6f) to { ballPlay() },
            (if (q || lp) 0f else 3f) to { rollAround() },
            (if (q || lp) 0f else 3f) to { zoomies() },
            (if (countdown != null && !lp) 8f else 0f) to { clockPlay() },
            0.4f to { sequence<Unit> { play("dracula", rnd(8f, 14f)) } },
        ))
    }
    /** One behaviour out on the edges (summoned, or the old roaming zones). */
    private fun playRound(): Sequence<Unit> {
        val lp = w.lowPower; val q = w.quiet
        return pickWeighted(listOf(
            (if (lp) 20f else 28f) to { sit() },
            (if (lp) 8f else 24f) to { wander() },
            8f to { sequence<Unit> { play("groom", secs("groom") * 2) } },
            5f to { sequence<Unit> { play("yawn") } },
            4f to { sequence<Unit> { play("stretch", secs("stretch") * 2) } },
            (if (lp) 25f else 8f) to { sequence<Unit> { play("loaf", rnd(15f, 40f)) } },
            (if (lp) 22f else 5f) to { nap(rnd(30f, 90f)) },
            4f to { sequence<Unit> { play("box", rnd(12f, 30f)) } },
            (if (q || lp) 0f else 4f) to { zoomies() },
            (if (q || lp) 0f else 7f) to { ballPlay() },
            (if (q || lp) 0f else 3f) to { rollAround() },
            (if (w.zone == Zone.BOTH && !q) 5f else 0f) to { switchEdge() },
            (if (countdown != null && !lp) 12f else 0f) to { clockPlay() },
            0.5f to { sequence<Unit> { play("dracula", rnd(8f, 14f)) } },
        ))
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
    private fun barWander() = sequence {
        val l = laneFor(x)
        walkTo(rnd(l[0], l[1])); play("idle", rnd(1f, 3f))
    }
    /** Hop over the camera cutout into the other lane. */
    private fun hopLane() = sequence {
        val here = laneFor(x)
        val other = w.lanes().filter { it !== here }.randomOrNull() ?: return@sequence
        val right = other[0] > here[1]
        walkTo(if (right) here[1] else here[0], 60f)
        jumpTo(if (right) other[0] else other[1], ground(), 0.45f, 14f)
        play("happy", 0.5f)
    }
    private fun nap(secs: Float) = sequence {
        play("loaf", 3f); setAnim("sleep")
        val end = t + secs; var nextZ = t
        while (t < end) { if (t > nextZ) { addFx("z", life = 2.4f); nextZ = t + 1.4f }; yield(Unit) }
        play("stretch")
    }
    private fun zoomies() = sequence {
        val b = bounds(); val home = x
        val far = if (x > (b[0] + b[1]) / 2) b[0] else b[1]
        walkTo(far, 220f, "run"); play("surprised", 0.4f); walkTo(home, 220f, "run"); play("happy", 1.2f)
    }
    private fun rollAround() = sequence {
        val dir = if (Random.nextBoolean()) 1 else -1
        val tx = clampX(x + dir * 24 * px); facing = dir; setAnim("roll")
        while (abs(x - tx) > 1) { x += sign(tx - x) * min(abs(tx - x), speed(45f) * dt); yield(Unit) }
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
        val bd = bounds(); val m = 5 * px
        if (b.x < bd[0] - m || b.x > bd[1] + m) { b.vx = -b.vx * 0.6f; b.x = b.x.coerceIn(bd[0] - m, bd[1] + m) }
        b.y = ground()
    }
    private fun ballPlay() = sequence {
        val bd = bounds()
        facing = if (x > (bd[0] + bd[1]) / 2) -1 else 1
        ball = Ball(clampX(x + facing * 18 * px), y, 0f)
        addFx("sparkle", facing * 18 * px)
        play("surprised", 0.5f)
        repeat(Random.nextInt(3, 6)) {
            val b = ball ?: return@sequence
            facing = if (b.x > x) 1 else -1
            setAnim("pounce", 0)
            for (f in 0..3) { frame = f; wait(0.14f) }
            jumpTo(clampX(b.x - facing * 6 * px), ground(), 0.35f, 22f)
            b.vx = facing * rnd(170f, 280f) * w.dp / 2.6f * (if (stage == Stage.BAR) 0.5f else 1f)
            wait(0.2f)
            while (abs(b.vx) > 3) {
                val gap = b.x - x
                if (abs(gap) > 10 * px) { facing = sign(gap).toInt(); setAnim("run"); x = clampX(x + facing * speed(140f) * dt) } else setAnim("idle")
                yield(Unit)
            }
            walkTo(b.x - sign(b.x - x) * 8 * px, 60f)
        }
        play("happy", 1.2f); ball = null; play("loaf", rnd(5f, 10f))
    }

    // ---------------- typing ----------------
    private fun typing() = sequence { setAnim("typing", 0); while (true) yield(Unit) }
    private fun afterTyping() = sequence { play("stretch"); play("idle", 1.5f) }

    // ---------------- touch ----------------
    private fun petted() = sequence {
        setAnim("happy")
        repeat(3) { addFx("heart", rnd(-4f, 4f) * px, 1.4f); wait(0.3f) }
        wait(0.8f)
    }
    private fun held() = sequence {
        setAnim("dangle")
        while (true) {
            val h = hold ?: break
            x = h[0]; y = h[1] + 32 * px * 0.85f
            tilt = (-h[2] / (2500 * w.dp / 2.6f)).coerceIn(-0.35f, 0.35f)
            yield(Unit)
        }
    }
    private fun falling(vx0: Float, vy0: Float) = sequence {
        tilt = 0f
        if (w.zone == Zone.BOTH) onTop = y < w.groundBottom() / 3f
        if (w.zone == Zone.TOP) onTop = true
        var vx = vx0; var vy = vy0
        val startY = y
        setAnim("pounce", 4)
        if (stage == Stage.PLAY && w.zone != Zone.STATUS && w.zone != Zone.BOTTOM && onTop) {
            jumpTo(clampX(x + vx * 0.2f), ground(), 0.5f, 0f)
        } else {
            airborne = true
            val g = 2600 * w.dp / 2.6f
            val catH = 32 * px
            while (true) {
                vy += g * dt; vx *= 0.6f.pow(dt)
                x += vx * dt; y += vy * dt
                if (abs(vx) > 30) facing = sign(vx).toInt()
                val m = 20 * w.dp
                if (x < m || x > w.width - m) { vx = -vx * 0.5f; x = x.coerceIn(m, w.width - m) }
                if (y < catH) { y = catH; vy = abs(vy) * 0.3f }
                if (y >= ground()) { y = ground(); break }
                yield(Unit)
            }
            airborne = false
        }
        setAnim("pounce", 6); wait(0.25f)
        if (abs(y - startY) > 250 * w.dp) { addFx("bang", life = 0.8f); play("surprised", 0.6f); play("groom", secs("groom")) }
        else play("happy", 0.8f)
    }

    // ---------------- feeds & focus ----------------
    private fun swatMission() = sequence {
        val ret = enterEvent()
        addFx("bang", life = 1f)
        play("surprised", 0.35f)
        val tx = clampX(w.width / 2f)
        walkTo(tx, max(260f, abs(tx - x) / w.dp / 0.9f), "run")
        facing = 1; setAnim("swipe"); wait(0.38f)
        events.swatHit()
        addFx("sparkle", 8 * px, 0.8f); wait(0.35f)
        say(listOf("not today!", "nope. back to it", "caught you 🐾", "focus mode!").random(), 2.2f)
        play("happy", 1.2f); bubble = null
        leaveEvent(ret)
    }
    private fun focusClose(label: String) = sequence {
        val ret = enterEvent()
        addFx("bang", life = 1f); play("surprised", 0.35f)
        facing = 1; setAnim("swipe"); wait(0.38f)
        events.closeApp()
        addFx("sparkle", 8 * px, 0.8f); wait(0.35f)
        say("time’s up for $label 🐾", 2.4f); play("happy", 1.4f); bubble = null
        leaveEvent(ret)
    }
    private fun focusAskSeq(pkg: String, label: String) = sequence {
        val ret = enterEvent()
        addFx("question", life = 1.6f)
        answer = null
        val b = Bubble("focus", "how long on $label?", "focus mode ⏱", listOf(
            BubbleButton("5", "5 min"), BubbleButton("10", "10 min"), BubbleButton("15", "15 min"),
            BubbleButton("30", "30 min"), BubbleButton("custom", "custom…"), BubbleButton("close", "close it"),
        ), tag = pkg)
        bubble = b; setAnim("idle")
        val end = t + 25f
        while (answer == null && t < end) { if (bubble !== b) bubble = b; yield(Unit) }
        when (answer) {
            null -> { bubble = null; events.bubbleAnswered(b, "timeout"); play("blink") }
            "close" -> { setAnim("swipe"); wait(0.4f) }
            "cancel" -> {}
            else -> play("happy", 0.6f)
        }
        leaveEvent(ret)
    }

    // ---------------- water ----------------
    private fun waterSeq() = sequence {
        val ret = enterEvent()
        play("drink"); addFx("drop", -3 * px, 1.2f)
        answer = null
        val b = Bubble("water", "💧 had a glass of water?", if (waterToday > 0) "${glasses(waterToday)} today" else "stay hydrated 🐾",
            listOf(BubbleButton("yes", "yes, drank!"), BubbleButton("notyet", "not yet"), BubbleButton("later", "in 10 min")))
        bubble = b; setAnim("holdGlass")
        val end = t + 30f
        while (answer == null && t < end) { if (bubble !== b) bubble = b; yield(Unit) }
        if (answer == null) { bubble = null; events.bubbleAnswered(b, "timeout") }
        if (answer == "yes") { setAnim("happy"); repeat(3) { addFx("heart", rnd(-4f, 4f) * px, 1.4f); wait(0.3f) }; wait(0.6f) }
        else play("blink")
        if (bubble?.kind != "say") bubble = null   // keep the service's "yay!" line if it set one
        leaveEvent(ret)
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
        while (answer == null && t < end) { if (bubble !== b) bubble = b; yield(Unit) }
        if (answer == null) { bubble = null; events.bubbleAnswered(b, "timeout"); return@sequence }
        if (answer == "close") { setAnim("swipe"); wait(0.4f); play("happy", 1f) } else play("blink")
    }

    // ---------------- reminders ----------------
    private fun ringing() = sequence {
        val ret = enterEvent()
        while (activeAlarm != null || alarmQueue.isNotEmpty()) {
            val rem = activeAlarm ?: alarmQueue.removeFirst().also { activeAlarm = it }
            ringing = true; clockGround = null
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
        leaveEvent(ret)
    }
    private fun clockPlay() = sequence {
        if (countdown == null) return@sequence
        val side = if (Random.nextBoolean()) 1 else -1
        facing = side; clockGround = side * 10 * px
        play("surprised", 0.4f)
        repeat(3) { i ->
            if (clockGround == null || countdown == null) return@repeat
            setAnim("pounce", 0); for (f in 0..3) { frame = f; wait(0.13f) }
            setAnim("pounce", 5); clockGround = side * (10 + 4 * (if (i % 2 == 1) -1 else 1)) * px
            wait(0.35f); play("idle", 0.8f)
        }
        clockGround = null; play("happy", 0.8f)
    }

    companion object {
        /** filled from the sprite manifest by the service */
        val animSeconds = mutableMapOf<String, Float>()
        private val GLUED = setOf("life", "typing", "menu", "pet")
    }
}
