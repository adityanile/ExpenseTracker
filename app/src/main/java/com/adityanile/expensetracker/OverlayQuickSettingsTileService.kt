package com.adityanile.expensetracker

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class OverlayQuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        if (OverlayTogglePreferences.isAccessibilityServiceEnabled(this)) {
            OverlayTogglePreferences.setOverlayEnabled(this, enabled = false)
            sendDisableAccessibilityBroadcast()
            updateTileState()
            return
        }

        OverlayTogglePreferences.setOverlayEnabled(this, enabled = true)
        openAccessibilitySettings()
        updateTileState()
    }

    private fun sendDisableAccessibilityBroadcast() {
        sendBroadcast(
            Intent(OverlayTogglePreferences.ACTION_DISABLE_ACCESSIBILITY_SERVICE)
                .setPackage(packageName),
        )
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                1002,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivity(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isAccessibilityEnabled = OverlayTogglePreferences.isAccessibilityServiceEnabled(this)

        tile.state = if (isAccessibilityEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.overlay_tile_label)
        tile.updateTile()
    }
}
