package com.workbuddy.cat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: show / hide the cat from the notification shade. */
class CatTileService : TileService() {
    override fun onStartListening() { refresh() }
    override fun onClick() {
        val p = Prefs.get(this)
        val showing = p.catEnabled && p.pausedUntil < System.currentTimeMillis()
        if (showing) p.catEnabled = false else { p.catEnabled = true; p.pausedUntil = 0; if (Settings.canDrawOverlays(this)) CatService.start(this) }
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

/** Brings the cat back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs.get(ctx)
        if (p.onboarded && Settings.canDrawOverlays(ctx)) runCatching { CatService.start(ctx) }
    }
}
