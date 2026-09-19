package com.workbuddy.cat

import android.Manifest
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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
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
        if (canShowCat(this)) { p.onboarded = true; runCatching { CatService.start(this) } }
    }

    // ---------------- permissions ----------------
    private fun hasOverlay() = Settings.canDrawOverlays(this)
    private fun hasWatcher() = watcherEnabled(this)
    private fun hasNotifications() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun batteryFree() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun openOverlay() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    private fun askNotifications() { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
    private fun openBattery() = startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    private fun openAppInfo() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    private fun openUrl(u: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh++
    }

    // ---------------- UI ----------------
    @Composable
    private fun Screen() {
        @Suppress("UNUSED_EXPRESSION") refresh   // recompose after returning from system settings
        val p = Prefs.get(this)
        var v by remember { mutableIntStateOf(0) }
        fun set(block: () -> Unit) { block(); v++ }
        @Suppress("UNUSED_EXPRESSION") v

        Column(
            Modifier.fillMaxSize().background(Ink.cream).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CatSprite(if (hasWatcher()) "happy" else "idle", 76.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("WorkBuddy", style = H1)
                    Text("A tiny cat that keeps you off Reels.", style = Body)
                }
            }

            // ---- setup ----
            if (!hasWatcher() || !hasNotifications() || !batteryFree()) InkCard(fill = Ink.paper) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Set up your cat", style = H2)
                    Step("1", "Let the cat see your screen", "Turn on “WorkBuddy cat” in Accessibility. That's how she lives in the status bar, spots Reels & Shorts, runs Focus mode and types along. She only checks which app and screen is open and that you're typing, never what. Nothing is stored or sent anywhere.", hasWatcher(), "Open settings") { openAccessibility() }
                    if (!hasWatcher()) Text("Greyed out, or “Restricted setting”? Open App info → ⋮ (top right) → “Allow restricted settings”, then try again.", style = Small)
                    if (!hasWatcher()) PillButton("Open App info", primary = false) { openAppInfo() }
                    Step("2", "Reminders, water & nudges", "Allow notifications so she can ring when a reminder is due.", hasNotifications(), "Allow") { askNotifications() }
                    Step("3", "Keep her awake", "Set WorkBuddy to “Unrestricted” / “Don't optimise” so the phone doesn't put her to sleep. On Tecno / Infinix (HiOS) also allow Auto-start for WorkBuddy.", batteryFree(), "Open") { openBattery() }
                    if (!batteryFree()) PillButton("Tips for your phone (dontkillmyapp.com)", primary = false) { openUrl("https://dontkillmyapp.com/") }
                    if (!hasWatcher() && !hasOverlay()) Step("4", "Fallback: display over other apps (optional)", "Only needed if you keep the accessibility service off. She'll then live on the bottom edge instead of the status bar.", false, "Allow") { openOverlay() }
                }
            }

            // ---- the cat ----
            InkCard {
                Column {
                    Text("Your cat", style = H2)
                    ToggleRow("Cat on screen", "Off = features keep working quietly, with notifications instead of the cat.", p.catEnabled) { on -> set { p.catEnabled = on } }
                    Text("Where she lives", style = Strong, modifier = Modifier.padding(top = 8.dp))
                    ChipRow { Zone.entries.forEach { z -> Chip(z.label, p.zone == z) { set { p.zone = z } } } }
                    if (p.zone == Zone.STATUS) Text(
                        if (hasWatcher()) "Tiny, next to the clock. Tap her to bring her down to play; long-press for her menu. Swiping down still opens your notifications."
                        else "Needs the accessibility service (step 1). Until then she lives on the bottom edge.",
                        style = Small, modifier = Modifier.padding(top = 4.dp),
                    )
                    Text("Size", style = Strong, modifier = Modifier.padding(top = 8.dp))
                    ChipRow { CatSize.entries.forEach { s -> Chip(s.label, p.size == s) { set { p.size = s } } } }
                    Text("When summoned, she plays for", style = Strong, modifier = Modifier.padding(top = 8.dp))
                    ChipRow { listOf(60 to "1 min", 180 to "3 min", 0 to "until sent back").forEach { (s, l) -> Chip(l, p.playSeconds == s) { set { p.playSeconds = s } } } }
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

            // ---- focus mode ----
            InkCard(fill = Ink.blush) {
                Column {
                    Text("Focus mode 🎯", style = H2)
                    ToggleRow("Focus mode", "Limits the apps you pick. Nothing is blocked until you turn this on (there's a Quick Settings tile too).", p.focusEnabled) { on -> set { p.focusEnabled = on } }
                    val rules = p.focusRules
                    if (rules.isEmpty()) Text("No apps yet. Add Instagram, YouTube, WhatsApp… anything that eats your time.", style = Small, modifier = Modifier.padding(vertical = 6.dp))
                    rules.forEach { r ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.label, style = Strong, modifier = Modifier.weight(1f))
                                Text("✕", style = Strong.copy(color = Ink.stripe), modifier = Modifier.clickable { set { p.focusRules = rules.filter { it.pkg != r.pkg } } }.padding(8.dp))
                            }
                            ChipRow {
                                Chip("Whole app", r.mode == FocusMode.WHOLE) { set { p.focusRules = rules.map { if (it.pkg == r.pkg) it.copy(mode = FocusMode.WHOLE) else it } } }
                                if (Feed.forPackage(r.pkg) != null) Chip("Just Reels / Shorts", r.mode == FocusMode.FEED) { set { p.focusRules = rules.map { if (it.pkg == r.pkg) it.copy(mode = FocusMode.FEED) else it } } }
                            }
                            Text(
                                if (r.mode == FocusMode.WHOLE) "When you open it she asks “how long?” (5 / 10 / 15 / custom). The countdown ticks next to her in the status bar; when it hits zero she closes the app."
                                else "The app stays open; only its Reels / Shorts get swatted the moment you land on them.",
                                style = Small,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    PillButton("＋ Add an app") { startActivity(Intent(this@MainActivity, AppPickerActivity::class.java)) }
                    Text("After the time runs out, keep the app closed for", style = Strong, modifier = Modifier.padding(top = 12.dp))
                    ChipRow { listOf(5, 15, 30, 60).forEach { m -> Chip("$m min", p.focusCooldownMinutes == m) { set { p.focusCooldownMinutes = m } } } }
                    ToggleRow("Also catch Reels & Shorts in the browser", "Chrome, Brave, Edge, Firefox, Samsung Internet.", p.swatInBrowser) { on -> set { p.swatInBrowser = on } }
                }
            }

            // ---- water ----
            InkCard(fill = Ink.sky) {
                Column {
                    Text("Water 💧", style = H2)
                    ToggleRow("Water reminders", "Every so often she brings her glass, drinks, and asks if you had one. Only time with the screen on counts.", p.waterEnabled) { on -> set { p.waterEnabled = on } }
                    if (p.waterEnabled) ChipRow { listOf(20, 30, 45, 60, 90).forEach { m -> Chip("$m min", p.waterEveryMinutes == m) { set { p.waterEveryMinutes = m } } } }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("${p.waterToday} today · ${p.waterTotal} total", style = Body, modifier = Modifier.weight(1f))
                        PillButton("I drank one 💧", primary = false) { set { val cat = Bus.cat; if (cat != null) cat.logWater() else { p.waterToday = p.waterToday + 1; p.waterTotal = p.waterTotal + 1 } } }
                    }
                }
            }

            // ---- nudges, typing, reminders ----
            InkCard {
                Column {
                    Text("Nudges & company", style = H2)
                    ToggleRow("“Take a break?” nudges", "After a while in Instagram, YouTube, TikTok, X, Reddit… (apps with a Focus mode timer are handled there instead)", p.nudgesEnabled) { on -> set { p.nudgesEnabled = on } }
                    if (p.nudgesEnabled) ChipRow { listOf(10, 20, 30, 45).forEach { m -> Chip("$m min", p.nudgeMinutes == m) { set { p.nudgeMinutes = m } } } }
                    ToggleRow("Typing buddy", "She taps a tiny keyboard while you type in apps (Chrome's address bar doesn't tell her).", p.typingBuddy) { on -> set { p.typingBuddy = on } }
                    ToggleRow("Reminders & focus timer", "Long-press the cat → Set a reminder / Focus 25 min.", p.remindersEnabled) { on -> set { p.remindersEnabled = on } }
                }
            }

            InkCard(fill = Ink.mint) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How to play", style = H2)
                    Text("• In the status bar: tap her to bring her down, long-press for her menu\n• Out playing: tap to pet, drag to pick her up, fling to throw\n• She goes back up by herself (or long-press → Go back up)\n• Quick Settings tiles: show / hide the cat, Focus mode on / off", style = Body)
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
                    Text(text, style = Small)
                    Spacer(Modifier.height(8.dp))
                    PillButton(action, onClick = onClick)
                }
            }
        }
    }
}
