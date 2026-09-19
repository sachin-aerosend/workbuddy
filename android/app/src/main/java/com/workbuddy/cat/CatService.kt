package com.workbuddy.cat

import android.annotation.SuppressLint
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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps the cat alive: a foreground service that owns three tiny overlay windows (cat, bubble, ball),
 * runs the brain at 30 fps (12 in battery saver), handles reminders, and reacts to the watcher
 * (Reels/Shorts swats, app-time nudges, typing).
 */
class CatService : Service(), Brain.Events, World, SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private lateinit var brain: Brain
    private lateinit var catView: CatView
    private lateinit var bubbleView: BubbleView
    private lateinit var ballView: BallView
    private var catLp: LP? = null; private var bubbleLp: LP? = null; private var ballLp: LP? = null
    private var catAttached = false; private var bubbleAttached = false; private var ballAttached = false
    private var lastFrame = 0L; private var lastReminderCheck = 0L
    private var fullscreen = false
    private var running = false

    // ---------------- World ----------------
    private val metrics get() = resources.displayMetrics
    override val dp get() = metrics.density
    override val width: Int get() = screenW
    override val zone get() = prefs.zone
    override val px: Float get() = (if (prefs.zone == Zone.TOP) minOf(prefs.size.artDp, 2f) else prefs.size.artDp) * dp
    override val lowPower get() = prefs.batterySaver || (prefs.autoBatterySaver && getSystemService(PowerManager::class.java).isPowerSaveMode)
    override val quiet get() = prefs.quietMode
    private var screenW = 0; private var screenH = 0; private var statusH = 0; private var navH = 0
    override fun groundBottom(): Float {
        val base = (screenH - navH - 2 * dp)
        val ime = Bus.imeTop
        return if (ime != null && ime < base) ime - 2 * dp else base
    }
    override fun groundTop(): Float = statusH + 32 * px + 2 * dp

    private fun readScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            screenW = m.bounds.width(); screenH = m.bounds.height()
            val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            statusH = ins.top; navH = ins.bottom
        } else {
            @Suppress("DEPRECATION") val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            screenW = dm.widthPixels; screenH = dm.heightPixels
            fun dim(n: String) = resources.getIdentifier(n, "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
            statusH = dim("status_bar_height"); navH = dim("navigation_bar_height")
        }
    }

    // ---------------- lifecycle ----------------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        wm = getSystemService(WindowManager::class.java)
        Sprites.get(this).anims.values.forEach { Brain.animSeconds[it.name] = it.seconds }
        readScreen()
        brain = Brain(this, this)
        brain.place(screenW * 0.75f)
        catView = CatView(this, brain) { px }.apply {
            touch = object : CatView.Touch {
                override fun tap() = brain.pet()
                override fun longPress() = openMenu()
                override fun dragStart(rawX: Float, rawY: Float) = brain.grab(rawX, rawY)
                override fun dragMove(rawX: Float, rawY: Float) = brain.holdAt(rawX, rawY)
                override fun dragEnd() = brain.release()
            }
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) fullscreen = !insets.isVisible(WindowInsets.Type.statusBars())
                v.onApplyWindowInsets(insets)
            }
        }
        bubbleView = BubbleView(this) { brain.answer(it) }
        ballView = BallView(this, brain)
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
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        prefs.unlisten(this)
        if (Bus.cat === this) Bus.cat = null
        if (Build.VERSION.SDK_INT >= 35) recordingCallback?.let { runCatching { wm.removeScreenRecordingCallback(it) } }
        listOf(catView to catAttached, bubbleView to bubbleAttached, ballView to ballAttached).forEach { (v, on) -> if (on) runCatching { wm.removeView(v) } }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig); readScreen(); brain.x = brain.clampX(brain.x)
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            "hide_from_recording" -> listOfNotNull(catLp, bubbleLp, ballLp).forEach { applySecure(it) }.also { relayoutAll() }
            "zone" -> { brain.onTop = prefs.zone == Zone.TOP; brain.y = brain.ground() }
            "paused_until", "cat_enabled" -> updateNotification()
        }
    }

    // ---------------- frame loop ----------------
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            val now = SystemClock.uptimeMillis()
            val minGap = if (lowPower) 80 else 30
            if (now - lastFrame < minGap) return
            val dt = if (lastFrame == 0L) 0.033f else (now - lastFrame) / 1000f
            lastFrame = now
            if (now - lastReminderCheck > 500) { lastReminderCheck = now; checkReminders() }
            brain.tick(dt)
            render()
        }
    }

    private var shy = false
    fun setShy(on: Boolean) { shy = on }

    private fun catVisible() = prefs.catEnabled && System.currentTimeMillis() > prefs.pausedUntil && !shy &&
        !(prefs.hideFullscreen && fullscreen && brain.mode != "alarm") &&
        !(prefs.hideFromRecording && recording) && Settings.canDrawOverlays(this)

    private fun render() {
        val show = catVisible()
        // cat
        // The window catches taps, so keep it as small as possible: just the cat, plus headroom only
        // while something is drawn above her (clock, hearts, z's).
        val decor = brain.countdown != null || brain.ringing || brain.fx.isNotEmpty() || brain.tilt != 0f
        val catW = (32 * px * (if (brain.countdown != null || brain.ringing) 1.9f else if (decor) 1.3f else 1.1f)).roundToInt()
        val catH = (32 * px * (if (decor) 1.65f else 1.05f)).roundToInt()
        if (show) {
            val lp = catLp ?: overlayParams(catW, catH, touchable = true).also { catLp = it }
            lp.width = catW; lp.height = catH
            lp.x = (brain.x - catW / 2f).roundToInt(); lp.y = (brain.y - catH).roundToInt()
            attach(catView, lp, catAttached) { catAttached = it; if (it) watchRecording() }
            catView.invalidate()
        } else detach(catView, catAttached) { catAttached = it }

        // bubble
        val b = brain.bubble
        if (show && b != null) {
            bubbleView.show(b)
            val lp = bubbleLp ?: overlayParams(LP.WRAP_CONTENT, LP.WRAP_CONTENT, touchable = true).also { bubbleLp = it }
            val bw = bubbleView.width.takeIf { it > 0 } ?: (220 * dp).toInt()
            val bh = bubbleView.height.takeIf { it > 0 } ?: (90 * dp).toInt()
            val catTop = brain.y - 32 * px
            val gap = 8 * dp
            val above = catTop - bh - gap > statusH
            lp.x = (brain.x - bw / 2f).coerceIn(4 * dp, screenW - bw - 4 * dp).roundToInt()
            lp.y = (if (above) catTop - bh - gap else brain.y + gap).roundToInt()
            attach(bubbleView, lp, bubbleAttached) { bubbleAttached = it }
        } else detach(bubbleView, bubbleAttached) { bubbleAttached = it; bubbleView.clearShown() }

        // ball
        val ball = brain.ball
        if (show && ball != null) {
            val s = (9 * px).roundToInt()
            val lp = ballLp ?: overlayParams(s, s, touchable = false).also { ballLp = it }
            lp.width = s; lp.height = s; lp.x = (ball.x - s / 2f).roundToInt(); lp.y = (ball.y - s).roundToInt()
            attach(ballView, lp, ballAttached) { ballAttached = it }
            ballView.invalidate()
        } else detach(ballView, ballAttached) { ballAttached = it }
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = LP(
        w, h, LP.TYPE_APPLICATION_OVERLAY,
        LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else LP.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (!touchable) alpha = 0.8f // lets touches pass through on Android 12+
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        applySecure(this)
    }
    /**
     * Android's FLAG_SECURE would show the cat as a black box in recordings, so we don't use it.
     * Instead, on Android 15+ the system tells us when our windows are being recorded/shared and the
     * cat simply steps away until it stops (see watchRecording).
     */
    private fun applySecure(lp: LP) { lp.flags = lp.flags and LP.FLAG_SECURE.inv() }
    private var recording = false
    private var recordingCallback: java.util.function.Consumer<Int>? = null
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
        val key = "${lp.x},${lp.y},${lp.width},${lp.height},${lp.flags}"
        if (!attached) { runCatching { wm.addView(v, lp); set(true); lastPos[v] = key } }
        else if (lastPos[v] != key) { runCatching { wm.updateViewLayout(v, lp) }; lastPos[v] = key }
    }
    private fun detach(v: View, attached: Boolean, set: (Boolean) -> Unit) {
        if (attached) { runCatching { wm.removeView(v) }; set(false); lastPos.remove(v) }
    }
    private fun relayoutAll() { lastPos.clear() }

    // ---------------- menu ----------------
    private fun openMenu() {
        val items = mutableListOf<BubbleButton>()
        if (prefs.remindersEnabled) {
            items += BubbleButton("remind", "⏰  Set a reminder…")
            items += BubbleButton("focus", "🍅  Focus for 25 min")
            prefs.reminders.sortedBy { it.due }.forEach {
                val left = ((it.due - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                items += BubbleButton("cancel:${it.id}", "✕  ${left / 60}:${"%02d".format(left % 60)} · ${it.text.take(22)}")
            }
        }
        if (!prefs.quietMode) items += BubbleButton("ball", "🧶  Play with the ball")
        items += BubbleButton("nap", "😴  Nap time")
        items += if (prefs.hideFromRecording) BubbleButton("rec", "📺  Show on screen recording") else BubbleButton("rec", "🙈  Hide from screen recording")
        items += BubbleButton("away", "💤  Hide for 1 hour")
        items += BubbleButton("settings", "⚙️  Settings")
        brain.openMenu(items)
    }

    // ---------------- Brain.Events ----------------
    override fun swatHit() { Bus.watcher?.back() }

    override fun bubbleAnswered(b: Bubble, answer: String) {
        when (b.kind) {
            "menu" -> when {
                answer == "remind" -> startActivity(Intent(this, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                answer == "focus" -> addReminder(getString(R.string.focus_done), 25)
                answer.startsWith("cancel:") -> { prefs.reminders = prefs.reminders.filter { it.id != answer.removePrefix("cancel:") }; brain.say("okay, cancelled", 2f) }
                answer == "ball" -> brain.playBall()
                answer == "nap" -> brain.napNow()
                answer == "rec" -> { prefs.hideFromRecording = !prefs.hideFromRecording; brain.say(if (prefs.hideFromRecording) "hidden from recordings 🙈" else "visible in recordings 📺", 2.5f) }
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
        }
    }

    // ---------------- from the watcher ----------------
    fun onFeed(label: String) {
        if (catVisible()) brain.swat() else { Bus.watcher?.back(); toastLike("🐾 $label closed") }
    }
    fun onNudge(label: String, minutes: Int, pkg: String) { if (catVisible()) brain.nudge(label, minutes, pkg) }
    fun onTyped() {
        if (prefs.typingBuddy && catVisible()) brain.onType()
    }

    // ---------------- reminders ----------------
    fun addReminder(text: String, minutes: Int, quiet: Boolean = false) {
        val r = Reminder("r${System.currentTimeMillis().toString(36)}", text.ifBlank { getString(R.string.times_up) }, System.currentTimeMillis() + minutes * 60_000L)
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
    }
    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, CatService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
    private fun ongoing(): Notification {
        val paused = prefs.pausedUntil > System.currentTimeMillis()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_CAT)
            .setSmallIcon(R.drawable.ic_stat_cat)
            .setContentTitle(if (paused) "Your cat is away until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(prefs.pausedUntil))}" else "WorkBuddy is keeping you company 🐾")
            .setContentText("Swatting Reels & Shorts, keeping your reminders.")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, if (paused) "Bring back" else "Hide 1 h", pi(if (paused) ACTION_BRING_BACK else ACTION_HIDE_HOUR, 1))
            .addAction(0, "Focus 25", pi(ACTION_FOCUS, 2))
            .build()
    }
    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, ongoing(), type)
    }
    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, ongoing()) }
    private fun notifyReminder(r: Reminder) {
        val n = NotificationCompat.Builder(this, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_cat).setContentTitle("⏰ ${r.text}").setContentText("Time’s up! – your WorkBuddy")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(r.id.hashCode(), n) }
    }
    private fun toastLike(text: String) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show() }

    companion object {
        const val CH_CAT = "cat"; const val CH_REMIND = "reminders"; const val NOTIF_ID = 1
        const val ACTION_HIDE_HOUR = "hide_hour"; const val ACTION_BRING_BACK = "bring_back"; const val ACTION_FOCUS = "focus"
        const val ACTION_ADD_REMINDER = "add_reminder"; const val ACTION_STOP = "stop"
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, CatService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CatService::class.java)) }
    }
}
