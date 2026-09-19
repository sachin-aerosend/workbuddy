package com.workbuddy.cat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.sin

/**
 * Draws the cat (bottom-centre of the view), its effects, and the reminder clock + countdown above its head.
 * Touch: tap = pet, long-press = menu, drag = pick up (fling to throw).
 */
@SuppressLint("ViewConstructor")
class CatView(ctx: Context, private val brain: Brain, private val scale: () -> Float) : View(ctx) {
    interface Touch { fun tap(); fun longPress(); fun dragStart(rawX: Float, rawY: Float); fun dragMove(rawX: Float, rawY: Float); fun dragEnd() }
    var touch: Touch? = null

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

    /** One "dp" scaled with the cat size (so effects grow/shrink with a Small/Large cat). */
    private fun rel(px: Float) = resources.displayMetrics.density * px / (2.5f * resources.displayMetrics.density)

    /** px of the cat's feet inside this view (bottom edge). */
    fun feetY() = height.toFloat()

    override fun onDraw(c: Canvas) {
        val px = scale()
        val fw = sp.frame * px
        val cx = width / 2f
        val bottom = feetY()
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
        drawClock(c, cx, bottom - fw, bottom, px)
    }

    private fun drawFx(c: Canvas, cx: Float, headY: Float, px: Float) {
        val s = 9 * px * 0.75f
        for (f in brain.fx) {
            val age = brain.t - f.born
            var fx = cx + f.dx; var fy = headY; var alpha = 1f
            val d = rel(px)
            when (f.type) {
                "heart" -> { fy -= age * 30 * d; fx += sin(age * 6) * 3 * d; alpha = 1 - age / 1.4f }
                "z" -> { fx += (12 + age * 8) * d; fy -= age * 22 * d; alpha = 1 - age / 2.4f }
                "bang" -> { fy -= (8 + abs(sin(age * 10)) * 4) * d; alpha = if (age < 0.8f) 1f else (1 - age) * 5 }
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

    private fun drawClock(c: Canvas, cx: Float, top: Float, bottom: Float, px: Float) {
        val remain = brain.countdown
        if (remain == null && !brain.ringing) return
        val s = 15 * px * 0.7f
        val now = SystemClock.uptimeMillis()
        val f = if (brain.ringing) 2 + ((now / 90) % 2).toInt() else ((now / 500) % 2).toInt()
        val d = rel(px)
        val bob = if (brain.ringing) 0f else sin(now / 420f) * 1.5f * d
        val ground = brain.clockGround
        val hasPill = remain != null && !brain.ringing
        val clockX = if (ground != null) cx + ground - s / 2 else cx - s / 2 - (if (hasPill) 18 * d else 0f)
        val clockY = if (ground != null) bottom - s else top + 3 * px - s + bob
        src.set(f * 15, 0, f * 15 + 15, 15)
        dst.set(clockX, clockY, clockX + s, clockY + s)
        c.drawBitmap(sp.clock, src, dst, pixel)
        if (!hasPill) return
        val secs = remain!!.toInt()
        val label = if (secs >= 3600) "${secs / 3600}h${"%02d".format(secs / 60 % 60)}" else "${secs / 60}:${"%02d".format(secs % 60)}"
        pillText.textSize = 11 * d
        val tw = pillText.measureText(label); val ph = pillText.textSize * 1.5f; val pw = tw + ph * 0.8f
        var pxl = clockX + s + 3 * d; var pyl = clockY + (s - ph) / 2
        if (ground != null) { pxl = clockX + s / 2 - pw / 2; pyl = clockY - ph - 3 * d }
        pillLine.strokeWidth = 1.6f * d
        val r = RectF(pxl, pyl, pxl + pw, pyl + ph)
        c.drawRoundRect(r, ph / 2, ph / 2, pillFill); c.drawRoundRect(r, ph / 2, ph / 2, pillLine)
        pillText.color = if (secs <= 60) Color.parseColor("#C2405F") else Color.parseColor("#2A2320")
        c.drawText(label, pxl + (pw - tw) / 2, pyl + ph / 2 + pillText.textSize * 0.35f, pillText)
    }

    // ---------------- touch ----------------
    private var downX = 0f; private var downY = 0f; private var dragging = false; private var longFired = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPress = Runnable { if (!dragging) { longFired = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); touch?.longPress() } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false; longFired = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !longFired && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                    dragging = true; removeCallbacks(longPress); touch?.dragStart(e.rawX, e.rawY)
                }
                if (dragging) touch?.dragMove(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                when {
                    dragging -> touch?.dragEnd()
                    !longFired && e.actionMasked == MotionEvent.ACTION_UP -> { performClick(); touch?.tap() }
                }
                dragging = false
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    companion object { val NO_FLIP = setOf("typing", "box", "dracula") }
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
