package com.workbuddy.cat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.sin

/**
 * Draws the cat (bottom-centre of its window), her effects, and the countdown pill (reminder clock or
 * focus timer) next to her head.
 * Touch: tap = summon / pet, long-press = menu, drag = pick up (fling to throw).
 * In the status bar only taps and long-presses count: a swipe there belongs to the system (notification shade).
 */
@SuppressLint("ViewConstructor")
class CatView(ctx: Context, private val brain: Brain, private val scale: () -> Float) : View(ctx) {
    interface Touch { fun tap(); fun longPress(); fun dragStart(rawX: Float, rawY: Float); fun dragMove(rawX: Float, rawY: Float); fun dragEnd() }
    var touch: Touch? = null
    var barMode = false          // in the status bar: no dragging
    var extraRight = 0           // px reserved right of the cat for the pill

    private val sp = Sprites.get(ctx)
    private val pixel = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val src = Rect(); private val dst = RectF()
    private var seqSeen = -1; private var animStart = 0L
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFFAF2") }
    private val pillLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2320"); style = Paint.Style.STROKE }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2320")
        typeface = ResourcesCompat.getFont(ctx, R.font.pixelify_sans)
    }

    /** One "dp" scaled with the cat's current size, so effects grow and shrink with her. */
    private fun rel(px: Float) = px / 2.5f

    override fun onDraw(c: Canvas) {
        val px = scale()
        val fw = sp.frame * px
        val cx = (width - extraRight) / 2f
        val bottom = height.toFloat()
        val a = sp[brain.anim]
        if (brain.seq != seqSeen) { seqSeen = brain.seq; animStart = SystemClock.uptimeMillis() }
        val i = brain.frame?.coerceIn(0, a.frames - 1) ?: if (a.fps == 0) 0 else {
            val n = ((SystemClock.uptimeMillis() - animStart) / 1000f * a.fps).toInt()
            if (a.loop) n % a.frames else minOf(n, a.frames - 1)
        }
        src.set(i * sp.frame, 0, (i + 1) * sp.frame, sp.frame)
        dst.set(cx - fw / 2, bottom - fw, cx + fw / 2, bottom)
        c.save()
        if (brain.tilt != 0f) c.rotate(Math.toDegrees(brain.tilt.toDouble()).toFloat(), cx, bottom - fw + 3 * px)
        if (brain.facing < 0 && brain.anim !in NO_FLIP) c.scale(-1f, 1f, cx, 0f)
        c.drawBitmap(a.sheet, src, dst, pixel)
        c.restore()

        drawFx(c, cx, bottom - fw + 6 * px, px)
        drawPill(c, cx, bottom - fw, bottom, px)
    }

    private fun drawFx(c: Canvas, cx: Float, headY: Float, px: Float) {
        val s = 9 * px * 0.75f
        val d = rel(px)
        for (f in brain.fx) {
            val age = brain.t - f.born
            var fx = cx + f.dx; var fy = headY; var alpha = 1f
            when (f.type) {
                "heart", "drop" -> { fy -= age * 30 * d; fx += sin(age * 6) * 3 * d; alpha = 1 - age / 1.4f }
                "z" -> { fx += (12 + age * 8) * d; fy -= age * 22 * d; alpha = 1 - age / 2.4f }
                "bang", "question" -> { fy -= (8 + abs(sin(age * 10)) * 4) * d; alpha = if (age < 0.8f) 1f else (1 - age) * 5 }
                "sparkle" -> { fy = height - 10 * px; alpha = if ((age * 8).toInt() % 2 == 1) 0.5f else 1f }
            }
            val idx = sp.fxIndex[f.type] ?: continue
            pixel.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            src.set(idx * 9, 0, idx * 9 + 9, 9)
            dst.set(fx - s / 2, fy - s / 2, fx + s / 2, fy + s / 2)
            c.drawBitmap(sp.fx, src, dst, pixel)
            pixel.alpha = 255
        }
    }

    private val density = resources.displayMetrics.density
    /** Pill sizes follow the cat when she's full size; in the status bar they stay readable (11 dp text). */
    private fun unit(px: Float) = if (barMode) density else rel(px)
    /** Free px to the right of the cat while she's in the status bar; the pill shrinks to what fits. */
    var barRoom = Int.MAX_VALUE
    private class PillPlan(val label: String?, val clock: Boolean, val focus: Boolean, val width: Float)
    private fun time(secs: Int) = if (secs >= 3600) "${secs / 3600}h${"%02d".format(secs / 60 % 60)}" else "${secs / 60}:${"%02d".format(secs % 60)}"
    private fun clockSize(px: Float) = 15 * px * (if (barMode) 0.85f else 0.7f)
    private fun pillWidth(label: String, px: Float): Float { pillText.textSize = 11 * unit(px); return pillText.measureText(label) + pillText.textSize * 1.2f }
    private fun fits(w: Float, px: Float) = !barMode || w + 6 * unit(px) <= barRoom
    /** What to show beside her: the richest version that fits ("Insta 4:32" → "4:32"; clock + time → time). */
    private fun plan(px: Float): PillPlan? {
        val focus = brain.focusRemain
        if (focus != null) {
            val t = time(focus.toInt())
            val full = "${brain.focusLabel ?: ""} $t".trim()
            val label = if (fits(pillWidth(full, px), px)) full else t
            return PillPlan(label, clock = false, focus = true, width = pillWidth(label, px))
        }
        val remain = brain.countdown
        if (remain == null && !brain.ringing) return null
        val cs = clockSize(px)
        if (remain == null || brain.ringing || brain.clockGround != null) return PillPlan(null, clock = true, focus = false, width = cs)
        val label = time(remain.toInt())
        val both = cs + 3 * unit(px) + pillWidth(label, px)
        return if (fits(both, px)) PillPlan(label, clock = true, focus = false, width = both)
        else PillPlan(label, clock = false, focus = false, width = pillWidth(label, px))
    }
    /** Room the pill (and the reminder clock) needs to the right of the cat, in px. The service sizes the window with it. */
    fun pillExtra(px: Float): Int = plan(px)?.let { (it.width + 6 * unit(px)).toInt() } ?: 0

    /** Focus countdown ("Insta 4:32"), or the reminder clock with its countdown: by her head, or beside her in the status bar. */
    private fun drawPill(c: Canvas, cx: Float, top: Float, bottom: Float, px: Float) {
        val plan = plan(px) ?: return
        val focus = brain.focusRemain
        val remain = brain.countdown
        val d = unit(px)
        val now = SystemClock.uptimeMillis()
        val bob = if (brain.ringing || barMode) 0f else sin(now / 420f) * 1.5f * d
        var anchorX = cx + 13 * px + 2 * d                                  // just right of the cat
        val anchorY = if (barMode) bottom - 13 * px else top + 3 * px + bob   // the bar's centre line, or head height
        if (plan.clock) {
            // reminder clock icon (she may have batted it onto the floor), then its countdown
            val s = clockSize(px)
            val f = if (brain.ringing) 2 + ((now / 90) % 2).toInt() else ((now / 500) % 2).toInt()
            val ground = brain.clockGround
            val clockX = if (ground != null) cx + ground - s / 2 else anchorX
            val clockY = if (ground != null) bottom - s else anchorY - s / 2
            src.set(f * 15, 0, f * 15 + 15, 15)
            dst.set(clockX, clockY, clockX + s, clockY + s)
            c.drawBitmap(sp.clock, src, dst, pixel)
            anchorX = clockX + s + 3 * d
        }
        val label = plan.label ?: return
        val pw = pillWidth(label, px)
        val tw = pillText.measureText(label); val ph = pillText.textSize * 1.5f
        val pyl = anchorY - ph / 2
        pillLine.strokeWidth = 1.6f * d
        pillFill.color = if (plan.focus) FOCUS_FILL else CREAM
        val r = RectF(anchorX, pyl, anchorX + pw, pyl + ph)
        c.drawRoundRect(r, ph / 2, ph / 2, pillFill); c.drawRoundRect(r, ph / 2, ph / 2, pillLine)
        val urgent = (focus != null && focus <= 60f) || (focus == null && remain != null && remain <= 60f)
        pillText.color = if (urgent) Color.parseColor("#C2405F") else Color.parseColor("#2A2320")
        c.drawText(label, anchorX + (pw - tw) / 2, pyl + ph / 2 + pillText.textSize * 0.35f, pillText)
    }

    // ---------------- touch ----------------
    private var downX = 0f; private var downY = 0f; private var dragging = false; private var longFired = false; private var moved = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPress = Runnable { if (!dragging && !moved) { longFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); touch?.longPress() } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false; longFired = false; moved = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val far = abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop
                if (far && !longFired) {
                    removeCallbacks(longPress); moved = true
                    if (!barMode && !dragging) { dragging = true; touch?.dragStart(e.rawX, e.rawY) }
                }
                if (dragging) touch?.dragMove(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                when {
                    dragging -> touch?.dragEnd()
                    !longFired && !moved && e.actionMasked == MotionEvent.ACTION_UP -> { performClick(); touch?.tap() }
                }
                dragging = false
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        val NO_FLIP = setOf("typing", "box", "dracula", "drink", "holdGlass")
        private val CREAM = Color.parseColor("#FFFAF2"); private val FOCUS_FILL = Color.parseColor("#FFE0DA")
    }
}

/** The yarn ball, in its own tiny window so it can roll away from the cat. */
@SuppressLint("ViewConstructor")
class BallView(ctx: Context, private val brain: Brain) : View(ctx) {
    private val sp = Sprites.get(ctx)
    private val pixel = Paint().apply { isFilterBitmap = false }
    private val dst = RectF()
    override fun onDraw(c: Canvas) {
        val b = brain.ball ?: return
        val q = Math.round(b.spin / (Math.PI / 2)).toInt() * 90f
        c.save(); c.rotate(q, width / 2f, height / 2f)
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(sp.ball, null, dst, pixel); c.restore()
    }
}
