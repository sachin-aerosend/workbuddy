package com.workbuddy.cat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Where the cat lives. STATUS is the phone-first default: tiny, in the status bar, out of your way. */
enum class Zone(val label: String) { STATUS("Status bar"), BOTTOM("Bottom edge"), TOP("Under the status bar"), BOTH("Both edges") }

/** artDp = screen dp per art pixel when she's full size; barPx = whole art-pixel scale in the status bar. */
enum class CatSize(val label: String, val artDp: Float, val barPx: Int) { S("Small", 2f, 2), M("Medium", 2.5f, 3), L("Large", 3f, 4) }

/** Short-video feeds the cat can swat inside an app, and the same feeds opened in a browser. */
enum class Feed(val key: String, val label: String, val pkg: String, val urlPrefixes: List<String>) {
    IG_REELS("ig_reels", "Instagram Reels", "com.instagram.android", listOf("instagram.com/reels", "instagram.com/reel/")),
    YT_SHORTS("yt_shorts", "YouTube Shorts", "com.google.android.youtube", listOf("youtube.com/shorts")),
    FB_REELS("fb_reels", "Facebook Reels", "com.facebook.katana", listOf("facebook.com/reel")),
    TIKTOK("tiktok", "TikTok", "com.zhiliaoapp.musically", listOf("tiktok.com"));

    companion object { fun forPackage(pkg: String) = entries.firstOrNull { it.pkg == pkg } }
}

/** How Focus mode treats one app: block the whole app (with a "how long?" allowance) or only its feed. */
enum class FocusMode(val label: String) { WHOLE("Whole app"), FEED("Just Reels / Shorts") }

data class FocusRule(val pkg: String, val label: String, val mode: FocusMode) {
    fun json(): JSONObject = JSONObject().put("pkg", pkg).put("label", label).put("mode", mode.name)
    companion object {
        fun from(o: JSONObject) = FocusRule(o.getString("pkg"), o.optString("label", o.getString("pkg")),
            runCatching { FocusMode.valueOf(o.getString("mode")) }.getOrDefault(FocusMode.WHOLE))
    }
}

/** A running allowance for a whole-app rule: `left` ms of foreground time still allowed. */
data class FocusTimer(val pkg: String, val left: Long, val total: Long, val lastUsedAt: Long) {
    fun json(): JSONObject = JSONObject().put("pkg", pkg).put("left", left).put("total", total).put("lastUsedAt", lastUsedAt)
    companion object { fun from(o: JSONObject) = FocusTimer(o.getString("pkg"), o.getLong("left"), o.optLong("total", o.getLong("left")), o.optLong("lastUsedAt", 0L)) }
}

data class Reminder(val id: String, val text: String, val due: Long) {
    fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("due", due)
    companion object { fun from(o: JSONObject) = Reminder(o.getString("id"), o.getString("text"), o.getLong("due")) }
}

/**
 * All settings in one place. Every feature has its own switch so people can untick what they don't want.
 * Listeners get notified on change so the running cat updates instantly.
 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("workbuddy", Context.MODE_PRIVATE)

    // ---- the cat ----
    var catEnabled by bool("cat_enabled", true)
    var zone: Zone
        get() = runCatching { Zone.valueOf(sp.getString("zone", Zone.STATUS.name)!!) }.getOrDefault(Zone.STATUS)
        set(v) = sp.edit().putString("zone", v.name).apply()
    var size: CatSize
        get() = runCatching { CatSize.valueOf(sp.getString("size", CatSize.M.name)!!) }.getOrDefault(CatSize.M)
        set(v) = sp.edit().putString("size", v.name).apply()
    var playSeconds by int("play_seconds", 90)              // how long she stays down after being summoned (0 = until sent back)

    // ---- comfort ----
    var batterySaver by bool("battery_saver", false)          // force low-power mode
    var autoBatterySaver by bool("auto_battery_saver", true)  // follow the phone's battery saver
    var quietMode by bool("quiet_mode", false)                // no zoomies / ball / rolling
    var hideFullscreen by bool("hide_fullscreen", true)
    var hideFromRecording by bool("hide_from_recording", true)

    // ---- focus mode (opt-in) ----
    var focusEnabled by bool("focus_enabled", false)
    // (both are read many times a second by the watcher, so the parsed values are kept)
    @Volatile private var rulesCache: List<FocusRule>? = null
    @Volatile private var blockedCache: Map<String, Long>? = null
    var focusRules: List<FocusRule>
        get() = rulesCache ?: jsonList("focus_rules") { FocusRule.from(it) }.also { rulesCache = it }
        set(v) { rulesCache = v; sp.edit().putString("focus_rules", JSONArray(v.map { it.json() }).toString()).apply() }
    var focusTimers: List<FocusTimer>
        get() = jsonList("focus_timers") { FocusTimer.from(it) }
        set(v) = sp.edit().putString("focus_timers", JSONArray(v.map { it.json() }).toString()).apply()
    /** pkg -> epoch ms until which the app stays closed after its time ran out */
    var focusBlocked: Map<String, Long>
        get() = blockedCache ?: runCatching {
            val o = JSONObject(sp.getString("focus_blocked", "{}")!!)
            o.keys().asSequence().associateWith { o.getLong(it) }
        }.getOrDefault(emptyMap()).also { blockedCache = it }
        set(v) { blockedCache = v; sp.edit().putString("focus_blocked", JSONObject(v).toString()).apply() }
    var focusCooldownMinutes by int("focus_cooldown", 15)
    var swatInBrowser by bool("swat_in_browser", true)
    fun rule(pkg: String) = focusRules.firstOrNull { it.pkg == pkg }
    /** Is this feed watched, in its app or in a browser? Only if Focus mode is on and the app has a FEED rule. */
    fun feedWatched(f: Feed) = focusEnabled && rule(f.pkg)?.mode == FocusMode.FEED

    // ---- nudges, typing, reminders ----
    var nudgesEnabled by bool("nudges_enabled", true)
    var nudgeMinutes by int("nudge_minutes", 20)
    var typingBuddy by bool("typing_buddy", true)
    var remindersEnabled by bool("reminders_enabled", true)

    // ---- water ----
    var waterEnabled by bool("water_enabled", true)
    var waterEveryMinutes by int("water_every", 30)
    var waterAccumMs by long("water_accum", 0L)                // screen-on time since the last glass / reminder
    var waterMisses by int("water_misses", 0)
    var waterTotal by int("water_total", 0)
    var waterToday: Int
        get() = if (sp.getString("water_day", "") == today()) sp.getInt("water_today", 0) else 0
        set(v) = sp.edit().putString("water_day", today()).putInt("water_today", v).apply()

    var pausedUntil by long("paused_until", 0L)
    var onboarded by bool("onboarded", false)

    var reminders: List<Reminder>
        get() = jsonList("reminders") { Reminder.from(it) }
        set(v) = sp.edit().putString("reminders", JSONArray(v.map { it.json() }).toString()).apply()

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    private fun today() = LocalDate.now().toString()
    private fun <T> jsonList(key: String, from: (JSONObject) -> T): List<T> = runCatching {
        val a = JSONArray(sp.getString(key, "[]"))
        (0 until a.length()).mapNotNull { i -> runCatching { from(a.getJSONObject(i)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    }
    private fun int(key: String, def: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) = sp.edit().putInt(key, value).apply()
    }
    private fun long(key: String, def: Long) = object : kotlin.properties.ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getLong(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Long) = sp.edit().putLong(key, value).apply()
    }

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: Prefs(ctx.applicationContext).also { inst = it } }
    }
}
