package com.workbuddy.cat

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Calendar

/**
 * The watcher (an accessibility service). It only looks for three things:
 *  1. a short-video feed on screen (Instagram Reels, YouTube Shorts, Facebook Reels, TikTok, or those pages in a browser)
 *  2. how long you've been in a distracting app (for "take a break?" nudges)
 *  3. *that* you're typing (never what) and where the keyboard is, so the cat can type along on top of it
 * Nothing is stored or sent anywhere.
 */
class WatchService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var lastScan = 0L
    private var lastSwat = 0L
    private var fgPkg = ""
    private var fgSince = 0L                 // when the current distracting-app session started
    private var nextNudgeAt = HashMap<String, Long>()
    private val snoozedUntil = HashMap<String, Long>()

    override fun onServiceConnected() {
        prefs = Prefs.get(this)
        Bus.watcher = this
        main.postDelayed(nudgeTick, 30_000)
    }
    override fun onDestroy() { if (Bus.watcher === this) Bus.watcher = null; main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Typing buddy: we only react to *that* a text field changed (or its cursor moved while the
                // keyboard is open — some apps, like Chrome's address bar, only report that). Never read it;
                // skip password fields.
                val typing = e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || Bus.imeTop != null || imeVisible()
                if (typing && !e.isPassword && pkg != packageName) Bus.cat?.onTyped()
                return
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> { updateKeyboard(); return }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { onForeground(pkg); updateKeyboard() }
        }
        if (prefs.swatEnabled) maybeScan(pkg)
    }

    // ---------------- short-video feeds ----------------
    private fun maybeScan(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < 350 || now - lastSwat < 2500) return
        val watched = Feed.entries.any { it.pkg == pkg && prefs.feedOn(it) } || (prefs.swatInBrowser && pkg in BROWSERS)
        if (!watched) return
        lastScan = now
        // Check every app window of that package (dialogs can sit on top of the page, e.g. Chrome prompts).
        val roots = runCatching { windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root } }
            .getOrDefault(emptyList()).ifEmpty { listOfNotNull(rootInActiveWindow) }
        val hit = roots.filter { it.packageName?.toString() == pkg }.firstNotNullOfOrNull { detect(pkg, it) }
        if (hit == null) return
        lastSwat = now
        Bus.cat?.onFeed(hit) ?: back()
    }

    /** Returns a label for the feed on screen, or null. */
    private fun detect(pkg: String, root: AccessibilityNodeInfo): String? {
        if (pkg in BROWSERS) {
            val url = browserUrl(root)?.lowercase()?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.")?.removePrefix("m.") ?: return null
            return when {
                prefs.feedOn(Feed.IG_REELS) && (url.startsWith("instagram.com/reels") || url.startsWith("instagram.com/reel/")) -> "Instagram Reels"
                prefs.feedOn(Feed.YT_SHORTS) && url.startsWith("youtube.com/shorts") -> "YouTube Shorts"
                prefs.feedOn(Feed.FB_REELS) && url.startsWith("facebook.com/reel") -> "Facebook Reels"
                prefs.feedOn(Feed.TIKTOK) && url.startsWith("tiktok.com") -> "TikTok"
                else -> null
            }
        }
        return when (pkg) {
            Feed.TIKTOK.pkg -> Feed.TIKTOK.label
            Feed.YT_SHORTS.pkg -> if (hasId(root, YT_SHORTS_IDS)) Feed.YT_SHORTS.label else null
            Feed.IG_REELS.pkg -> if (hasId(root, IG_REELS_IDS)) Feed.IG_REELS.label else null
            Feed.FB_REELS.pkg -> if (hasId(root, FB_REELS_IDS)) Feed.FB_REELS.label else null
            else -> null
        }
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
    private fun onForeground(pkg: String) {
        if (pkg == "com.android.systemui" || pkg == fgPkg) return
        // Sensitive screens ignore taps while any overlay is visible (anti-tapjacking), so the cat steps aside.
        Bus.cat?.setShy(pkg in SHY)
        if (pkg == packageName) return
        fgPkg = pkg; fgSince = System.currentTimeMillis()
    }
    private val nudgeTick = object : Runnable {
        override fun run() {
            main.postDelayed(this, 30_000)
            if (!prefs.nudgesEnabled) return
            val label = DISTRACTING[fgPkg] ?: return
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
            // floating keyboards use a full-screen (mostly transparent) window, so also require a sane height
            val docked = r.width() >= dm.widthPixels * 0.9f && r.bottom >= dm.heightPixels - 160 * dm.density &&
                r.height() > 100 * dm.density && r.height() < dm.heightPixels * 0.6f
            if (docked) r.top else null
        }
    }

    // ---------------- actions ----------------
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    companion object {
        val BROWSERS = setOf("com.android.chrome", "com.brave.browser", "com.microsoft.emmx", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.opera.browser")
        val URL_BAR_IDS = listOf(
            "com.android.chrome:id/url_bar", "com.brave.browser:id/url_bar", "com.microsoft.emmx:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.opera.browser:id/url_field",
        )
        // Screen markers for the short-video players (view ids; checked on the emulator, may need updates as apps change).
        val YT_SHORTS_IDS = listOf("reel_recycler", "reel_player_page_container", "shorts_player", "reel_watch_player")
        val IG_REELS_IDS = listOf("clips_viewer_view_pager", "clips_viewer_container", "clips_video_container", "clips_single_media_viewer")
        val FB_REELS_IDS = listOf("reels_viewer", "reel_viewer", "fb_shorts")
        /** Screens that refuse taps while an overlay is showing: the cat hides while they're open. */
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
