package com.zandaulion.bitey

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * Ways into Bitey that start a logging action straight away: the long-press
 * shortcuts on the launcher icon and the Quick Settings tile.
 *
 * Each one opens [MainActivity] with an action name, and the activity hands it
 * to the page exactly as a tap on the native action bar would. Photo and
 * Gallery therefore pass through the same Bitey AI entitlement gate; a
 * shortcut is a quicker way to the button, never a way around it.
 */
object LaunchActions {
    const val EXTRA_ACTION = "com.zandaulion.bitey.extra.ACTION"

    /** The action bar's own names, the only ones the page understands. */
    val ACTIONS = setOf("photo", "gallery", "barcode", "manual")

    fun intent(context: Context, action: String): Intent =
        Intent(context, MainActivity::class.java)
            // Launcher shortcuts must carry an action; which one is irrelevant.
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_ACTION, action)

    /** The action an intent asks for, or null for an ordinary launch. */
    fun from(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_ACTION)?.takeIf { it in ACTIONS }

    private var published: List<String>? = null

    /**
     * The long-press menu, in the page's current language.
     *
     * Dynamic rather than static shortcuts: a static shortcuts.xml names the
     * target package literally, which the debug build (`.debug`) does not
     * share, and its labels would follow the phone's language rather than the
     * one chosen in Bitey. The page sends its labels on every start and every
     * language change, so this runs with the words already on screen.
     */
    fun publish(context: Context, manual: String, barcode: String, gallery: String, photo: String) {
        val labels = listOf(photo, gallery, barcode, manual)
        if (labels == published) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val entries = listOf(
            Triple("photo", photo, R.drawable.ic_shortcut_photo),
            Triple("gallery", gallery, R.drawable.ic_shortcut_gallery),
            Triple("barcode", barcode, R.drawable.ic_shortcut_barcode),
            Triple("manual", manual, R.drawable.ic_shortcut_manual),
        )
        val shortcuts = entries.mapIndexed { rank, (action, label, icon) ->
            ShortcutInfo.Builder(context, action)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(Icon.createWithResource(context, icon))
                .setIntent(intent(context, action))
                // Rank 0 sits nearest the icon: the photo, Bitey's main route.
                .setRank(rank)
                .build()
        }
        // A launcher can cap the count; a refusal here must not touch the app.
        runCatching {
            val max = manager.maxShortcutCountPerActivity
            manager.dynamicShortcuts = shortcuts.take(max)
            published = labels
        }
    }
}
