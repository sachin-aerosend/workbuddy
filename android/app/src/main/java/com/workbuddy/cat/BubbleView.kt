package com.workbuddy.cat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/** The cat's speech / thought bubble, the long-press menu, nudges and alarms: cream card, ink outline. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class BubbleView(ctx: Context, private val onAnswer: (String) -> Unit) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val ink = Color.parseColor("#2A2320")
    private val cream = Color.parseColor("#FFFAF2")
    private val fur = Color.parseColor("#FDD5B5")
    private val nunito: Typeface? = ResourcesCompat.getFont(ctx, R.font.nunito)
    private val nunitoBold: Typeface? = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(nunito, 800, false) else Typeface.create(nunito, Typeface.BOLD)
    var shown: Bubble? = null; private set

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = (10 * d).toInt(); setPadding(p, (8 * d).toInt(), p, (9 * d).toInt())
        elevation = 6 * d
    }

    fun clearShown() { shown = null }

    fun show(b: Bubble) {
        if (b == shown) return
        shown = b
        removeAllViews()
        background = card(if (b.kind == "alarm") Color.parseColor("#C2405F") else ink, 16f)
        if (b.kind == "menu") {
            val p = (5 * d).toInt(); setPadding(p, p, p, p)
            b.buttons.forEach { btn -> addView(menuItem(btn)) }
            return
        }
        val p = (12 * d).toInt(); setPadding(p, (8 * d).toInt(), p, (9 * d).toInt())
        addView(text(b.text, 14f, true))
        if (b.sub.isNotEmpty()) addView(text(b.sub, 12f, false).apply { setTextColor(Color.parseColor("#7D6B62")) })
        if (b.buttons.isNotEmpty()) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
            b.buttons.forEachIndexed { i, btn -> row.addView(pill(btn, i == 0)) }
            addView(row, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (7 * d).toInt() })
        }
    }

    private fun text(s: String, sp: Float, bold: Boolean) = TextView(context).apply {
        text = s; textSize = sp; setTextColor(ink); gravity = Gravity.CENTER
        typeface = if (bold) nunitoBold else nunito
        maxWidth = (230 * d).toInt(); maxLines = 3
    }

    private fun pill(btn: BubbleButton, primary: Boolean) = TextView(context).apply {
        text = btn.label; textSize = 12.5f; setTextColor(ink)
        typeface = nunitoBold
        val h = (12 * d).toInt(); setPadding(h, (4 * d).toInt(), h, (4 * d).toInt())
        background = card(ink, 999f, if (primary) fur else Color.WHITE)
        setOnClickListener { onAnswer(btn.id) }
        layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt() }
    }

    private fun menuItem(btn: BubbleButton) = TextView(context).apply {
        text = btn.label; textSize = 14f
        setTextColor(if (btn.id.startsWith("cancel:")) Color.parseColor("#A8364A") else ink)
        typeface = nunitoBold
        val h = (12 * d).toInt(); setPadding(h, (9 * d).toInt(), h * 2, (9 * d).toInt())
        minWidth = (200 * d).toInt()
        background = GradientDrawable().apply { cornerRadius = 10 * d; setColor(Color.TRANSPARENT) }
        setOnClickListener { onAnswer(btn.id) }
        setOnTouchListener { v, e ->
            (v.background as GradientDrawable).setColor(if (e.action == android.view.MotionEvent.ACTION_DOWN) fur else Color.TRANSPARENT); false
        }
    }

    private fun card(stroke: Int, radiusDp: Float, fill: Int = cream) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radiusDp * d; setStroke((2 * d).toInt(), stroke)
    }
}
