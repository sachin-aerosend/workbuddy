package com.workbuddy.cat

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Setup checklist + a switch for every feature. */
class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        refresh++
        val p = Prefs.get(this)
        if (Settings.canDrawOverlays(this)) { p.onboarded = true; CatService.start(this) }
    }

    // ---------------- permissions ----------------
    private fun hasOverlay() = Settings.canDrawOverlays(this)
    private fun hasWatcher(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, WatchService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
    private fun hasNotifications() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun batteryFree() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun openOverlay() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    private fun askNotifications() { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
    private fun openBattery() = startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    private fun openAppInfo() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh++
    }

    // ---------------- UI ----------------
    @Composable
    private fun Screen() {
        @Suppress("UNUSED_EXPRESSION") refresh   // recompose after returning from system settings
        val p = Prefs.get(this)
        var v by androidx.compose.runtime.remember { mutableIntStateOf(0) }
        fun set(block: () -> Unit) { block(); v++ }
        @Suppress("UNUSED_EXPRESSION") v

        Column(
            Modifier.fillMaxSize().background(Ink.cream).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CatSprite(if (hasOverlay()) "happy" else "idle", 76.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("WorkBuddy", style = H1)
                    Text("A tiny cat that keeps you off Reels.", style = Body)
                }
            }

            // ---- setup ----
            val steps = listOf(hasOverlay(), hasWatcher(), hasNotifications())
            if (steps.any { !it } || !batteryFree()) InkCard(fill = Ink.paper) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Set up your cat", style = H2)
                    Step("1", "Let the cat onto your screen", "Android calls this “Display over other apps”. It's how she can walk along the edge of your screen.", hasOverlay(), "Allow") { openOverlay() }
                    Step("2", "Let the cat spot Reels & Shorts", "Turn on “WorkBuddy” in Accessibility. She only checks whether a short-video feed is on screen, how long you've been in an app, and *that* you're typing (never what). Nothing is stored or sent anywhere.", hasWatcher(), "Open settings") { openAccessibility() }
                    if (!hasWatcher()) Text("Greyed out, or “Restricted setting”? Open App info → ⋮ (top right) → “Allow restricted settings”, then try again.", style = Small)
                    if (!hasWatcher()) PillButton("Open App info", primary = false) { openAppInfo() }
                    Step("3", "Reminders & nudges", "Allow notifications so she can ring when your reminder is due.", hasNotifications(), "Allow") { askNotifications() }
                    Step("4", "Keep her awake (optional)", "Some phones put apps to sleep. Set WorkBuddy to “Unrestricted” / “Don't optimise” so she stays around.", batteryFree(), "Open") { openBattery() }
                }
            }

            // ---- the cat ----
            InkCard {
                Column {
                    Text("Your cat", style = H2)
                    ToggleRow("Cat on screen", "Off = features keep working quietly, with notifications instead of the cat.", p.catEnabled) { on -> set { p.catEnabled = on } }
                    Text("Where she lives", style = Strong, modifier = Modifier.padding(top = 8.dp))
                    ChipRow { Zone.entries.forEach { z -> Chip(z.label, p.zone == z) { set { p.zone = z } } } }
                    Text("Size", style = Strong, modifier = Modifier.padding(top = 8.dp))
                    ChipRow { CatSize.entries.forEach { s -> Chip(s.label, p.size == s) { set { p.size = s } } } }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Ink.paper)
                    ToggleRow("Battery saver", "Slower animation and a lazier cat.", p.batterySaver) { on -> set { p.batterySaver = on } }
                    ToggleRow("Follow the phone's battery saver", null, p.autoBatterySaver) { on -> set { p.autoBatterySaver = on } }
                    ToggleRow("Quiet mode", "No zoomies, ball or rolling around. Just naps and stretches.", p.quietMode) { on -> set { p.quietMode = on } }
                    ToggleRow("Hide in full screen", "Steps aside for videos and games.", p.hideFullscreen) { on -> set { p.hideFullscreen = on } }
                    ToggleRow(
                        "Hide while screen recording",
                        if (Build.VERSION.SDK_INT >= 35) "She steps away while your screen is recorded or shared, and comes back after."
                        else "Needs Android 15 or newer. On this phone she'll show up in recordings.",
                        p.hideFromRecording, enabled = Build.VERSION.SDK_INT >= 35,
                    ) { on -> set { p.hideFromRecording = on } }
                }
            }

            // ---- swatting ----
            InkCard {
                Column {
                    Text("Swat short videos", style = H2)
                    ToggleRow("Swat away short-video feeds", "She runs over and presses Back for you.", p.swatEnabled) { on -> set { p.swatEnabled = on } }
                    Feed.entries.forEach { f -> ToggleRow(f.label, null, p.feedOn(f), enabled = p.swatEnabled) { on -> set { p.setFeed(f, on) } } }
                    ToggleRow("Also in the browser", "Chrome, Brave, Edge, Firefox, Samsung Internet.", p.swatInBrowser, enabled = p.swatEnabled) { on -> set { p.swatInBrowser = on } }
                }
            }

            // ---- nudges, typing, reminders ----
            InkCard {
                Column {
                    Text("Nudges & company", style = H2)
                    ToggleRow("“Take a break?” nudges", "After a while in Instagram, YouTube, TikTok, X, Reddit…", p.nudgesEnabled) { on -> set { p.nudgesEnabled = on } }
                    if (p.nudgesEnabled) ChipRow { listOf(10, 20, 30, 45).forEach { m -> Chip("$m min", p.nudgeMinutes == m) { set { p.nudgeMinutes = m } } } }
                    ToggleRow("Typing buddy", "She taps a tiny keyboard while you type in apps (Chrome's address bar doesn't tell her).", p.typingBuddy) { on -> set { p.typingBuddy = on } }
                    ToggleRow("Reminders & focus timer", "Long-press the cat → Set a reminder / Focus 25 min.", p.remindersEnabled) { on -> set { p.remindersEnabled = on } }
                }
            }

            InkCard(fill = Ink.mint) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How to play", style = H2)
                    Text("• Tap her to pet her\n• Long-press for her menu\n• Drag to pick her up, fling to throw\n• Quick Settings tile: show / hide in one tap", style = Body)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun Step(n: String, title: String, text: String, done: Boolean, action: String, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.Top) {
            Text(if (done) "✓" else n, style = H2.copy(color = if (done) Ink.stripe else Ink.ink), modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Strong)
                if (!done) {
                    Text(text.replace("*", ""), style = Small)
                    Spacer(Modifier.height(8.dp))
                    PillButton(action, onClick = onClick)
                }
            }
        }
    }
}
