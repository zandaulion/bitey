package com.zandaulion.bitey

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A Quick Settings tile that opens Bitey straight into a meal photo.
 *
 * It is a button, not a switch: it has no on or off, so it always shows as
 * inactive. The photo itself goes through the page's usual Bitey AI gate (see
 * [LaunchActions]); without a subscription the tile opens the plans instead.
 */
class PhotoTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Behind the lock screen the person unlocks first; the camera and the
        // diary are not for whoever is holding a locked phone.
        if (isLocked) unlockAndRun(::openPhoto) else openPhoto()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openPhoto() {
        // Reuse a running Bitey rather than stacking a second one on it: the
        // existing activity receives the action through onNewIntent.
        val intent = LaunchActions.intent(this, "photo").addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 refuses the Intent form from a tile.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
