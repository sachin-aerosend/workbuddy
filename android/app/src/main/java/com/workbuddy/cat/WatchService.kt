package com.workbuddy.cat

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Calendar

/** The status bar as measured: occupied [left, right] stretches in px, and the y its content is centred on. */
class BarLayout(val blocks: List<IntArray>, val centerY: Float?)

/**
 * The watcher (an accessibility service). It only looks for a few things:
 *  1. which app is in front (Focus mode: "how long?", timers, cooldowns)
 *  2. a short-video feed on screen, in its app or in a browser (Reels/Shorts swatting)
 *  3. how long you've been in a distracting app (gentle "take a break?" nudges)
 *  4. *that* you're typing (never what) and where the keyboard is, so the cat can type along
 *  5. whether the notification shade is open, so the cat steps aside
 * It also hosts the cat's overlay windows, which lets her live above the status bar.
 * Nothing is stored or sent anywhere.
 */
class WatchService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var lastScan = 0L
    private var lastSwat = 0L
    var fgPkg = ""; private set
    private var fgSince = 0L                 // when the current distracting-app session started
    private val nextNudgeAt = HashMap<String, Long>()
    private val snoozedUntil = HashMap<String, Long>()
    private var shade = false

    // (kept in a field: SharedPreferences only holds listeners weakly)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "focus_enabled" || key == "focus_rules" || key == "typing_buddy") applyEventMask()
    }
    /**
     * Battery: only ask Android for the chatty events while something needs them. "Content changed" (every
     * scroll in every app) is only for spotting Reels/Shorts; text events only for the typing buddy.
     */
    private fun applyEventMask() {
        val info = serviceInfo ?: return
        var want = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (prefs.focusEnabled && prefs.focusRules.any { it.mode == FocusMode.FEED }) want = want or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (prefs.typingBuddy) want = want or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        if (info.eventTypes != want) { info.eventTypes = want; serviceInfo = info }
    }

    override fun onServiceConnected() {
        prefs = Prefs.get(this)
        Bus.watcher = this
        applyEventMask(); prefs.listen(prefListener)
        main.postDelayed(nudgeTick, 30_000)
        main.postDelayed({ syncForeground() }, 800)
        val cat = Bus.cat
        if (cat != null) cat.rehost() else runCatching { CatService.start(this) }
    }

    /**
     * Which app is in front right now? App switches normally arrive as events, but one can slip by (we just
     * connected, the phone restarted us…), so the cat service double-checks every few seconds.
     */
    fun syncForeground() {
        val pkg = runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root?.packageName?.toString()
                ?: rootInActiveWindow?.packageName?.toString()
        }.getOrNull() ?: return
        onForeground(pkg)
    }
    override fun onDestroy() {
        if (Bus.watcher === this) Bus.watcher = null
        if (::prefs.isInitialized) prefs.unlisten(prefListener)
        main.removeCallbacksAndMessages(null)
        Bus.cat?.rehost()
        super.onDestroy()
    }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Typing buddy: we only react to *that* a text field changed (or its cursor moved while the
                // keyboard is open). Never read it; skip password fields.
                val typing = e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || Bus.imeTop != null || imeVisible()
                if (typing && !e.isPassword && pkg != packageName) Bus.cat?.onTyped()
                return
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> { updateKeyboard(); updateShade(null); return }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { updateShade(pkg); onForeground(pkg); updateKeyboard() }
        }
        maybeScan(pkg)
    }

    // ---------------- foreground app ----------------
    private fun onForeground(pkg: String) {
        if (pkg == "com.android.systemui" || pkg == fgPkg || isKeyboard(pkg)) return
        Bus.cat?.setShy(pkg in SHY)
        if (pkg == packageName) return
        fgPkg = pkg; fgSince = System.currentTimeMillis()
        Bus.cat?.onForeground(pkg)
    }
    private var imePkg: String? = null; private var imeCheckedAt = 0L
    private fun isKeyboard(pkg: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - imeCheckedAt > 60_000) {
            imeCheckedAt = now
            imePkg = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        }
        return pkg == imePkg
    }

    // ---------------- notification shade ----------------
    /**
     * The shade (or quick settings, the power menu…) is open when a tall system window is up and has the
     * user's attention. The collapsed shade isn't in the window list at all.
     */
    private fun updateShade(statePkg: String?) {
        val dm = resources.displayMetrics
        val open = runCatching {
            windows.any { w ->
                w.type == AccessibilityWindowInfo.TYPE_SYSTEM && (w.isActive || w.isFocused) &&
                    Rect().also(w::getBoundsInScreen).height() > dm.heightPixels * 0.45f
            }
        }.getOrDefault(false)
        if (open != shade && BuildConfig.DEBUG) Log.i("WorkBuddy", "shade=$open (event from $statePkg)")
        shade = open
        Bus.cat?.setShadeOpen(shade)
    }

    /** Debug builds: log every accessibility window (see CatService.debugTick). */
    fun dumpWindows() {
        val list = runCatching { windows }.getOrNull() ?: return
        Log.i("WorkBuddy", "---- ${list.size} windows ----")
        for (w in list) {
            val r = Rect().also(w::getBoundsInScreen)
            Log.i("WorkBuddy", "win type=${w.type} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} bounds=$r title=${w.title} pkg=${runCatching { w.root?.packageName }.getOrNull()}")
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && r.top <= 0 && r.height() < resources.displayMetrics.heightPixels * 0.12f) dumpNodes(w.root, 0)
        }
        val bar = statusBarLayout()
        Log.i("WorkBuddy", "bar blocks=${bar?.blocks?.joinToString { "${it[0]}-${it[1]}" }} centreY=${bar?.centerY}")
    }
    private fun dumpNodes(n: AccessibilityNodeInfo?, depth: Int) {
        if (n == null || depth > 12) return
        val r = Rect().also(n::getBoundsInScreen)
        Log.i("WorkBuddy", "${"  ".repeat(depth)}node ${n.className?.toString()?.substringAfterLast('.')} id=${n.viewIdResourceName?.substringAfter('/')} vis=${n.isVisibleToUser} $r text=${n.text ?: n.contentDescription}")
        for (i in 0 until n.childCount) dumpNodes(n.getChild(i), depth + 1)
    }

    /**
     * What's already in the status bar: [left, right] px of every visible item (clock, notification icons,
     * Wi-Fi, battery…) and the height its content is centred on, read from its real layout so the cat only
     * walks in the free space, on any phone. Null when the bar can't be seen (then the cat falls back to a guess).
     * Safe to call off the main thread.
     */
    fun statusBarLayout(): BarLayout? {
        val dm = resources.displayMetrics
        val bar = runCatching {
            windows.firstOrNull { w ->
                if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@firstOrNull false
                val r = Rect().also(w::getBoundsInScreen)
                r.top <= 0 && r.width() >= dm.widthPixels * 0.9f && r.height() < dm.heightPixels * 0.12f
            }
        }.getOrNull() ?: return null
        val barH = Rect().also(bar::getBoundsInScreen).height()
        val root = runCatching { bar.root }.getOrNull() ?: return null
        val blocks = ArrayList<IntArray>(); val centres = ArrayList<Float>()
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        val r = Rect(); var seen = 0
        while (queue.isNotEmpty() && seen < 300) {
            val n = queue.removeFirst(); seen++
            if (!n.isVisibleToUser) continue
            if (n.childCount == 0) {
                n.getBoundsInScreen(r)
                if (r.width() <= 0 || r.height() <= 0 || r.width() >= dm.widthPixels * 0.6f) continue
                blocks.add(intArrayOf(r.left, r.right))
                if (r.height() < barH * 0.8f) centres.add(r.exactCenterY())   // full-height hit areas say nothing about the centre line
            } else for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        if (blocks.isEmpty()) return null
        return BarLayout(blocks, centres.sorted().getOrNull(centres.size / 2))
    }

    // ---------------- short-video feeds ----------------
    private fun maybeScan(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < 350 || now - lastSwat < 2500 || !prefs.focusEnabled) return
        val feed = Feed.forPackage(pkg)
        val watched = (feed != null && prefs.feedWatched(feed)) ||
            (prefs.swatInBrowser && pkg in BROWSERS && Feed.entries.any { prefs.feedWatched(it) })
        if (!watched) return
        lastScan = now
        // Check every app window of that package (dialogs can sit on top of the page, e.g. Chrome prompts).
        val roots = runCatching { windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root } }
            .getOrDefault(emptyList()).ifEmpty { listOfNotNull(rootInActiveWindow) }
        val hit = roots.filter { it.packageName?.toString() == pkg }.firstNotNullOfOrNull { detect(pkg, it) } ?: return
        lastSwat = now
        val cat = Bus.cat
        if (cat != null) cat.onFeed(hit) else back()
    }

    /** Returns a label for the feed on screen, or null. */
    private fun detect(pkg: String, root: AccessibilityNodeInfo): String? {
        if (pkg in BROWSERS) {
            val url = browserUrl(root)?.lowercase()?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.")?.removePrefix("m.") ?: return null
            return Feed.entries.firstOrNull { f -> prefs.feedWatched(f) && f.urlPrefixes.any { url.startsWith(it) } }?.label
        }
        val feed = Feed.forPackage(pkg) ?: return null
        val ids = when (feed) {
            Feed.TIKTOK -> return feed.label
            Feed.YT_SHORTS -> YT_SHORTS_IDS
            Feed.IG_REELS -> IG_REELS_IDS
            Feed.FB_REELS -> FB_REELS_IDS
        }
        return if (hasId(root, ids)) feed.label else null
    }

    /** Breadth-first search for any visible node whose view id contains one of the markers (bounded). */
    private fun hasId(root: AccessibilityNodeInfo, markers: List<String>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 400) {
            val n = queue.removeFirst(); seen++
            val id = n.viewIdResourceName
            if (id != null && n.isVisibleToUser && markers.any { id.contains(it) }) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private fun browserUrl(root: AccessibilityNodeInfo): String? {
        for (id in URL_BAR_IDS) root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.text?.let { return it.toString() }
        return null
    }

    // ---------------- app-time nudges ----------------
    private val nudgeTick = object : Runnable {
        override fun run() {
            main.postDelayed(this, 30_000)
            if (!prefs.nudgesEnabled) return
            val label = DISTRACTING[fgPkg] ?: return
            if (prefs.focusEnabled && prefs.rule(fgPkg)?.mode == FocusMode.WHOLE) return   // focus mode handles it
            val now = System.currentTimeMillis()
            if ((snoozedUntil[fgPkg] ?: 0) > now) return
            val mins = prefs.nudgeMinutes
            val due = nextNudgeAt[fgPkg]?.takeIf { it > fgSince } ?: (fgSince + mins * 60_000L)
            if (now >= due) {
                nextNudgeAt[fgPkg] = now + mins * 60_000L
                Bus.cat?.onNudge(label, ((now - fgSince) / 60_000).toInt().coerceAtLeast(1), fgPkg)
            }
        }
    }
    fun snooze(pkg: String, minutes: Int) { if (pkg.isNotEmpty()) nextNudgeAt[pkg] = System.currentTimeMillis() + minutes * 60_000L }
    fun snoozeToday(pkg: String) {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
        if (pkg.isNotEmpty()) snoozedUntil[pkg] = c.timeInMillis
    }

    // ---------------- keyboard ----------------
    private fun imeVisible() = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)

    /** Only a regular docked keyboard (full width, at the bottom) becomes the cat's floor; floating ones are ignored. */
    private fun updateKeyboard() {
        val ime = runCatching { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrNull()
        val dm = resources.displayMetrics
        Bus.imeTop = ime?.let { w ->
            val r = Rect().also(w::getBoundsInScreen)
            val docked = r.width() >= dm.widthPixels * 0.9f && r.bottom >= dm.heightPixels - 160 * dm.density &&
                r.height() > 100 * dm.density && r.height() < dm.heightPixels * 0.6f
            if (docked) r.top else null
        }
    }

    // ---------------- actions ----------------
    // (forgetting the front app afterwards means reopening the same app counts as a fresh switch)
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean { fgPkg = ""; return performGlobalAction(GLOBAL_ACTION_HOME) }
    fun openShade() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    companion object {
        val BROWSERS = setOf("com.android.chrome", "com.brave.browser", "com.microsoft.emmx", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.opera.browser")
        val URL_BAR_IDS = listOf(
            "com.android.chrome:id/url_bar", "com.brave.browser:id/url_bar", "com.microsoft.emmx:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.opera.browser:id/url_field",
        )
        // Screen markers for the short-video players (view ids; may need updates as the apps change).
        val YT_SHORTS_IDS = listOf("reel_recycler", "reel_player_page_container", "shorts_player", "reel_watch_player")
        val IG_REELS_IDS = listOf("clips_viewer_view_pager", "clips_viewer_container", "clips_video_container", "clips_single_media_viewer")
        val FB_REELS_IDS = listOf("reels_viewer", "reel_viewer", "fb_shorts")
        /** Screens that refuse taps while an ordinary app overlay is showing (only matters for the fallback host). */
        val SHY = setOf(
            "com.android.settings", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.android.vending",
            "com.google.android.gms", "com.google.android.setupwizard", "com.android.credentialmanager",
        )
        val DISTRACTING = mapOf(
            "com.instagram.android" to "Instagram", "com.google.android.youtube" to "YouTube", "com.facebook.katana" to "Facebook",
            "com.zhiliaoapp.musically" to "TikTok", "com.twitter.android" to "X", "com.snapchat.android" to "Snapchat",
            "com.reddit.frontpage" to "Reddit",
        )
    }
}
