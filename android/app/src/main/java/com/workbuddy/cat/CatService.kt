package com.workbuddy.cat

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Keeps the cat alive: a foreground service that owns three tiny overlay windows (cat, bubble, ball),
 * runs the brain at 30 fps (12 in battery saver), keeps the water and focus timers, and reacts to the
 * watcher (feeds to swat, apps opened, typing, the notification shade).
 *
 * The windows are hosted through the accessibility service's WindowManager when it's on: those overlays
 * draw above the status bar, are trusted (never block taps on sensitive screens) and need no extra
 * permission. Without it we fall back to ordinary app overlays on the bottom edge.
 */
class CatService : Service(), Brain.Events, World, SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager                // ours: screen metrics + fallback host
    private var host: WindowManager? = null                 // owns the overlay windows right now
    private var hostIsA11y = false
    private lateinit var brain: Brain
    private lateinit var catView: CatView
    private lateinit var bubbleView: BubbleView
    private lateinit var ballView: BallView
    private var catLp: LP? = null; private var bubbleLp: LP? = null; private var ballLp: LP? = null
    private var catAttached = false; private var bubbleAttached = false; private var ballAttached = false
    private var lastFrame = 0L; private var lastSlow = 0L; private var lastSave = 0L; private var lastTimerSave = 0L
    private var fullscreen = false; private var shadeOpen = false; private var shy = false; private var recording = false
    private var running = false; private var fastBits = 0
    private var lowPowerNow = false; private var interactive = true; private var locked = false
    private var fgPkg = ""; private var asking: String? = null
    private val timers = HashMap<String, FocusTimer>()
    private var waterAccum = 0L
    private val main = Handler(Looper.getMainLooper())
    private val km by lazy { getSystemService(KeyguardManager::class.java) }
    private val pm by lazy { getSystemService(PowerManager::class.java) }

    // ---------------- World ----------------
    private val metrics get() = resources.displayMetrics
    override val dp get() = metrics.density
    override val width: Int get() = screenW
    /** The status bar needs the accessibility host; without it she lives on the bottom edge instead. */
    override val zone get() = if (prefs.zone == Zone.STATUS && !hostIsA11y) Zone.BOTTOM else prefs.zone
    override val playSeconds get() = prefs.playSeconds
    override val lowPower get() = lowPowerNow
    override val quiet get() = prefs.quietMode
    private var screenW = 0; private var screenH = 0; private var statusH = 0; private var navH = 0
    private var cutout: Rect? = null
    private fun barPx(): Float = minOf(prefs.size.barPx, (statusH * 0.85f / 26f).toInt()).coerceAtLeast(1).toFloat()
    override fun px(stage: Stage): Float = if (stage == Stage.BAR) barPx() else prefs.size.artDp * dp
    override fun groundBottom(): Float {
        val base = screenH - navH - 2 * dp
        val ime = Bus.imeTop
        return if (ime != null && ime < base) ime - 2 * dp else base
    }
    override fun groundTop(): Float = statusH + 32 * px(Stage.TOP) + 2 * dp
    override fun barGround(): Float = ((barLayout?.centerY ?: (statusH * 0.5f)) + 13 * barPx()).coerceAtMost(statusH.toFloat())
    override fun lanes(): List<FloatArray> = lanesNow

    /**
     * Walkable stretches of the status bar: the free space between what's really in it (clock, notification
     * icons, Wi-Fi, battery — measured by the watcher) and the camera cutout. Without a measurement we guess:
     * clock in the first 20%, icons in the last 30%.
     */
    private var barLayout: BarLayout? = null
    private var lanesNow: List<FloatArray> = emptyList()
    private var pillReserve = 0          // px kept free right of her for the countdown pill
    private fun computeLanes() {
        val half = 13 * barPx(); val pad = 4 * dp; val edge = 12 * dp
        val blocks = ArrayList<FloatArray>()
        val measured = barLayout
        if (measured != null) measured.blocks.forEach { blocks += floatArrayOf(it[0] - pad, it[1] + pad) }
        else { blocks += floatArrayOf(0f, screenW * 0.20f); blocks += floatArrayOf(screenW * 0.70f, screenW.toFloat()) }
        cutout?.let { blocks += floatArrayOf(it.left - pad, it.right + pad) }
        blocks.sortBy { it[0] }
        val gaps = ArrayList<FloatArray>(); var cursor = edge
        for (b in blocks) { if (b[0] > cursor) gaps += floatArrayOf(cursor, b[0]); cursor = max(cursor, b[1]) }
        if (screenW - edge > cursor) gaps += floatArrayOf(cursor, screenW - edge)
        val widest = gaps.maxByOrNull { it[1] - it[0] }
        barRoom = ((widest?.let { it[1] - it[0] } ?: 0f) - 2 * half).toInt().coerceAtLeast(0)
        val fit = gaps.mapNotNull { g ->
            val lo = g[0] + half; val hi = g[1] - half - pillReserve
            if (hi - lo >= -8 * dp) floatArrayOf(lo, max(lo, hi)) else null
        }
        // a crowded bar: squeeze into the widest gap rather than sit on the clock (hugging its left side when
        // there's a countdown pill to fit in beside her)
        lanesNow = fit.ifEmpty {
            widest?.let { val c = if (pillReserve > 0) it[0] + half else (it[0] + it[1]) / 2; listOf(floatArrayOf(c, c)) } ?: emptyList()
        }
    }
    private var barRoom = 0              // px free beside her in the widest gap: what a pill may use
    private val bg by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private var measuring = false
    /** Reading another window's layout is slow-ish, so it happens off the main thread. */
    private fun measureBar() {
        val watcher = Bus.watcher
        if (watcher == null) { if (barLayout != null) { barLayout = null; computeLanes() }; return }
        if (measuring) return
        measuring = true
        bg.execute {
            val found = runCatching { watcher.statusBarLayout() }.getOrNull()
            main.post { measuring = false; if (found != null) barLayout = found; computeLanes() }   // keep the last good one
        }
    }

    private fun readScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            screenW = m.bounds.width(); screenH = m.bounds.height()
            val ins = m.windowInsets
            val bars = ins.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            statusH = bars.top; navH = bars.bottom
            cutout = ins.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }
        } else {
            @Suppress("DEPRECATION") val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            screenW = dm.widthPixels; screenH = dm.heightPixels
            @Suppress("DiscouragedApi")
            fun dim(n: String) = resources.getIdentifier(n, "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
            statusH = dim("status_bar_height"); navH = dim("navigation_bar_height"); cutout = null
        }
        computeLanes()
    }

    // ---------------- lifecycle ----------------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        wm = getSystemService(WindowManager::class.java)
        Sprites.get(this).anims.values.forEach { Brain.animSeconds[it.name] = it.seconds }
        readScreen()
        hostIsA11y = Bus.watcher != null
        brain = Brain(this, this)
        brain.place()
        catView = CatView(this, brain) { px(brain.stage) }.apply {
            touch = object : CatView.Touch {
                override fun tap() = brain.tap()
                override fun longPress() = openMenu()
                override fun dragStart(rawX: Float, rawY: Float) = brain.grab(rawX, rawY)
                override fun dragMove(rawX: Float, rawY: Float) = brain.holdAt(rawX, rawY)
                override fun dragEnd() = brain.release()
            }
        }
        bubbleView = BubbleView(this) { brain.answer(it) }
        ballView = BallView(this, brain)
        timers.clear(); prefs.focusTimers.forEach { timers[it.pkg] = it }
        waterAccum = prefs.waterAccumMs
        brain.waterToday = prefs.waterToday
        createChannels()
        startInForeground()
        prefs.listen(this)
        Bus.cat = this
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE_HOUR -> { prefs.pausedUntil = System.currentTimeMillis() + 3_600_000; updateNotification() }
            ACTION_BRING_BACK -> { prefs.pausedUntil = 0; updateNotification() }
            ACTION_FOCUS -> addReminder(getString(R.string.focus_done), 25)
            ACTION_ADD_REMINDER -> addReminder(intent.getStringExtra("text").orEmpty(), intent.getIntExtra("minutes", 10))
            ACTION_WATER -> logWater()
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        saveTimers(); prefs.waterAccumMs = waterAccum
        prefs.unlisten(this)
        if (Bus.cat === this) Bus.cat = null
        if (Build.VERSION.SDK_INT >= 35) recordingCallback?.let { runCatching { wm.removeScreenRecordingCallback(it) } }
        detachAll()
        bg.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig); readScreen(); brain.x = brain.clampX(brain.x)
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            "zone" -> { brain.onTop = prefs.zone == Zone.TOP; brain.sendHome() }
            "size" -> computeLanes()
            "paused_until", "cat_enabled", "water_today", "focus_enabled" -> updateNotification()
        }
        if (key == "focus_enabled" && !prefs.focusEnabled) {
            timers.clear(); saveTimers(); asking = null
            brain.dismissAsk("focus"); brain.focusRemain = null; brain.focusLabel = null
        }
        if (key == "water_enabled" && !prefs.waterEnabled) { waterAccum = 0; prefs.waterAccumMs = 0 }
    }

    // ---------------- hosting ----------------
    private fun ensureHost() {
        if (host != null) return
        val a = Bus.watcher
        host = a?.getSystemService(WindowManager::class.java) ?: wm
        val was = hostIsA11y
        hostIsA11y = a != null
        canOverlay = hostIsA11y || Settings.canDrawOverlays(this)
        if (was != hostIsA11y) brain.sendHome()
    }
    /** The accessibility service came or went: move the windows to the right host. */
    fun rehost() { main.post { detachAll(); host = null; catLp = null; bubbleLp = null; ballLp = null; lastPos.clear() } }
    private fun detachAll() {
        val h = host ?: return
        if (catAttached) runCatching { h.removeView(catView) }
        if (bubbleAttached) runCatching { h.removeView(bubbleView) }
        if (ballAttached) runCatching { h.removeView(ballView) }
        catAttached = false; bubbleAttached = false; ballAttached = false
    }

    // ---------------- frame loop ----------------
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            val now = SystemClock.uptimeMillis()
            if (now - lastFrame < (if (lowPowerNow) 80 else 30)) return
            val dt = if (lastFrame == 0L) 0.033f else (now - lastFrame) / 1000f
            lastFrame = now
            // (capped: frames stop while the screen is off, and that gap must not count as screen time)
            if (now - lastSlow >= 500) { val d = if (lastSlow == 0L) 500L else (now - lastSlow).coerceAtMost(1500L); lastSlow = now; slowTick(d) }
            brain.tick(dt)
            render()
        }
    }
    private fun slowTick(dtMs: Long) {
        fastBits = if (BuildConfig.DEBUG) Settings.Global.getInt(contentResolver, "wb_fast", 0) else 0
        lowPowerNow = prefs.batterySaver || (prefs.autoBatterySaver && pm.isPowerSaveMode)
        interactive = pm.isInteractive; locked = km.isKeyguardLocked
        canOverlay = hostIsA11y || Settings.canDrawOverlays(this)
        slowTicks++
        if (interactive && !locked && prefs.zone == Zone.STATUS && slowTicks % 4 == 0) measureBar()
        if (interactive && !locked && slowTicks % 10 == 0) Bus.watcher?.syncForeground()   // safety net for a missed app switch
        Bus.watcher?.fgPkg?.takeIf { it.isNotEmpty() && it != fgPkg }?.let { onForeground(it) }
        // Full-screen apps hide the status bar. Our own window sits above the bar, so ask for the display's
        // insets instead of the window's.
        if (Build.VERSION.SDK_INT >= 30) fullscreen = runCatching { !wm.currentWindowMetrics.windowInsets.isVisible(WindowInsets.Type.statusBars()) }.getOrDefault(false)
        checkReminders(); tickWater(dtMs); tickFocus(dtMs)
        debugTick()
    }
    private var slowTicks = 0; private var canOverlay = false
    private var lastDbg = ""; private var lastDump = -1
    /** Debug builds: state changes go to logcat (tag WorkBuddy); `settings put global wb_dump N` dumps the windows. */
    private fun debugTick() {
        if (!BuildConfig.DEBUG) return
        val s = "visible=${catVisible()} shade=$shadeOpen locked=$locked interactive=$interactive fullscreen=$fullscreen " +
            "recording=$recording shy=$shy a11y=$hostIsA11y zone=$zone stage=${brain.stage} mode=${brain.mode} fg=$fgPkg " +
            "bar=${statusH}px measured=${barLayout != null} cutout=$cutout lanes=${lanes().joinToString { "${it[0].toInt()}-${it[1].toInt()}" }}"
        if (s != lastDbg) { lastDbg = s; Log.i("WorkBuddy", s) }
        val dump = Settings.Global.getInt(contentResolver, "wb_dump", 0)
        if (dump != lastDump) { if (lastDump >= 0) Bus.watcher?.dumpWindows(); lastDump = dump }
    }
    /** Debug builds: `adb shell settings put global wb_fast N` makes every "minute" one second (1 = focus & reminders, 2 = water, 3 = both). */
    private fun minutesMs(m: Int) = if (fastBits and 1 != 0) m * 1000L else m * 60_000L
    private fun waterMs(m: Int) = if (fastBits and 2 != 0) m * 1000L else m * 60_000L

    fun setShy(on: Boolean) { shy = on }
    fun setShadeOpen(on: Boolean) { shadeOpen = on }

    private fun catVisible(): Boolean {
        if (!prefs.catEnabled || System.currentTimeMillis() < prefs.pausedUntil) return false
        if (shadeOpen || locked || !interactive) return false
        if (prefs.hideFullscreen && fullscreen && brain.mode != "alarm") return false
        if (prefs.hideFromRecording && recording) return false
        if (!hostIsA11y && shy) return false
        return canOverlay
    }

    private fun render() {
        ensureHost()
        if (!canOverlay) { detachAll(); return }     // no accessibility host and no overlay permission: nothing to draw on
        val show = catVisible()
        val stage = brain.stage
        val p = px(stage)
        catView.barMode = stage == Stage.BAR; catView.barRoom = barRoom
        val extraRight = catView.pillExtra(p)                 // countdown pill / reminder clock beside her
        val decor = extraRight > 0 || brain.fx.isNotEmpty() || brain.tilt != 0f
        val coreW = (32 * p * (if (decor) 1.3f else 1.1f)).roundToInt()
        val catW = coreW + extraRight
        val catH = (32 * p * (if (decor) 1.65f else 1.05f)).roundToInt()
        catView.extraRight = extraRight
        // in the status bar the pill needs free space too (in steps, so a changing digit doesn't shuffle her around)
        val step = (8 * dp).toInt()
        val reserve = if (stage == Stage.BAR && extraRight > 0) (extraRight / step + 1) * step else 0
        if (reserve != pillReserve) { pillReserve = reserve; computeLanes() }
        // The cat window stays attached even while hidden (alpha 0, untouchable) so it keeps receiving
        // window insets and can notice when a full-screen app ends.
        val lp = catLp ?: overlayParams(catW, catH, touchable = true).also { catLp = it }
        lp.width = catW; lp.height = catH
        lp.x = (brain.x - coreW / 2f).roundToInt(); lp.y = (brain.y - catH).roundToInt()
        lp.alpha = if (show) 1f else 0f
        lp.flags = if (show) lp.flags and LP.FLAG_NOT_TOUCHABLE.inv() else lp.flags or LP.FLAG_NOT_TOUCHABLE
        attach(catView, lp, catAttached) { catAttached = it; if (it) watchRecording() }
        if (show) catView.invalidate()

        val b = brain.bubble
        if (show && b != null) {
            bubbleView.show(b)
            val lpB = bubbleLp ?: overlayParams(LP.WRAP_CONTENT, LP.WRAP_CONTENT, touchable = true).also { bubbleLp = it }
            val bw = bubbleView.width.takeIf { it > 0 } ?: (220 * dp).toInt()
            val bh = bubbleView.height.takeIf { it > 0 } ?: (90 * dp).toInt()
            val gap = 8 * dp
            val catTop = brain.y - 32 * p
            val y = when (stage) {
                Stage.BAR -> statusH + gap
                Stage.TOP -> brain.y + gap
                Stage.PLAY -> if (catTop - bh - gap > statusH) catTop - bh - gap else brain.y + gap
            }
            val minX = 4 * dp; val maxX = max(minX, screenW - bw - 4 * dp)
            lpB.x = (brain.x - bw / 2f).coerceIn(minX, maxX).roundToInt()
            lpB.y = y.roundToInt()
            attach(bubbleView, lpB, bubbleAttached) { bubbleAttached = it }
        } else detach(bubbleView, bubbleAttached) { bubbleAttached = it; bubbleView.clearShown() }

        val ball = brain.ball
        if (show && ball != null) {
            val s = (9 * p).roundToInt()
            val lpO = ballLp ?: overlayParams(s, s, touchable = false).also { ballLp = it }
            lpO.width = s; lpO.height = s; lpO.x = (ball.x - s / 2f).roundToInt(); lpO.y = (ball.y - s).roundToInt()
            attach(ballView, lpO, ballAttached) { ballAttached = it }
            ballView.invalidate()
        } else detach(ballView, ballAttached) { ballAttached = it }
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = LP(
        w, h, if (hostIsA11y) LP.TYPE_ACCESSIBILITY_OVERLAY else LP.TYPE_APPLICATION_OVERLAY,
        LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else LP.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (!touchable) alpha = 0.8f // lets touches pass through on Android 12+ (app-overlay host)
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    private var recordingCallback: java.util.function.Consumer<Int>? = null
    /** Android 15+ tells us when our windows are being recorded/shared; the cat steps away until it stops. */
    private fun watchRecording() {
        if (Build.VERSION.SDK_INT < 35 || recordingCallback != null) return
        val cb = java.util.function.Consumer<Int> { state -> recording = state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE }
        runCatching {
            val initial = wm.addScreenRecordingCallback(mainExecutor, cb)
            recording = initial == WindowManager.SCREEN_RECORDING_STATE_VISIBLE
            recordingCallback = cb
        }
    }
    private val lastPos = HashMap<View, String>()
    private fun attach(v: View, lp: LP, attached: Boolean, set: (Boolean) -> Unit) {
        val h = host ?: return
        val key = "${lp.x},${lp.y},${lp.width},${lp.height},${lp.flags},${lp.alpha}"
        if (!attached) {
            runCatching { h.addView(v, lp) }
                .onSuccess { set(true); lastPos[v] = key }
                .onFailure { Log.w("WorkBuddy", "addView failed: ${it.message}") }
        } else if (lastPos[v] != key) {
            runCatching { h.updateViewLayout(v, lp) }
                .onSuccess { lastPos[v] = key }
                .onFailure { set(false); lastPos.remove(v) }
        }
    }
    private fun detach(v: View, attached: Boolean, set: (Boolean) -> Unit) {
        if (attached) { runCatching { host?.removeView(v) }; set(false); lastPos.remove(v) }
    }

    // ---------------- menu ----------------
    private fun openMenu() {
        val items = mutableListOf<BubbleButton>()
        val bar = brain.stage == Stage.BAR
        if (bar) items += BubbleButton("summon", "🐾  Come down and play")
        else if (brain.homeStage() == Stage.BAR) items += BubbleButton("home", "🔼  Go back up")
        if (prefs.remindersEnabled) {
            items += BubbleButton("remind", "⏰  Set a reminder…")
            items += BubbleButton("focus25", "🍅  Focus for 25 min")
            prefs.reminders.sortedBy { it.due }.forEach {
                val left = ((it.due - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                items += BubbleButton("cancel:${it.id}", "✕  ${left / 60}:${"%02d".format(left % 60)} · ${it.text.take(22)}")
            }
        }
        items += BubbleButton("focusmode", if (prefs.focusEnabled) "🎯  Focus mode: on" else "🎯  Focus mode: off")
        if (prefs.waterEnabled) items += BubbleButton("water", "💧  I drank water" + (if (prefs.waterToday > 0) " · ${prefs.waterToday} today" else ""))
        if (!bar && !prefs.quietMode) items += BubbleButton("ball", "🧶  Play with the ball")
        items += BubbleButton("nap", "😴  Nap time")
        items += BubbleButton("away", "💤  Hide for 1 hour")
        items += BubbleButton("settings", "⚙️  Settings")
        brain.openMenu(items)
    }

    // ---------------- Brain.Events ----------------
    override fun swatHit() { Bus.watcher?.back() }
    override fun closeApp() { Bus.watcher?.home() }

    override fun bubbleAnswered(b: Bubble, answer: String) {
        when (b.kind) {
            "menu" -> when {
                answer == "summon" -> brain.summon()
                answer == "home" -> brain.sendHome()
                answer == "remind" -> startActivity(Intent(this, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                answer == "focus25" -> addReminder(getString(R.string.focus_done), 25)
                answer.startsWith("cancel:") -> { prefs.reminders = prefs.reminders.filter { it.id != answer.removePrefix("cancel:") }; brain.say("okay, cancelled", 2f) }
                answer == "focusmode" -> { prefs.focusEnabled = !prefs.focusEnabled; brain.say(if (prefs.focusEnabled) "focus mode on 🎯" else "focus mode off", 2f) }
                answer == "water" -> logWater()
                answer == "ball" -> brain.playBall()
                answer == "nap" -> brain.napNow()
                answer == "away" -> prefs.pausedUntil = System.currentTimeMillis() + 3_600_000
                answer == "settings" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "ask" -> when (answer) {
                "close" -> Bus.watcher?.home()
                "more" -> Bus.watcher?.snooze(b.tag, 5)
                "stop" -> Bus.watcher?.snoozeToday(b.tag)
                "timeout" -> Bus.watcher?.snooze(b.tag, 10)
            }
            "alarm" -> if (answer == "snooze") addReminder(b.text.removePrefix("⏰ "), 5, quiet = true)
            "water" -> onWaterAnswer(answer)
            "focus" -> onFocusAnswer(b.tag, answer)
        }
    }

    // ---------------- from the watcher ----------------
    fun onFeed(label: String) {
        if (catVisible()) brain.swat() else { Bus.watcher?.back(); toastLike("🐾 $label closed") }
    }
    fun onNudge(label: String, minutes: Int, pkg: String) { if (catVisible()) brain.nudge(label, minutes, pkg) }
    fun onTyped() { if (prefs.typingBuddy && catVisible()) brain.onType() }

    /** An app came to the front. Focus mode decides whether to ask "how long?", resume a timer, or keep it shut. */
    fun onForeground(pkg: String) {
        if (pkg == fgPkg) return
        fgPkg = pkg
        if (asking != null && asking != pkg) { asking = null; brain.dismissAsk("focus") }
        brain.focusRemain = null; brain.focusLabel = null
        val rule = prefs.rule(pkg)
        if (!prefs.focusEnabled || rule == null || rule.mode != FocusMode.WHOLE) return
        val now = System.currentTimeMillis()
        val until = prefs.focusBlocked[pkg] ?: 0L
        if (until > now) {
            Bus.watcher?.home()
            val left = until - now
            val wait = if (fastBits and 1 != 0) "${left / 1000 + 1} s" else "${left / 60_000L + 1} min"
            brain.say("nope — ${rule.label} is closed for another $wait 🐾", 3f)
            if (!catVisible()) toastLike("🐾 ${rule.label} is closed for another $wait")
            return
        }
        val t = timers[pkg]
        if (t != null) {
            // an allowance from a while ago doesn't carry over: ask again
            if (now - t.lastUsedAt < minutesMs(30)) { brain.focusRemain = t.left / 1000f; brain.focusLabel = short(rule.label); return }
            timers.remove(pkg); saveTimers()
        }
        askHoldUntil = 0
        maybeAskFocus()
    }
    private fun short(label: String) = label.trim().take(9)

    private var askHoldUntil = 0L
    /** A limited app is open without an allowance: ask "how long?" as soon as the cat is free (she may be mid water break). */
    private fun maybeAskFocus() {
        if (!prefs.focusEnabled || !interactive || locked) return
        val pkg = fgPkg
        val rule = prefs.rule(pkg) ?: return
        if (rule.mode != FocusMode.WHOLE || timers.containsKey(pkg) || brain.mode == "focus") return
        val now = System.currentTimeMillis()
        if (now < askHoldUntil || (prefs.focusBlocked[pkg] ?: 0L) > now) return
        asking = pkg
        brain.focusAsk(pkg, rule.label)
    }

    // ---------------- focus timers ----------------
    private fun tickFocus(dtMs: Long) {
        if (!prefs.focusEnabled || !interactive) return
        val pkg = fgPkg
        val t = timers[pkg]
        if (t == null) { maybeAskFocus(); return }
        val now = System.currentTimeMillis()
        val left = t.left - dtMs
        if (left <= 0) {
            timers.remove(pkg); saveTimers()
            val until = now + minutesMs(prefs.focusCooldownMinutes)
            prefs.focusBlocked = prefs.focusBlocked.filterValues { it > now } + (pkg to until)
            brain.focusRemain = null; brain.focusLabel = null
            brain.focusExpired(prefs.rule(pkg)?.label ?: pkg)
        } else {
            timers[pkg] = t.copy(left = left, lastUsedAt = now)
            brain.focusRemain = left / 1000f; brain.focusLabel = short(prefs.rule(pkg)?.label ?: pkg)
            if (now - lastTimerSave > 5000) { saveTimers(); lastTimerSave = now }
        }
    }
    private fun saveTimers() { prefs.focusTimers = timers.values.toList() }
    fun startFocus(pkg: String, minutes: Int) {
        val ms = minutesMs(minutes)
        timers[pkg] = FocusTimer(pkg, ms, ms, System.currentTimeMillis()); saveTimers()
        val label = prefs.rule(pkg)?.label ?: pkg
        if (fgPkg == pkg) { brain.focusRemain = ms / 1000f; brain.focusLabel = short(label) }
        brain.say("okay, $minutes min on $label ⏱", 2.5f)
    }
    private fun onFocusAnswer(pkg: String, answer: String) {
        asking = null
        when (answer) {
            "5", "10", "15", "30" -> startFocus(pkg, answer.toInt())
            "custom" -> {
                askHoldUntil = System.currentTimeMillis() + 90_000      // time to type the minutes
                startActivity(
                    Intent(this, ReminderActivity::class.java).putExtra("focusPkg", pkg)
                        .putExtra("focusLabel", prefs.rule(pkg)?.label ?: pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            "close" -> Bus.watcher?.home()
            "timeout" -> {   // no answer (or the cat is hidden): a short default allowance
                startFocus(pkg, 5); brain.say("okay, 5 minutes then ⏱", 2.5f)
                if (!catVisible()) toastLike("⏱ Focus mode: 5 min on ${prefs.rule(pkg)?.label ?: pkg}")
            }
        }
    }

    // ---------------- water ----------------
    private fun tickWater(dtMs: Long) {
        if (!prefs.waterEnabled || !interactive) return
        waterAccum += dtMs
        val now = SystemClock.uptimeMillis()
        if (now - lastSave > 30_000) { prefs.waterAccumMs = waterAccum; lastSave = now }
        val interval = waterMs(prefs.waterEveryMinutes)
        if (waterAccum < interval) return
        if (brain.mode != "life" || brain.t - brain.lastType < 8f) return   // wait for a calm moment
        if (!catVisible()) { notifyWater(); waterAccum = 0; prefs.waterAccumMs = 0; return }
        waterAccum = (interval - waterMs(15)).coerceAtLeast(0)           // if ignored, she asks again in ~15 min
        prefs.waterAccumMs = waterAccum
        brain.waterToday = prefs.waterToday
        brain.waterAsk()
    }
    private fun onWaterAnswer(answer: String) {
        val interval = waterMs(prefs.waterEveryMinutes)
        when (answer) {
            "yes" -> { logGlass(); waterAccum = 0; brain.say("yay! ${glasses(prefs.waterToday)} today 💧", 2.5f) }
            "later" -> waterAccum = (interval - waterMs(10)).coerceAtLeast(0)
            else -> {   // "not yet", or no answer: try again in a bit, but don't nag forever
                prefs.waterMisses = prefs.waterMisses + 1
                if (prefs.waterMisses >= 3) { prefs.waterMisses = 0; waterAccum = 0 }
                else waterAccum = (interval - waterMs(15)).coerceAtLeast(0)
            }
        }
        prefs.waterAccumMs = waterAccum
    }
    private fun logGlass() {
        prefs.waterToday = prefs.waterToday + 1; prefs.waterTotal = prefs.waterTotal + 1; prefs.waterMisses = 0
        brain.waterToday = prefs.waterToday
    }
    /** "I drank a glass" from the menu, the notification or the settings screen. */
    fun logWater() {
        logGlass(); waterAccum = 0; prefs.waterAccumMs = 0
        if (catVisible()) brain.drinkNow()
        brain.say("yay! ${glasses(prefs.waterToday)} today 💧", 2.5f)
    }

    // ---------------- reminders ----------------
    fun addReminder(text: String, minutes: Int, quiet: Boolean = false) {
        val r = Reminder("r${System.currentTimeMillis().toString(36)}", text.ifBlank { getString(R.string.times_up) }, System.currentTimeMillis() + minutesMs(minutes))
        prefs.reminders = prefs.reminders + r
        if (!quiet) brain.say(if (minutes >= 60) "got it! ⏰ ${minutes / 60} h" else "got it! ⏰ $minutes min", 2.5f)
    }
    private fun checkReminders() {
        val now = System.currentTimeMillis()
        val all = prefs.reminders
        val due = all.filter { it.due <= now }
        if (due.isNotEmpty()) {
            prefs.reminders = all - due.toSet()
            due.forEach { r ->
                if (prefs.pausedUntil > now) prefs.pausedUntil = 0
                brain.alarm(r); notifyReminder(r)
            }
        }
        val next = prefs.reminders.minOfOrNull { it.due }
        brain.countdown = next?.let { ((it - now) / 1000f).coerceAtLeast(0f) }
    }

    // ---------------- notifications ----------------
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CAT, getString(R.string.ch_cat), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, getString(R.string.ch_remind), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WATER, getString(R.string.ch_water), NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, CatService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
    private fun ongoing(): Notification {
        val paused = prefs.pausedUntil > System.currentTimeMillis()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = "💧 ${prefs.waterToday} today · focus mode ${if (prefs.focusEnabled) "on" else "off"}"
        return NotificationCompat.Builder(this, CH_CAT)
            .setSmallIcon(R.drawable.ic_stat_cat)
            .setContentTitle(if (paused) "Your cat is away until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(prefs.pausedUntil))}" else "WorkBuddy is keeping you company 🐾")
            .setContentText(text)
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, if (paused) "Bring back" else "Hide 1 h", pi(if (paused) ACTION_BRING_BACK else ACTION_HIDE_HOUR, 1))
            .addAction(0, "I drank 💧", pi(ACTION_WATER, 2))
            .addAction(0, "Focus 25", pi(ACTION_FOCUS, 3))
            .build()
    }
    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, ongoing(), type)
    }
    private fun updateNotification() { runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, ongoing()) } }
    private fun notifyReminder(r: Reminder) {
        val n = NotificationCompat.Builder(this, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_cat).setContentTitle("⏰ ${r.text}").setContentText("Time’s up! – your WorkBuddy")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(r.id.hashCode(), n) }
    }
    private fun notifyWater() {
        val n = NotificationCompat.Builder(this, CH_WATER)
            .setSmallIcon(R.drawable.ic_stat_cat).setContentTitle(getString(R.string.water_notif_title)).setContentText(getString(R.string.water_notif_text))
            .setAutoCancel(true).addAction(0, "I drank 💧", pi(ACTION_WATER, 5))
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(WATER_NOTIF_ID, n) }
    }
    private fun toastLike(text: String) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show() }

    companion object {
        const val CH_CAT = "cat"; const val CH_REMIND = "reminders"; const val CH_WATER = "water"
        const val NOTIF_ID = 1; const val WATER_NOTIF_ID = 2
        const val ACTION_HIDE_HOUR = "hide_hour"; const val ACTION_BRING_BACK = "bring_back"; const val ACTION_FOCUS = "focus"
        const val ACTION_ADD_REMINDER = "add_reminder"; const val ACTION_WATER = "water"; const val ACTION_STOP = "stop"
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, CatService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CatService::class.java)) }
    }
}
