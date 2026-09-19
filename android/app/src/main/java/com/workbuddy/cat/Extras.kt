package com.workbuddy.cat

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Is the WorkBuddy accessibility service switched on? */
fun watcherEnabled(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(ctx, WatchService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}

/** Can the cat appear at all: either the accessibility host or the "display over other apps" permission. */
fun canShowCat(ctx: Context) = watcherEnabled(ctx) || Settings.canDrawOverlays(ctx)

/** Quick Settings tile: show / hide the cat from the notification shade. */
class CatTileService : TileService() {
    override fun onStartListening() { refresh() }
    override fun onClick() {
        val p = Prefs.get(this)
        val showing = p.catEnabled && p.pausedUntil < System.currentTimeMillis()
        if (showing) p.catEnabled = false else { p.catEnabled = true; p.pausedUntil = 0; if (canShowCat(this)) runCatching { CatService.start(this) } }
        refresh()
    }
    private fun refresh() {
        val p = Prefs.get(this)
        qsTile?.apply {
            state = if (p.catEnabled && p.pausedUntil < System.currentTimeMillis()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "WorkBuddy"
            updateTile()
        }
    }
}

/** Quick Settings tile: Focus mode on / off. */
class FocusTileService : TileService() {
    override fun onStartListening() { refresh() }
    override fun onClick() { val p = Prefs.get(this); p.focusEnabled = !p.focusEnabled; refresh() }
    private fun refresh() {
        val p = Prefs.get(this)
        qsTile?.apply {
            state = if (p.focusEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Focus mode"
            if (android.os.Build.VERSION.SDK_INT >= 29) subtitle = if (p.focusEnabled) "on" else "off"
            updateTile()
        }
    }
}

/** Brings the cat back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs.get(ctx)
        if (p.onboarded && canShowCat(ctx)) runCatching { CatService.start(ctx) }
    }
}
