package com.workbuddy.cat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Where the cat is allowed to live. */
enum class Zone(val label: String) { BOTTOM("Bottom edge"), TOP("Under the status bar"), BOTH("Both edges") }
enum class CatSize(val label: String, val artDp: Float) { S("Small", 2f), M("Medium", 2.5f), L("Large", 3f) }

/** Distracting short-video feeds the cat can swat, per app. */
enum class Feed(val key: String, val label: String, val pkg: String) {
    IG_REELS("ig_reels", "Instagram Reels", "com.instagram.android"),
    YT_SHORTS("yt_shorts", "YouTube Shorts", "com.google.android.youtube"),
    FB_REELS("fb_reels", "Facebook Reels", "com.facebook.katana"),
    TIKTOK("tiktok", "TikTok (whole app)", "com.zhiliaoapp.musically"),
}

data class Reminder(val id: String, val text: String, val due: Long) {
    fun json() = JSONObject().put("id", id).put("text", text).put("due", due)
    companion object { fun from(o: JSONObject) = Reminder(o.getString("id"), o.getString("text"), o.getLong("due")) }
}

/**
 * All settings in one place. Every feature has its own switch so people can untick what they don't want.
 * Listeners get notified on change so the running cat updates instantly.
 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("workbuddy", Context.MODE_PRIVATE)

    var catEnabled by bool("cat_enabled", true)
    var zone: Zone
        get() = runCatching { Zone.valueOf(sp.getString("zone", Zone.BOTTOM.name)!!) }.getOrDefault(Zone.BOTTOM)
        set(v) = sp.edit().putString("zone", v.name).apply()
    var size: CatSize
        get() = runCatching { CatSize.valueOf(sp.getString("size", CatSize.M.name)!!) }.getOrDefault(CatSize.M)
        set(v) = sp.edit().putString("size", v.name).apply()

    // behaviour & comfort
    var batterySaver by bool("battery_saver", false)          // force low-power mode
    var autoBatterySaver by bool("auto_battery_saver", true)  // follow the phone's battery saver
    var quietMode by bool("quiet_mode", false)                // no zoomies / ball / rolling
    var hideFullscreen by bool("hide_fullscreen", true)
    var hideFromRecording by bool("hide_from_recording", true)

    // features
    var swatEnabled by bool("swat_enabled", true)
    var swatInBrowser by bool("swat_in_browser", true)
    fun feedOn(f: Feed) = sp.getBoolean("feed_${f.key}", f != Feed.TIKTOK)
    fun setFeed(f: Feed, on: Boolean) = sp.edit().putBoolean("feed_${f.key}", on).apply()

    var nudgesEnabled by bool("nudges_enabled", true)
    var nudgeMinutes by int("nudge_minutes", 20)
    var typingBuddy by bool("typing_buddy", true)
    var remindersEnabled by bool("reminders_enabled", true)

    var pausedUntil by long("paused_until", 0L)
    var onboarded by bool("onboarded", false)

    var reminders: List<Reminder>
        get() = runCatching {
            val a = JSONArray(sp.getString("reminders", "[]"))
            (0 until a.length()).map { Reminder.from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("reminders", JSONArray(v.map { it.json() }).toString()).apply()

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

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
