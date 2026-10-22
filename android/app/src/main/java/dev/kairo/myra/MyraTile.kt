package dev.kairo.myra

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A "Myraa" tile in the quick-settings panel: tap it to talk. */
class MyraTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (MyraService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (MyraService.running) "Tap to talk" else "Off"
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (MyraService.running) {
            MyraService.send(this, MyraService.ACTION_ACTIVATE)
            return
        }
        // Not running yet: go through an activity, so Android allows the microphone to start.
        val i = Intent(this, TriggerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
